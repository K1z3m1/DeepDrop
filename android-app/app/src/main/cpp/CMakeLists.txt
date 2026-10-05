cmake_minimum_required(VERSION 3.22.1)
project(lsfg-android LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# Reach into the Linux project tree for sources we want to reuse as-is.
set(LSFG_ROOT "${CMAKE_SOURCE_DIR}/../../../../../framegen-native-lib")
if(NOT EXISTS "${LSFG_ROOT}/framegen/CMakeLists.txt")
    message(FATAL_ERROR "Can't find framegen-native-lib sources at ${LSFG_ROOT}. Did you clone the submodules?")
endif()

# -----------------------------------------------------------------------------
# volk — Vulkan loader. On Android we enable the Android surface platform.
# We avoid add_subdirectory() because volk's default install/include rules get
# in the way; compiling the two-file library directly is trivial.
# -----------------------------------------------------------------------------
add_library(volk STATIC "${LSFG_ROOT}/thirdparty/vulkan-loader/volk.c")
target_include_directories(volk PUBLIC "${LSFG_ROOT}/thirdparty/vulkan-loader")
target_compile_definitions(volk PUBLIC VK_USE_PLATFORM_ANDROID_KHR=1)

# -----------------------------------------------------------------------------
# pe-parse — PE/COFF parser. We skip the upstream CMakeLists (it requires ICU)
# and build the minimal translation units ourselves, substituting our own
# UTF-16 → UTF-8 helper from unicode_minimal.cpp.
# -----------------------------------------------------------------------------
set(PEPARSE_DIR "${LSFG_ROOT}/thirdparty/pe-dll-parser/pe-parser-library")
add_library(pe-parse STATIC
    "${PEPARSE_DIR}/src/buffer.cpp"
    "${PEPARSE_DIR}/src/parse.cpp"
    "${CMAKE_CURRENT_SOURCE_DIR}/unicode_minimal.cpp"
)
target_include_directories(pe-parse PUBLIC "${PEPARSE_DIR}/include")
target_compile_features(pe-parse PUBLIC cxx_std_17)

# -----------------------------------------------------------------------------
# framegen — the Vulkan frame-generation pipeline. Reused verbatim.
#
# Tell Gradle's externalNativeBuild task that this project's "input set"
# extends into the submodule sources. Without this, AGP's incremental build
# only watches files under app/src/main/cpp/ and silently skips re-running
# CMake when files in ../../../../../framegen-native-lib/framegen/src change —
# leaving the cached lsfg-vk-framegen.a in place even after a real source
# edit. CMAKE_CONFIGURE_DEPENDS makes the configure step depend on every
# .cpp/.hpp under the framegen tree, so any edit there triggers a full
# regeneration on the next build.
# -----------------------------------------------------------------------------
file(GLOB_RECURSE FRAMEGEN_WATCH_SOURCES CONFIGURE_DEPENDS
    "${LSFG_ROOT}/framegen/src/*.cpp"
    "${LSFG_ROOT}/framegen/src/*.hpp"
    "${LSFG_ROOT}/framegen/include/*.hpp"
    "${LSFG_ROOT}/framegen/public/*.hpp"
    "${LSFG_ROOT}/framegen/v3.1_src/*.cpp"
    "${LSFG_ROOT}/framegen/v3.1p_src/*.cpp"
)
set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS
    ${FRAMEGEN_WATCH_SOURCES}
)
add_subdirectory("${LSFG_ROOT}/framegen" "${CMAKE_BINARY_DIR}/framegen" EXCLUDE_FROM_ALL)

# -----------------------------------------------------------------------------
# ncnn — optional AI (FlowNetLite/RefineNetLite) frame-gen backend.
#
# We can't vendor this ourselves (it's a large prebuilt binary SDK, not
# source we can generate). Download the official Android Vulkan build from
# https://github.com/Tencent/ncnn/releases (asset named
# ncnn-<date>-android-vulkan.zip), unzip it, and lay it out as:
#
#   ${LSFG_ROOT}/thirdparty/ncnn-android-vulkan/<ABI>/include/ncnn/net.h
#   ${LSFG_ROOT}/thirdparty/ncnn-android-vulkan/<ABI>/lib/libncnn.a
#
# (the release zip already has this arm64-v8a/armeabi-v7a/... layout — just
# copy the whole extracted folder in and rename it to ncnn-android-vulkan).
#
# When present, NcnnInterpolator.cpp is compiled in and
# NativeBridge.{init,release}AiInterpolator / aiInterpolate become real.
# When absent, the build still succeeds; those JNI calls return
# lsfg_android::kNcnnErrNotBuilt instead of doing anything, and the AI
# backend option in Settings surfaces that as a clear error rather than a
# silent no-op or a broken build.
# -----------------------------------------------------------------------------
set(NCNN_ROOT "${LSFG_ROOT}/thirdparty/ncnn-android-vulkan/${ANDROID_ABI}")
if(EXISTS "${NCNN_ROOT}/include/ncnn/net.h" AND EXISTS "${NCNN_ROOT}/lib/libncnn.a")
    set(LSFG_HAVE_NCNN TRUE)
    # Don't hand-declare the imported target: this prebuilt was built with
    # NCNN_OPENMP=ON and NCNN_VULKAN=ON (online SPIR-V compile via glslang),
    # so the real ncnn target carries transitive link requirements
    # (-fopenmp -static-openmp, Threads::Threads, glslang::glslang,
    # glslang::SPIRV, dl, jnigraphics) that a bare IMPORTED_LOCATION does
    # not pull in — that's what was producing the __kmpc_*/omp_* and
    # glslang::* undefined symbol errors at link time. find_package reads
    # ncnn's own generated config and wires all of it up automatically.
    #
    # The NDK toolchain file sets CMAKE_FIND_ROOT_PATH_MODE_PACKAGE=ONLY,
    # which makes find_package() search only inside the NDK sysroot and
    # silently ignore PATHS pointing anywhere else (like our vendored SDK
    # under thirdparty/). ncnnConfig.cmake also does its own nested
    # find_package(glslang ...) internally, which we can't pass
    # NO_CMAKE_FIND_ROOT_PATH to directly — so widen the search mode for
    # the whole block instead of just this one call, then restore it so
    # it doesn't loosen root-path checks for the rest of the build.
    set(_lsfg_saved_find_root_path_mode_package "${CMAKE_FIND_ROOT_PATH_MODE_PACKAGE}")
    set(CMAKE_FIND_ROOT_PATH_MODE_PACKAGE BOTH)
    find_package(ncnn CONFIG REQUIRED
        PATHS "${NCNN_ROOT}/lib/cmake/ncnn"
        NO_DEFAULT_PATH
    )
    set(CMAKE_FIND_ROOT_PATH_MODE_PACKAGE "${_lsfg_saved_find_root_path_mode_package}")
    unset(_lsfg_saved_find_root_path_mode_package)
    message(STATUS "lsfg-android: ncnn found at ${NCNN_ROOT} — AI (ncnn) frame-gen backend ENABLED.")
else()
    set(LSFG_HAVE_NCNN FALSE)
    message(WARNING
        "lsfg-android: ncnn not found at ${NCNN_ROOT} — AI (ncnn) frame-gen backend DISABLED for this build.\n"
        "  To enable it: download the Vulkan Android build from\n"
        "  https://github.com/Tencent/ncnn/releases (ncnn-<date>-android-vulkan.zip),\n"
        "  unzip it, and copy it so that\n"
        "    ${NCNN_ROOT}/include/ncnn/net.h\n"
        "    ${NCNN_ROOT}/lib/libncnn.a\n"
        "  exist, then re-run the build.")
endif()

# -----------------------------------------------------------------------------
# glslang is required by the Vulkan image-enhancement compute pass even when
# the optional ncnn AI backend is not built. Keep this dependency independent
# from LSFG_HAVE_NCNN so GPU image enhancement is available for the normal
# LSFG frame-generation path as well.
# -----------------------------------------------------------------------------
set(LSFG_GLSLANG_ROOT "${LSFG_ROOT}/thirdparty/ncnn-android-vulkan/${ANDROID_ABI}")
if(EXISTS "${LSFG_GLSLANG_ROOT}/lib/cmake/glslang/glslang-config.cmake")
    set(_lsfg_saved_find_root_path_package "${CMAKE_FIND_ROOT_PATH_MODE_PACKAGE}")
    set(CMAKE_FIND_ROOT_PATH_MODE_PACKAGE BOTH)
    find_package(glslang CONFIG REQUIRED
        PATHS "${LSFG_GLSLANG_ROOT}/lib/cmake/glslang"
        NO_DEFAULT_PATH
    )
    set(CMAKE_FIND_ROOT_PATH_MODE_PACKAGE "${_lsfg_saved_find_root_path_package}")
    unset(_lsfg_saved_find_root_path_package)
    set(LSFG_HAVE_GLSLANG TRUE)
else()
    set(LSFG_HAVE_GLSLANG FALSE)
    message(FATAL_ERROR
        "lsfg-android: Vulkan image enhancement requires glslang for ABI ${ANDROID_ABI}, "
        "but ${LSFG_GLSLANG_ROOT}/lib/cmake/glslang/glslang-config.cmake was not found.")
endif()

# -----------------------------------------------------------------------------
# NcnnInterpolator.cpp gets its own static library instead of being compiled
# straight into lsfg-android. ncnn's imported target (from find_package above)
# carries INTERFACE_COMPILE_OPTIONS of -fno-rtti/-fno-exceptions — and those
# usage requirements apply to a *direct* consumer's own compilation even when
# linked PRIVATE (PRIVATE only stops them propagating further downstream).
# Linking ncnn straight into lsfg-android was therefore silently disabling
# exceptions for every other .cpp in the library too, breaking the real
# try/catch error handling around LSFG_3_1P::initialize/createContextFromAHB
# in lsfg_render_loop.cpp ("cannot use 'try' with exceptions disabled").
# Routing it through this static lib keeps -fno-exceptions/-fno-rtti scoped
# to NcnnInterpolator.cpp alone. libncnn.a (and the glslang/OpenMP libs ncnn's
# target pulls in) still reach the final .so's link line regardless — CMake
# forwards a static library's PRIVATE link dependencies to whatever ultimately
# links it, even though it doesn't forward compile-time usage requirements
# the same way. Safe to split out: NcnnInterpolator.hpp uses a pImpl (struct
# Impl*), so no ncnn types leak into callers like lsfg_jni.cpp that just
# include the header.
if(LSFG_HAVE_NCNN)
    add_library(lsfg-ncnn-interpolator STATIC
        ${CMAKE_CURRENT_SOURCE_DIR}/NcnnInterpolator.cpp
        ${CMAKE_CURRENT_SOURCE_DIR}/RifeWarp.cpp
        # IFRNet: second selectable ncnn AI backend, same static lib as RIFE
        # since both need the same -fno-rtti/-fno-exceptions isolation from
        # ncnn's imported target (see comment block above). IfrnetInterpolator
        # only includes NcnnInterpolator.hpp for the shared kNcnnErr*
        # constants (header-only, no ODR risk from being compiled into the
        # same TU set), and IfrnetWarp is a distinct custom layer from
        # RifeWarp — see IfrnetWarp.hpp for why they aren't interchangeable.
        ${CMAKE_CURRENT_SOURCE_DIR}/IfrnetInterpolator.cpp
        ${CMAKE_CURRENT_SOURCE_DIR}/IfrnetWarp.cpp
        ${CMAKE_CURRENT_SOURCE_DIR}/ncnn_cpu_policy.cpp
    )
    target_link_libraries(lsfg-ncnn-interpolator PRIVATE ncnn)
    target_compile_definitions(lsfg-ncnn-interpolator PRIVATE LSFG_HAVE_NCNN=1)
endif()

# -----------------------------------------------------------------------------
# lsfg-android — JNI shared library.
# Phase 2 only exposes nativeVersion() plus placeholder symbols; later phases
# add the real shader extraction and Vulkan context entry points.
# -----------------------------------------------------------------------------
find_library(log-lib log)

add_library(lsfg-android SHARED
    lsfg_jni.cpp
    android_shader_loader.cpp
    android_vk_probe.cpp
    android_vk_session.cpp
    ahb_image_bridge.cpp
    lsfg_render_loop.cpp
    crash_reporter.cpp
    gpu_image_enhancement.cpp
)

target_compile_definitions(lsfg-android PRIVATE
    $<$<BOOL:${LSFG_HAVE_NCNN}>:LSFG_HAVE_NCNN=1>
)

target_link_libraries(lsfg-android
    PRIVATE
        lsfg-vk-framegen
        pe-parse
        volk
        ${log-lib}
        android
        jnigraphics
        $<$<BOOL:${LSFG_HAVE_NCNN}>:lsfg-ncnn-interpolator>
        glslang::glslang
        glslang::SPIRV
        glslang::MachineIndependent
        glslang::GenericCodeGen
        glslang::OSDependent
        glslang::glslang-default-resource-limits
)
