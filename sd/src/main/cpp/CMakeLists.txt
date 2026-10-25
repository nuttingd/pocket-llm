cmake_minimum_required(VERSION 3.22.1)
project("pocketsd" LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED true)

set(SD_SRC ${CMAKE_CURRENT_LIST_DIR}/../../../../external/stable-diffusion.cpp)

# Build the library only: no examples, no server, no CLI.
set(SD_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(SD_BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)
set(SD_VULKAN ON CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP ON CACHE BOOL "" FORCE)

# Cross-compiling Vulkan shaders needs host tools: glslc, the C++ Vulkan headers and SPIRV-Headers.
if(DEFINED ANDROID_ABI)
    find_program(_vulkan_glslc glslc
            PATHS /opt/homebrew/bin /usr/local/bin /usr/bin
            NO_CMAKE_FIND_ROOT_PATH)
    if(_vulkan_glslc)
        set(Vulkan_GLSLC_EXECUTABLE "${_vulkan_glslc}" CACHE FILEPATH "" FORCE)
    endif()

    find_path(_vulkan_include vulkan/vulkan.hpp
            PATHS /opt/homebrew/include /opt/vulkan-headers /usr/local/include
            NO_CMAKE_FIND_ROOT_PATH)
    if(_vulkan_include)
        set(Vulkan_INCLUDE_DIR "${_vulkan_include}" CACHE PATH "" FORCE)
        set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -isystem ${_vulkan_include}" CACHE STRING "" FORCE)
        set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -isystem ${_vulkan_include}" CACHE STRING "" FORCE)
    endif()

    if(NOT SPIRV-Headers_DIR)
        find_path(_spirv_cfg SPIRV-HeadersConfig.cmake
                PATHS /opt/homebrew /usr/local /usr
                PATH_SUFFIXES share/cmake/SPIRV-Headers lib/cmake/SPIRV-Headers
                NO_DEFAULT_PATH NO_CMAKE_FIND_ROOT_PATH)
        if(_spirv_cfg)
            set(SPIRV-Headers_DIR "${_spirv_cfg}" CACHE PATH "" FORCE)
        endif()
    endif()
endif()

add_subdirectory(${SD_SRC} ${CMAKE_CURRENT_BINARY_DIR}/sd_cpp)

add_library(pocketsd SHARED sd_jni.cpp)
target_include_directories(pocketsd PRIVATE ${SD_SRC}/include)

# Hide everything except the JNI entry points so this .so cannot clash with libpocketllm.so.
set_target_properties(pocketsd PROPERTIES CXX_VISIBILITY_PRESET hidden VISIBILITY_INLINES_HIDDEN ON)
target_compile_options(pocketsd PRIVATE -fvisibility=hidden)

target_link_libraries(pocketsd PRIVATE stable-diffusion android log)
