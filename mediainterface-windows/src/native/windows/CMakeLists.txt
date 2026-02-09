cmake_minimum_required(VERSION 3.20)
project(mediainterface_winrt LANGUAGES CXX)

if (NOT WIN32)
    message(FATAL_ERROR "This project builds only on Windows.")
endif()

if (NOT DEFINED ENV{JAVA_HOME})
    message(FATAL_ERROR "JAVA_HOME is required to build the JNI DLL.")
endif()

set(JAVA_HOME $ENV{JAVA_HOME})

add_library(mediainterface_winrt SHARED
    mediainterface_winrt.cpp
)

target_compile_features(mediainterface_winrt PRIVATE cxx_std_20)
target_compile_definitions(mediainterface_winrt PRIVATE WIN32_LEAN_AND_MEAN NOMINMAX)

target_include_directories(mediainterface_winrt PRIVATE
    "${JAVA_HOME}/include"
    "${JAVA_HOME}/include/win32"
)

set_target_properties(mediainterface_winrt PROPERTIES
    OUTPUT_NAME "mediainterface_winrt"
)

target_link_libraries(mediainterface_winrt PRIVATE windowsapp)
