cmake_minimum_required(VERSION 3.22)
project(PandoraCore LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 23)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

if(NOT ANDROID OR NOT ANDROID_ABI STREQUAL "arm64-v8a")
    message(FATAL_ERROR "PandoraCore requires Android arm64-v8a")
endif()

add_library(PandoraCore STATIC
    Core/State.cpp
    Core/ProcessMemory.cpp
    Core/FileHooks.cpp
    Core/Tracer.cpp
    Core/Redirect.cpp)

set_target_properties(PandoraCore PROPERTIES POSITION_INDEPENDENT_CODE ON)
target_include_directories(PandoraCore
    PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/include
    PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/Core)
target_compile_options(PandoraCore PRIVATE -Wall -Wextra -Wpedantic)

add_library(PandoraPayload SHARED Jni/Main.cpp Jni/JniBridge.cpp)
set_target_properties(PandoraPayload PROPERTIES OUTPUT_NAME "pandora-core")
target_compile_options(PandoraPayload PRIVATE -Wall -Wextra -Wpedantic)
target_link_libraries(PandoraPayload PRIVATE PandoraCore log)
