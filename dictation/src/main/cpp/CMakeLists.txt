cmake_minimum_required(VERSION 3.22.1)
project(needle_jni C)

add_library(needle STATIC IMPORTED)
set_target_properties(needle PROPERTIES IMPORTED_LOCATION
    ${CMAKE_CURRENT_SOURCE_DIR}/libs/${ANDROID_ABI}/libneedle.a)

add_library(needle_jni SHARED needle_jni.c)

target_include_directories(needle_jni PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/include)
# libneedle.a is C++ (exceptions, std::string, chrono) — link libc++ statically
target_link_libraries(needle_jni needle c++_static c++abi log)
