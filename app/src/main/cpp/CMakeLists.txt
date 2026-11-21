cmake_minimum_required(VERSION 3.22)
project(fluxa C CXX)
set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Lean native build: size/speed optimised, dead code and symbols dropped, LTO across the C sources.
set(CMAKE_C_VISIBILITY_PRESET hidden)
set(CMAKE_VISIBILITY_INLINES_HIDDEN ON)
set(CMAKE_INTERPROCEDURAL_OPTIMIZATION_RELEASE ON)
set(FX_OPT_FLAGS "-O2 -ffunction-sections -fdata-sections -fno-unwind-tables -fno-asynchronous-unwind-tables -fomit-frame-pointer")

# Memory-safety / exploit-mitigation hardening (all build types):
#  - stack protector on every function with a local buffer or address-taken local
#  - FORTIFY_SOURCE: bounds-checked memcpy/strcpy/etc. where the size is known at compile time
#  - stack-clash protection, and zero-initialised automatic variables (no stale-stack info leaks)
#  - branch protection (PAC/BTI on arm64, CET on x86_64) so ROP/JOP gadgets are harder to chain
#  - linker: full RELRO + immediate binding, non-executable stack, no text relocations
set(FX_HARDEN_FLAGS "-fstack-protector-strong -U_FORTIFY_SOURCE -D_FORTIFY_SOURCE=2 -fstack-clash-protection -ftrivial-auto-var-init=zero -fno-delete-null-pointer-checks -fPIC -Wformat -Wformat-security -Werror=format-security")
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(FX_HARDEN_FLAGS "${FX_HARDEN_FLAGS} -mbranch-protection=standard")
elseif(ANDROID_ABI STREQUAL "x86_64")
    set(FX_HARDEN_FLAGS "${FX_HARDEN_FLAGS} -fcf-protection=full")
endif()
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} ${FX_HARDEN_FLAGS}")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} ${FX_HARDEN_FLAGS}")
set(FX_HARDEN_LINK "-Wl,-z,relro -Wl,-z,now -Wl,-z,noexecstack")
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} ${FX_HARDEN_LINK}")

set(CMAKE_C_FLAGS_RELEASE "${FX_OPT_FLAGS} -DNDEBUG")
set(CMAKE_SHARED_LINKER_FLAGS_RELEASE "-Wl,--gc-sections -Wl,--icf=safe -Wl,--exclude-libs,ALL -Wl,-O2 -Wl,--as-needed")

find_program(GLSLC glslc REQUIRED HINTS
    "${CMAKE_ANDROID_NDK}/shader-tools/linux-x86_64"
    "${CMAKE_ANDROID_NDK}/shader-tools/darwin-x86_64"
    "${CMAKE_ANDROID_NDK}/shader-tools/windows-x86_64")
set(BIN2C_SCRIPT "${CMAKE_CURRENT_SOURCE_DIR}/bin2c.cmake")

# ---- DIS (Dense Inverse Search) engine: GLSL -> SPIR-V headers -> static lib ----
set(DIS_DIR "${CMAKE_CURRENT_SOURCE_DIR}/dis")
set(DIS_OUT "${CMAKE_CURRENT_BINARY_DIR}/dis_shaders")
file(MAKE_DIRECTORY "${DIS_OUT}/shaders")
set(DIS_SHADERS
    dis_luma_r16 dis_luma_r32 dis_gradient dis_inverse_search dis_propagate dis_densify
    dis_interpolate dis_hist dis_side dis_flow_pack dis_vr_prep dis_vr_d1 dis_vr_d2 dis_vr_w
    dis_vr_coef dis_vr_sor dis_vr_add dis_me_luma)
set(DIS_HEADERS "")
foreach(base ${DIS_SHADERS})
    set(var "${base}_comp")
    set(spv "${DIS_OUT}/shaders/${var}.spv")
    set(hdr "${DIS_OUT}/shaders/${var}.spv.h")
    add_custom_command(
        OUTPUT "${hdr}"
        COMMAND "${GLSLC}" --target-env=vulkan1.1 -O "${DIS_DIR}/include/shaders/${base}.comp" -o "${spv}"
        COMMAND "${CMAKE_COMMAND}" -DINPUT_FILE=${spv} -DOUTPUT_FILE=${hdr} -DVAR_NAME=${var} -P "${BIN2C_SCRIPT}"
        DEPENDS "${DIS_DIR}/include/shaders/${base}.comp" "${BIN2C_SCRIPT}"
        VERBATIM)
    list(APPEND DIS_HEADERS "${hdr}")
endforeach()
add_custom_target(dis_shaders DEPENDS ${DIS_HEADERS})

add_library(fluxa_dis STATIC ${DIS_DIR}/src/vkr_dis.c ${DIS_DIR}/src/dis_qcom_me.c)
add_dependencies(fluxa_dis dis_shaders)
set_target_properties(fluxa_dis PROPERTIES POSITION_INDEPENDENT_CODE ON)
target_compile_definitions(fluxa_dis PUBLIC VK_USE_PLATFORM_ANDROID_KHR)
target_include_directories(fluxa_dis
    PUBLIC  ${DIS_DIR}/include ${CMAKE_CURRENT_SOURCE_DIR}/shared
    PRIVATE ${DIS_OUT})
target_link_libraries(fluxa_dis PRIVATE log dl)

# ---- output upscaler shaders (SGSR-style, FSR1 EASU, Catmull-Rom, Lanczos, NIS-style, RCAS, CAS): GLSL -> SPIR-V headers ----
set(UP_DIR "${CMAKE_CURRENT_SOURCE_DIR}/shared/presenter/shaders")
set(UP_OUT "${CMAKE_CURRENT_BINARY_DIR}/up_shaders")
file(MAKE_DIRECTORY "${UP_OUT}")
set(UP_HEADERS "")
foreach(base up_easu up_sgsr up_rcas up_catrom up_lanczos up_nis up_cas up_anime up_mitchell up_lanczos3 up_adaptive up_spline36 up_ewa up_luma up_clarity
             up_denoise up_fxaa up_smaa up_deband)
    set(var "${base}_comp")
    set(spv "${UP_OUT}/${var}.spv")
    set(hdr "${UP_OUT}/${var}.spv.h")
    add_custom_command(
        OUTPUT "${hdr}"
        COMMAND "${GLSLC}" --target-env=vulkan1.1 -O "${UP_DIR}/${base}.comp" -o "${spv}"
        COMMAND "${CMAKE_COMMAND}" -DINPUT_FILE=${spv} -DOUTPUT_FILE=${hdr} -DVAR_NAME=${var} -P "${BIN2C_SCRIPT}"
        DEPENDS "${UP_DIR}/${base}.comp" "${BIN2C_SCRIPT}"
        VERBATIM)
    list(APPEND UP_HEADERS "${hdr}")
endforeach()
add_custom_target(up_shaders DEPENDS ${UP_HEADERS})

# ---- JNI library (FluxaNative loads "fluxa") ----
add_library(fluxa SHARED
    shared/vk_dispatch.c
    shared/presenter/fx_present.c
    shared/presenter/fx_upscale.c
    shared/presenter/fx_still.c
    shared/presenter/fx_jni.c)
add_dependencies(fluxa up_shaders)
target_compile_definitions(fluxa PRIVATE VK_USE_PLATFORM_ANDROID_KHR)
target_include_directories(fluxa PRIVATE shared ${UP_OUT})
target_link_libraries(fluxa PRIVATE fluxa_dis android log mediandk nativewindow dl)
