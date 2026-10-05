#include <volk.h>
#include <vulkan/vulkan_core.h>

#include "lsfg_3_1p.hpp"
#include "v3_1p/context.hpp"
#include "core/commandpool.hpp"
#include "core/descriptorpool.hpp"
#include "core/instance.hpp"
#include "pool/shaderpool.hpp"
#include "common/exception.hpp"
#include "common/utils.hpp"

#include <cstdint>
#include <optional>
#include <cstdlib>
#include <ctime>
#include <functional>
#include <string>
#include <unordered_map>
#include <vector>

using namespace LSFG;
using namespace LSFG_3_1P;

namespace {
    std::optional<Core::Instance> instance;
    std::optional<Vulkan> device;
    std::unordered_map<int32_t, Context> contexts;
#ifdef __ANDROID__
    // Contexts presented since the last waitIdle() call. Lets waitIdle()
    // wait only on the specific submissions that were made, instead of
    // vkDeviceWaitIdle() which drains every queue on the device.
    std::vector<int32_t> pendingPresents;
#endif
}

void LSFG_3_1P::initialize(uint64_t deviceUUID,
        bool isHdr, float flowScale, uint64_t generationCount,
        const std::function<std::vector<uint8_t>(const std::string&)>& loader) {
    if (instance.has_value() || device.has_value())
        return;

    instance.emplace();
    device.emplace(Vulkan {
        .device{*instance, deviceUUID},
        .generationCount = generationCount,
        .flowScale = flowScale,
        .isHdr = isHdr
    });
    contexts = std::unordered_map<int32_t, Context>();

    device->commandPool = Core::CommandPool(device->device);
    device->descriptorPool = Core::DescriptorPool(device->device);

    device->resources = Pool::ResourcePool(device->isHdr, device->flowScale);
    device->shaders = Pool::ShaderPool(loader);

    std::srand(static_cast<uint32_t>(std::time(nullptr)));
}

int32_t LSFG_3_1P::createContext(
        int in0, int in1, const std::vector<int>& outN,
        VkExtent2D extent, VkFormat format) {
    if (!instance.has_value() || !device.has_value())
        throw LSFG::vulkan_error(VK_ERROR_INITIALIZATION_FAILED, "LSFG not initialized");

    const int32_t id = std::rand();
    contexts.emplace(id, Context(*device, in0, in1, outN, extent, format));
    return id;
}

void LSFG_3_1P::setExternalSemaphoreFdHandleType(VkExternalSemaphoreHandleTypeFlagBits type) {
    Core::Semaphore::setExternalFdHandleType(type);
}

void LSFG_3_1P::presentContext(int32_t id, int inSem, const std::vector<int>& outSem) {
    if (!instance.has_value() || !device.has_value())
        throw LSFG::vulkan_error(VK_ERROR_INITIALIZATION_FAILED, "LSFG not initialized");

    auto it = contexts.find(id);
    if (it == contexts.end())
        throw LSFG::vulkan_error(VK_ERROR_UNKNOWN, "Context not found");

    it->second.present(*device, inSem, outSem);
#ifdef __ANDROID__
    pendingPresents.push_back(id);
#endif
}

void LSFG_3_1P::presentContextAHB(int32_t id, AHardwareBuffer* current, AHardwareBuffer* previous,
        int inSem, const std::vector<int>& outSem) {
    if (!instance.has_value() || !device.has_value())
        throw LSFG::vulkan_error(VK_ERROR_INITIALIZATION_FAILED, "LSFG not initialized");
    auto it = contexts.find(id);
    if (it == contexts.end())
        throw LSFG::vulkan_error(VK_ERROR_UNKNOWN, "Context not found");
    it->second.presentAHB(*device, current, previous, inSem, outSem);
#ifdef __ANDROID__
    pendingPresents.push_back(id);
#endif
}

void LSFG_3_1P::deleteContext(int32_t id) {
    if (!instance.has_value() || !device.has_value())
        throw LSFG::vulkan_error(VK_ERROR_INITIALIZATION_FAILED, "LSFG not initialized");

    auto it = contexts.find(id);
    if (it == contexts.end())
        throw LSFG::vulkan_error(VK_ERROR_DEVICE_LOST, "No such context");

    vkDeviceWaitIdle(device->device.handle());
    contexts.erase(it);
#ifdef __ANDROID__
    // The device is fully idle now (line above), so any fences waitIdle()
    // would have waited on are already signaled — and the deleted context's
    // own fences are gone. Drop everything rather than leave a dangling id.
    pendingPresents.clear();
#endif
}

void LSFG_3_1P::finalize() {
    if (!instance.has_value() || !device.has_value())
        return;

    vkDeviceWaitIdle(device->device.handle());
    contexts.clear();
    device.reset();
    instance.reset();
}

#ifdef __ANDROID__

#include <android/hardware_buffer.h>

int32_t LSFG_3_1P::createContextFromAHB(
        AHardwareBuffer* in0, AHardwareBuffer* in1,
        const std::vector<AHardwareBuffer*>& outN,
        VkExtent2D extent, VkFormat format) {
    if (!instance.has_value() || !device.has_value())
        throw LSFG::vulkan_error(VK_ERROR_INITIALIZATION_FAILED, "LSFG not initialized");

    const int32_t id = std::rand();
    contexts.emplace(id, Context(*device, in0, in1, outN, extent, format));
    return id;
}

#endif // __ANDROID__

#ifdef __ANDROID__
void LSFG_3_1P::waitIdle() {
    if (!device.has_value()) return;

    // Wait only on the completion fences of the specific present() calls
    // made since the last waitIdle() — not vkDeviceWaitIdle(), which drains
    // every queue and every context on the device and was the single
    // biggest per-frame cost in profiling. In the common case (one context
    // presented once per frame) this is a single vkWaitForFences on that
    // context's own fences.
    for (int32_t id : pendingPresents) {
        auto it = contexts.find(id);
        if (it != contexts.end())
            it->second.waitForCompletion(*device);
    }
    pendingPresents.clear();
}
#endif
