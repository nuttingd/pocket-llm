cmake_minimum_required(VERSION 3.22.1)

project("pocketllm-llm" VERSION 1.0.0 LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED true)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED true)

# --------------------------------------------------------------------------
# llama.cpp source tree (git submodule)
# --------------------------------------------------------------------------

set(LLAMA_SRC ${CMAKE_CURRENT_LIST_DIR}/../../../../external/llama.cpp)

if(DEFINED ANDROID_ABI)
    message(STATUS "Detected Android ABI: ${ANDROID_ABI}")
    if(ANDROID_ABI STREQUAL "arm64-v8a")
        set(GGML_SYSTEM_ARCH "ARM")
        set(GGML_CPU_KLEIDIAI OFF)
        set(GGML_OPENMP ON)
    else()
        message(FATAL_ERROR "Unsupported ABI: ${ANDROID_ABI}. Only arm64-v8a is supported.")
    endif()
endif()

# Disable tools build — we only need core llama + common
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)

# Enable Vulkan GPU backend
set(GGML_VULKAN ON CACHE BOOL "" FORCE)

# The NDK provides vulkan.h (C) but not vulkan.hpp (C++).
# Use system-installed Vulkan headers which include both.
if(DEFINED ANDROID_ABI)
    foreach(_vk_path "/opt/homebrew/include" "/opt/vulkan-headers" "/usr/local/include")
        if(EXISTS "${_vk_path}/vulkan/vulkan.hpp")
            set(Vulkan_INCLUDE_DIR "${_vk_path}" CACHE PATH "Vulkan headers with C++ bindings")
            set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -isystem ${_vk_path}" CACHE STRING "" FORCE)
            set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -isystem ${_vk_path}" CACHE STRING "" FORCE)
            message(STATUS "Found Vulkan C++ headers at: ${_vk_path}")
            break()
        endif()
    endforeach()
endif()

# Build llama.cpp (includes ggml, llama, common)
add_subdirectory(${LLAMA_SRC} build-llama)

# --------------------------------------------------------------------------
# JNI bridge library
# --------------------------------------------------------------------------

add_library(${CMAKE_PROJECT_NAME} SHARED
        llm_jni.cpp)

target_include_directories(${CMAKE_PROJECT_NAME} PRIVATE
        ${LLAMA_SRC}
        ${LLAMA_SRC}/common
        ${LLAMA_SRC}/include
        ${LLAMA_SRC}/ggml/include
        ${LLAMA_SRC}/ggml/src)

target_link_libraries(${CMAKE_PROJECT_NAME}
        llama
        common
        android
        log)
