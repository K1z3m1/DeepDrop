cmake_minimum_required(VERSION 3.22)
project(fluxa C CXX)
set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ---- release build: optimise for speed/size, drop unused code, hide non-JNI symbols ----
# (JNI entry points are JNIEXPORT = default visibility, so they stay exported)
if(NOT CMAKE_BUILD_TYPE STREQUAL "Debug")
    add_compile_options(-O2 -fvisibility=hidden -ffunction-sections -fdata-sections)
    add_link_options(-Wl,--gc-sections -Wl,--exclude-libs,ALL -Wl,--build-id=none)
endif()

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
