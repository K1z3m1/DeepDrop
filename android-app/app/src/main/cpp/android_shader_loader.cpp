// On-device port of Extract::extractShaders + Extract::translateShader.
//
// On Linux the DLL path is discovered from Steam install locations. On Android
// the user picks the file via SAF and Kotlin copies it to a local path under
// the app's filesDir before calling into native. This file implements the PE
// parsing and caches the precompiled FP16/FP32 SPIR-V resources (Lossless
// Scaling 3.2.2.0+ ships shaders as native SPIR-V) into a caller-chosen cache
// directory — one .spv per resource ID.

#include "android_shader_loader.hpp"

#include <pe-parse/parse.h>

#include <android/log.h>

#include <algorithm>
#include <array>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <exception>
#include <fstream>
#include <string>
#include <sys/stat.h>
#include <unordered_map>
#include <vector>

#include "crash_reporter.hpp"

#define LOG_TAG "lsfg-extract"
#define LOGE(...) ::lsfg_android::ring_logf(LOG_TAG, ANDROID_LOG_ERROR, __VA_ARGS__)
#define LOGI(...) ::lsfg_android::ring_logf(LOG_TAG, ANDROID_LOG_INFO,  __VA_ARGS__)

namespace {

// Resource IDs used by both LSFG 3.1 and the 3.1P "performance" variant.
// Kept in sync with lsfg-vk/src/extract/extract.cpp::nameIdxTable.
constexpr uint32_t kResourceIds[] = {
    255, 256, 257, 258, 259, 260, 261, 262, 263, 264, 265, 266,
    267, 268, 269, 270, 271, 272, 273, 274, 275, 276, 277, 278, 279,
    280, 281, 282, 283, 284, 285, 286, 287, 288, 289,
    290, 291, 292, 293, 294, 295, 296, 297, 298, 299, 300, 301, 302,
};

// FP16 SPIR-V variants live as RCDATA at DXBC_id + 49 (range 304..351).
// They're already SPIR-V — no DXBC translation needed. The blob has
// OpCapability Float16 and mixed FP16/FP32 OpTypeFloat. See
// patches/LosslessScaling/findings.md for the full mapping table.
constexpr uint32_t kFp16IdOffset = 49;

// FP32 SPIR-V variants live as RCDATA at DXBC_id + 98 (range 353..400).
// Same shaders, no Float16 capability, MemoryModel Logical GLSL450 (no
// VulkanMemoryModel) — verified in _analysis/fp32/ via dump_fp32_spv.py.
// This is the default path — see kFp32SpirvIdOffset in the header.
using lsfg_android::kFp32SpirvIdOffset;

// SPIR-V LE magic word (0x07230203). Used to validate FP16 blobs before
// caching them — if THS reshuffles resource layout in a future Lossless.dll
// update, IDs 304..351 may stop being SPIR-V; we silently skip those instead
// of caching garbage.
constexpr std::array<uint8_t, 4> kSpirvMagic{0x03, 0x02, 0x23, 0x07};

// Sanity cap for a single RCDATA resource we're willing to copy out of the
// PE resource table. Legitimate shader blobs here are a few KB; this exists
// so a corrupted or maliciously crafted Lossless.dll that claims an absurd
// resource size (bufLen is attacker-controlled input straight from the file)
// can't force a huge/unbounded heap allocation.
constexpr size_t kMaxResourceBytes = 16u * 1024u * 1024u; // 16 MiB

struct ExtractionCtx {
    std::unordered_map<uint32_t, std::vector<uint8_t>> *out;
};

int on_resource(void *userData, const peparse::resource &res) {
    auto *ctx = static_cast<ExtractionCtx *>(userData);
    if (res.type != peparse::RT_RCDATA || res.buf == nullptr || res.buf->bufLen <= 0) {
        return 0;
    }
    if (static_cast<size_t>(res.buf->bufLen) > kMaxResourceBytes) {
        LOGE("Skipping oversized RCDATA resource id=%u (%llu bytes > cap) — "
             "corrupt or untrusted DLL?", res.name,
             static_cast<unsigned long long>(res.buf->bufLen));
        return 0;
    }
    std::vector<uint8_t> data(res.buf->bufLen);
    std::copy_n(res.buf->buf, res.buf->bufLen, data.data());
    (*ctx->out)[res.name] = std::move(data);
    return 0;
}

// SPIR-V header layout: [magic][version][generator][bound][schema]
// Vulkan 1.1 supports up to SPIR-V 1.3 (0x00010300). The dxvk compiler
// targets SPIR-V 1.6 which Mali rejects at JIT compilation time. Patch the
// version word down so the driver accepts the bytecode.
bool read_spirv_word(const std::vector<uint8_t> &spirv, size_t wordIndex, uint32_t &out) {
    if (wordIndex * 4u + 4u > spirv.size()) {
        return false;
    }
    std::memcpy(&out, spirv.data() + wordIndex * 4u, sizeof(out));
    return true;
}

bool write_spirv_word(std::vector<uint8_t> &spirv, size_t wordIndex, uint32_t value) {
    if (wordIndex * 4u + 4u > spirv.size()) {
        return false;
    }
    std::memcpy(spirv.data() + wordIndex * 4u, &value, sizeof(value));
    return true;
}

void cap_spirv_version(std::vector<uint8_t> &spirv) {
    if (spirv.size() < 20) return;
    uint32_t ver = 0;
    if (!read_spirv_word(spirv, 1, ver)) return;
    if (ver > 0x00010300u) {
        ver = 0x00010300u;
        write_spirv_word(spirv, 1, ver);
    }
}

// Renumber Binding decorations to match the descriptor layout expected by the
// framegen library (lsfg-vk-android).
//
// The framework builds its VkDescriptorSetLayout by counting bindings from 0
// in a *type-grouped* order: first uniform buffers, then samplers, then
// sampled images, then storage images. See e.g.
// `framegen/v3.1p_src/shaders/mipmaps.cpp:19-23` which declares
//   { 1, UNIFORM_BUFFER }, { 1, SAMPLER }, { 1, SAMPLED_IMAGE }, { 7, STORAGE_IMAGE }
// and ShaderModule fills `binding = 0..N-1` over that flattened sequence.
//
// The FP16 path consumes precompiled SPIR-V straight from
// Lossless.dll where the bindings are HLSL register slots and the
// OpDecorate ordering is `sampler, sampled_image, storage_image..., cb`
// (cb last) — flattened in declaration order this puts the uniform buffer
// at slot 9 instead of 0, mismatching the layout and producing the symptoms
// the user reported (black flashes alternating with valid frames, top-left
// black rectangle that grows with flowScale).
//
// The fix: classify each variable by its underlying type kind, then assign
// dense bindings in the framework's expected group order, breaking ties by
// the variable's original binding number so the relative order within a
// group (e.g. Output0..Output6) is preserved.
//
// Returns true on success. Returns false on an unrecognised SPIR-V header
// or malformed module so the extractor can skip the blob without corrupting
// the cache.
bool rewrite_spirv_bindings_dense(std::vector<uint8_t> &spirv) {
    if (spirv.size() < 20 || (spirv.size() % 4) != 0) {
        return false;
    }
    uint32_t magic = 0;
    if (!read_spirv_word(spirv, 0, magic) || magic != 0x07230203u) {
        return false;
    }
    const size_t wordCount = spirv.size() / 4;

    // SPIR-V opcodes / decorations we care about.
    constexpr uint32_t kOpName = 5;
    constexpr uint32_t kOpTypeImage = 25;
    constexpr uint32_t kOpTypeSampler = 26;
    constexpr uint32_t kOpTypeSampledImage = 27;
    constexpr uint32_t kOpTypeStruct = 30;
    constexpr uint32_t kOpTypePointer = 32;
    constexpr uint32_t kOpVariable = 59;
    constexpr uint32_t kOpDecorate = 71;
    constexpr uint32_t kOpFunction = 54;
    constexpr uint32_t kDecorationBinding = 33;

    // Kind ordering matches the descriptor type order the framework emits at
    // ShaderModule build time. Lower number = earlier in the layout.
    enum Kind : int {
        KindUnknown = 4,
        KindUniformBuffer = 0,
        KindSampler = 1,
        KindSampledImage = 2,
        KindStorageImage = 3,
    };

    struct BindingSite {
        size_t valueWordIndex;
        uint32_t varId;
        uint32_t origBinding;
        Kind kind;
    };

    // First pass: gather type info, variable->pointer mapping, and binding
    // sites. Stop at OpFunction — bindings only appear in the declaration
    // section before any function bodies.
    std::unordered_map<uint32_t, Kind> typeKind;            // type id -> Kind
    std::unordered_map<uint32_t, uint32_t> ptrPointee;       // pointer type id -> pointee type id
    std::unordered_map<uint32_t, uint32_t> varType;          // var id -> pointer type id
    std::vector<BindingSite> sites;
    sites.reserve(32);

    size_t i = 5; // skip 5-word SPIR-V header
    while (i < wordCount) {
        uint32_t header = 0;
        if (!read_spirv_word(spirv, i, header)) {
            return false;
        }
        const uint32_t wc = (header >> 16) & 0xFFFFu;
        const uint32_t op = header & 0xFFFFu;
        if (wc == 0 || i + wc > wordCount) {
            return false; // malformed
        }
        if (op == kOpFunction) {
            break;
        }
        switch (op) {
            case kOpTypeSampler: {
                uint32_t id = 0;
                if (wc >= 2 && read_spirv_word(spirv, i + 1, id)) {
                    typeKind[id] = KindSampler;
                }
                break;
            }
            case kOpTypeImage: {
                uint32_t id = 0;
                uint32_t sampledKind = 0;
                if (wc >= 8 && read_spirv_word(spirv, i + 1, id) &&
                    read_spirv_word(spirv, i + 7, sampledKind)) {
                    // OpTypeImage: id, sampledType, dim, depth, arrayed, ms, sampled, format
                    // sampled == 1: sampled image (read-only); sampled == 2: storage image (read/write)
                    typeKind[id] = (sampledKind == 2) ? KindStorageImage : KindSampledImage;
                }
                break;
            }
            case kOpTypeSampledImage: {
                uint32_t id = 0;
                if (wc >= 2 && read_spirv_word(spirv, i + 1, id)) {
                    typeKind[id] = KindSampledImage;
                }
                break;
            }
            case kOpTypeStruct: {
                uint32_t id = 0;
                // We assume any struct backing a binding is a uniform buffer.
                // The CB layout in Lossless.dll is consistent (verified
                // statically across all FP16/FP32 variants).
                if (wc >= 2 && read_spirv_word(spirv, i + 1, id)) {
                    typeKind[id] = KindUniformBuffer;
                }
                break;
            }
            case kOpTypePointer: {
                uint32_t id = 0;
                uint32_t pointee = 0;
                // OpTypePointer: id, storageClass, type
                if (wc == 4 && read_spirv_word(spirv, i + 1, id) &&
                    read_spirv_word(spirv, i + 3, pointee)) {
                    ptrPointee[id] = pointee;
                }
                break;
            }
            case kOpVariable: {
                uint32_t varResultType = 0;
                uint32_t varId = 0;
                // OpVariable: resultType (pointer), resultId, storageClass, [initializer]
                if (wc >= 4 && read_spirv_word(spirv, i + 1, varResultType) &&
                    read_spirv_word(spirv, i + 2, varId)) {
                    varType[varId] = varResultType;
                }
                break;
            }
            case kOpDecorate: {
                uint32_t decoration = 0;
                uint32_t value = 0;
                uint32_t varId = 0;
                if (wc == 4 && read_spirv_word(spirv, i + 2, decoration) &&
                    decoration == kDecorationBinding &&
                    read_spirv_word(spirv, i + 1, varId) &&
                    read_spirv_word(spirv, i + 3, value)) {
                    sites.push_back({i + 3, varId, value, KindUnknown});
                }
                break;
            }
            default:
                break;
        }
        i += wc;
    }

    // Resolve each binding site's kind via varId -> pointer -> pointee type.
    for (auto &s : sites) {
        const auto vt = varType.find(s.varId);
        if (vt == varType.end()) continue;
        const auto pp = ptrPointee.find(vt->second);
        if (pp == ptrPointee.end()) continue;
        const auto tk = typeKind.find(pp->second);
        if (tk != typeKind.end()) {
            s.kind = tk->second;
        }
    }

    // Sort by (kind, original binding) so the framework's flat sequence
    //   [uniform, sampler, sampled, storage_image_0..N-1]
    // lines up. Stable sort to preserve input order for any ties (none
    // expected, but defensive). Returns false if any site stayed unknown:
    // that means we couldn't classify it, and renumbering would silently
    // misroute the shader — better to skip the blob entirely.
    for (const auto &s : sites) {
        if (s.kind == KindUnknown) {
            return false;
        }
    }
    std::stable_sort(sites.begin(), sites.end(), [](const BindingSite &a, const BindingSite &b) {
        if (a.kind != b.kind) return static_cast<int>(a.kind) < static_cast<int>(b.kind);
        return a.origBinding < b.origBinding;
    });

    for (size_t k = 0; k < sites.size(); ++k) {
        if (!write_spirv_word(spirv, sites[k].valueWordIndex, static_cast<uint32_t>(k))) {
            return false;
        }
    }
    return true;
}

bool normalize_spirv_blob(std::vector<uint8_t> &spirv) {
    if (spirv.size() < 20 || (spirv.size() % 4) != 0) {
        return false;
    }
    cap_spirv_version(spirv);
    return rewrite_spirv_bindings_dense(spirv);
}

bool write_file(const std::string &path, const std::vector<uint8_t> &data) {
    std::ofstream f(path, std::ios::binary | std::ios::trunc);
    if (!f) {
        return false;
    }
    f.write(reinterpret_cast<const char *>(data.data()), static_cast<std::streamsize>(data.size()));
    return f.good();
}

} // namespace

namespace lsfg_android {

namespace {
bool is_lossless_3220(const std::string& path) {
    std::ifstream f(path, std::ios::binary | std::ios::ate);
    if (!f) return false;
    const auto sz = f.tellg();
    if (sz <= 0 || sz > static_cast<std::streamoff>(32 * 1024 * 1024)) return false;
    f.seekg(0, std::ios::beg);
    std::vector<uint8_t> data(static_cast<size_t>(sz));
    if (!f.read(reinterpret_cast<char*>(data.data()), static_cast<std::streamsize>(data.size())))
        return false;

    // VS_VERSION_INFO stores ProductVersion as UTF-16LE. Require both the key
    // and the exact 3.2.2.0 value so a later Lossless.dll layout cannot be
    // silently accepted and partially mapped onto the 3.2.2 shader table.
    const char key[] = "ProductVersion";
    const char ver[] = "3.2.2.0";
    bool keyFound = false;
    bool verFound = false;
    for (size_t i = 0; i + 2 * sizeof(key) <= data.size(); ++i) {
        bool ok = true;
        for (size_t j = 0; j < sizeof(key) - 1; ++j) {
            if (data[i + j * 2] != static_cast<uint8_t>(key[j]) ||
                data[i + j * 2 + 1] != 0) { ok = false; break; }
        }
        if (ok) { keyFound = true; break; }
    }
    for (size_t i = 0; i + 2 * sizeof(ver) <= data.size(); ++i) {
        bool ok = true;
        for (size_t j = 0; j < sizeof(ver) - 1; ++j) {
            if (data[i + j * 2] != static_cast<uint8_t>(ver[j]) ||
                data[i + j * 2 + 1] != 0) { ok = false; break; }
        }
        if (ok) { verFound = true; break; }
    }
    return keyFound && verFound;
}
} // namespace

// Renamed to an internal impl; extract_dll_to_spirv (declared in the header)
// wraps this in a try/catch so a malformed/hostile DLL can only ever fail
// the import cleanly — it can never throw an uncaught C++ exception across
// the JNI boundary.
int extract_dll_to_spirv_impl(const std::string &dllPath, const std::string &cacheDir) {
    if (!is_lossless_3220(dllPath)) {
        LOGE("Rejected Lossless.dll: exact ProductVersion 3.2.2.0 is required (%s)", dllPath.c_str());
        return kErrDllUnreadable;
    }
    peparse::parsed_pe *rawDll = peparse::ParsePEFromFile(dllPath.c_str());
    if (!rawDll) {
        LOGE("ParsePEFromFile failed for %s", dllPath.c_str());
        return kErrDllUnreadable;
    }
    // RAII guard: guarantees DestructParsedPE runs on every exit path,
    // including an exception thrown out of IterRsrc/on_resource further
    // down. Without this, an exception here would leak the parsed_pe
    // structure — before extract_dll_to_spirv() gained its own try/catch
    // that leak didn't matter because the whole process would abort anyway,
    // but now that such an exception is caught and converted into a normal
    // error return, cleanup has to happen unconditionally.
    struct ParsedPeGuard {
        peparse::parsed_pe *p;
        ~ParsedPeGuard() { if (p) peparse::DestructParsedPE(p); }
    } dllGuard{rawDll};
    peparse::parsed_pe *dll = rawDll;

    std::unordered_map<uint32_t, std::vector<uint8_t>> blobsByResId;
    ExtractionCtx ctx{&blobsByResId};
    peparse::IterRsrc(dll, on_resource, &ctx);
    // dllGuard destructs `dll` automatically when this function returns (or
    // throws) — no manual DestructParsedPE call here, since we don't need
    // `dll` again below and an explicit call here would double-free against
    // the guard's destructor.

    // kResourceIds are the base (DXBC-era) resource ids — no longer read
    // directly, but still needed as the anchor every FP16 (+49) and FP32
    // (+98) offset is computed from.
    LOGI("Parsed %s, extracting FP16/FP32 SPIR-V", dllPath.c_str());

    // FP16 SPIR-V variants: precompiled in the DLL at DXBC_id + 49. Cache them
    // verbatim into <cacheDir>/fp16/. This is best-effort — a Lossless.dll
    // build that doesn't ship the FP16 set must still produce a working FP32
    // cache. Validation: the blob must start with the SPIR-V LE magic.
    const std::string fp16Dir = cacheDir + "/fp16";
    bool fp16DirOk = true;
    if (mkdir(fp16Dir.c_str(), 0700) != 0 && errno != EEXIST) {
        LOGE("Failed to create FP16 cache dir %s (errno=%d) — FP16 path disabled", fp16Dir.c_str(), errno);
        fp16DirOk = false;
    }
    int fp16Cached = 0;
    int fp16Skipped = 0;
    for (uint32_t dxbcId : kResourceIds) {
        if (!fp16DirOk) break;
        const uint32_t fp16Id = dxbcId + kFp16IdOffset;
        const auto it = blobsByResId.find(fp16Id);
        if (it == blobsByResId.end()) {
            ++fp16Skipped;
            continue;
        }
        // Copy out so we can rewrite Binding decorations to the dense layout
        // the framegen library expects. The FP16 SPIR-V blobs ship with HLSL
        // register slots (t16/s32/u48/b0...) which must be flattened to
        // 0,1,2,... in declaration order — same transformation the DXBC path
        // performs via DXVK's code-stream rewriter.
        std::vector<uint8_t> blob = it->second;
        if (blob.size() < kSpirvMagic.size() ||
            !std::equal(kSpirvMagic.begin(), kSpirvMagic.end(), blob.begin())) {
            ++fp16Skipped;
            continue;
        }
        if (!normalize_spirv_blob(blob)) {
            LOGE("FP16 SPIR-V normalization failed for resource %u — skipping", fp16Id);
            ++fp16Skipped;
            continue;
        }
        char path[512];
        std::snprintf(path, sizeof(path), "%s/%u.spv", fp16Dir.c_str(), fp16Id);
        if (!write_file(path, blob)) {
            LOGE("Failed to write FP16 cache %s — FP16 path may be incomplete", path);
            ++fp16Skipped;
            continue;
        }
        ++fp16Cached;
    }
    LOGI("FP16 SPIR-V variants: %d cached, %d skipped (%s)", fp16Cached, fp16Skipped, fp16Dir.c_str());

    // FP32 SPIR-V variants: precompiled in the DLL at DXBC_id + 98 (range
    // 353..400). Same shape as the FP16 cache step above, including the
    // dense-binding rewrite — these blobs ship with HLSL register slots like
    // the FP16 set, so they need the same flatten before the framegen
    // descriptor-set layout will accept them.
    const std::string fp32Dir = cacheDir + "/fp32";
    bool fp32DirOk = true;
    if (mkdir(fp32Dir.c_str(), 0700) != 0 && errno != EEXIST) {
        LOGE("Failed to create FP32 SPIR-V cache dir %s (errno=%d) — FP32 SPIR-V path disabled",
             fp32Dir.c_str(), errno);
        fp32DirOk = false;
    }
    int fp32SpvCached = 0;
    int fp32SpvSkipped = 0;
    for (uint32_t dxbcId : kResourceIds) {
        if (!fp32DirOk) break;
        const uint32_t fp32Id = dxbcId + kFp32SpirvIdOffset;
        const auto it = blobsByResId.find(fp32Id);
        if (it == blobsByResId.end()) {
            ++fp32SpvSkipped;
            continue;
        }
        std::vector<uint8_t> blob = it->second;
        if (blob.size() < kSpirvMagic.size() ||
            !std::equal(kSpirvMagic.begin(), kSpirvMagic.end(), blob.begin())) {
            ++fp32SpvSkipped;
            continue;
        }
        if (!normalize_spirv_blob(blob)) {
            LOGE("FP32 SPIR-V normalization failed for resource %u — skipping", fp32Id);
            ++fp32SpvSkipped;
            continue;
        }
        char path[512];
        std::snprintf(path, sizeof(path), "%s/%u.spv", fp32Dir.c_str(), fp32Id);
        if (!write_file(path, blob)) {
            LOGE("Failed to write FP32 SPIR-V cache %s — FP32 SPIR-V path may be incomplete", path);
            ++fp32SpvSkipped;
            continue;
        }
        ++fp32SpvCached;
    }
    LOGI("FP32 SPIR-V variants: %d cached, %d skipped (%s)",
         fp32SpvCached, fp32SpvSkipped, fp32Dir.c_str());

    const bool fp16Complete = fp16DirOk && fp16Skipped == 0;
    const bool fp32Complete = fp32DirOk && fp32SpvSkipped == 0;
    if (!fp16Complete && !fp32Complete) {
        LOGE("Neither FP16 nor FP32 SPIR-V set is complete in %s — "
             "is Lossless Scaling up to date? (requires exactly 3.2.2.0)", dllPath.c_str());
        return kErrMissingResource;
    }
    return kOk;
}

int extract_dll_to_spirv(const std::string &dllPath, const std::string &cacheDir) {
    try {
        return extract_dll_to_spirv_impl(dllPath, cacheDir);
    } catch (const std::exception &e) {
        LOGE("Exception while parsing %s: %s — treating as unreadable/corrupt DLL",
             dllPath.c_str(), e.what());
        return kErrParseException;
    } catch (...) {
        LOGE("Unknown exception while parsing %s — treating as unreadable/corrupt DLL",
             dllPath.c_str());
        return kErrParseException;
    }
}

std::vector<uint8_t> load_cached_spirv(const std::string &cacheDir, uint32_t resId,
                                       ShaderCache source) {
    char path[512];
    switch (source) {
        case ShaderCache::Fp16Spirv:
            std::snprintf(path, sizeof(path), "%s/fp16/%u.spv", cacheDir.c_str(), resId);
            break;
        case ShaderCache::Fp32Spirv:
        default:
            std::snprintf(path, sizeof(path), "%s/fp32/%u.spv", cacheDir.c_str(), resId);
            break;
    }
    std::ifstream f(path, std::ios::binary | std::ios::ate);
    if (!f) {
        return {};
    }
    const auto size = f.tellg();
    f.seekg(0, std::ios::beg);
    std::vector<uint8_t> out(static_cast<size_t>(size));
    f.read(reinterpret_cast<char *>(out.data()), static_cast<std::streamsize>(size));
    if (!normalize_spirv_blob(out)) {
        LOGE("Cached SPIR-V resource %u failed runtime normalization (%s) — skipping",
             resId, path);
        return {};
    }
    return out;
}

// Mirror of Extract::nameIdxTable in lsfg-vk-android/src/extract/extract.cpp
// (Steam Deck side). Framegen asks for shaders by symbolic name; on Android
// we cache them on disk by numeric resource ID, so we need to translate.
uint32_t shader_name_to_resource_id(const std::string &name) {
    static const std::unordered_map<std::string, uint32_t> kTable = {
        { "mipmaps",     255 },
        { "alpha[0]",    267 },
        { "alpha[1]",    268 },
        { "alpha[2]",    269 },
        { "alpha[3]",    270 },
        { "beta[0]",     275 },
        { "beta[1]",     276 },
        { "beta[2]",     277 },
        { "beta[3]",     278 },
        { "beta[4]",     279 },
        { "gamma[0]",    257 },
        { "gamma[1]",    259 },
        { "gamma[2]",    260 },
        { "gamma[3]",    261 },
        { "gamma[4]",    262 },
        { "delta[0]",    257 },
        { "delta[1]",    263 },
        { "delta[2]",    264 },
        { "delta[3]",    265 },
        { "delta[4]",    266 },
        { "delta[5]",    258 },
        { "delta[6]",    271 },
        { "delta[7]",    272 },
        { "delta[8]",    273 },
        { "delta[9]",    274 },
        { "generate",    256 },
        { "p_mipmaps",   255 },
        { "p_alpha[0]",  290 },
        { "p_alpha[1]",  291 },
        { "p_alpha[2]",  292 },
        { "p_alpha[3]",  293 },
        { "p_beta[0]",   298 },
        { "p_beta[1]",   299 },
        { "p_beta[2]",   300 },
        { "p_beta[3]",   301 },
        { "p_beta[4]",   302 },
        { "p_gamma[0]",  280 },
        { "p_gamma[1]",  282 },
        { "p_gamma[2]",  283 },
        { "p_gamma[3]",  284 },
        { "p_gamma[4]",  285 },
        { "p_delta[0]",  280 },
        { "p_delta[1]",  286 },
        { "p_delta[2]",  287 },
        { "p_delta[3]",  288 },
        { "p_delta[4]",  289 },
        { "p_delta[5]",  281 },
        { "p_delta[6]",  294 },
        { "p_delta[7]",  295 },
        { "p_delta[8]",  296 },
        { "p_delta[9]",  297 },
        { "p_generate",  256 },
    };
    auto it = kTable.find(name);
    return it == kTable.end() ? 0u : it->second;
}

uint32_t shader_name_to_resource_id_fp16(const std::string &name) {
    const uint32_t dxbcId = shader_name_to_resource_id(name);
    if (dxbcId == 0) {
        return 0;
    }
    const uint32_t fp16Id = dxbcId + kFp16IdOffset;
    // Guard against accidentally walking off the FP16 SPIR-V range. Anything
    // outside 304..351 means the DXBC id is out of the LSFG framegen set
    // (shouldn't happen because shader_name_to_resource_id only returns
    // 255..302), or the DLL layout has changed in a future release.
    if (fp16Id < 304 || fp16Id > 351) {
        return 0;
    }
    return fp16Id;
}

bool fp16_shaders_available(const std::string &cacheDir) {
    // Quick existence check — the loader callback handles missing-file fallback
    // per-shader, but we want a single boolean for the UI capability gate
    // (so the FP16 toggle can grey out when the DLL didn't ship the FP16 set
    // or when extraction skipped them).
    for (uint32_t dxbcId : kResourceIds) {
        const uint32_t fp16Id = dxbcId + kFp16IdOffset;
        if (fp16Id < 304 || fp16Id > 351) continue;
        char path[512];
        std::snprintf(path, sizeof(path), "%s/fp16/%u.spv", cacheDir.c_str(), fp16Id);
        struct stat st{};
        if (stat(path, &st) != 0 || st.st_size < 20) {
            return false;
        }
    }
    return true;
}

uint32_t shader_name_to_resource_id_fp32_spirv(const std::string &name) {
    const uint32_t dxbcId = shader_name_to_resource_id(name);
    if (dxbcId == 0) {
        return 0;
    }
    const uint32_t fp32Id = dxbcId + kFp32SpirvIdOffset;
    if (fp32Id < 353 || fp32Id > 400) {
        return 0;
    }
    return fp32Id;
}

bool fp32_spirv_shaders_available(const std::string &cacheDir) {
    for (uint32_t dxbcId : kResourceIds) {
        const uint32_t fp32Id = dxbcId + kFp32SpirvIdOffset;
        if (fp32Id < 353 || fp32Id > 400) continue;
        char path[512];
        std::snprintf(path, sizeof(path), "%s/fp32/%u.spv", cacheDir.c_str(), fp32Id);
        struct stat st{};
        if (stat(path, &st) != 0 || st.st_size < 20) {
            return false;
        }
    }
    return true;
}

} // namespace lsfg_android
