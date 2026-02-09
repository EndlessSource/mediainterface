cmake_minimum_required(VERSION 3.20)
project(mediainterface_mediaremote LANGUAGES CXX OBJCXX)

if (NOT APPLE)
    message(FATAL_ERROR "This project builds only on macOS.")
endif()

add_library(MediaRemoteAdapter SHARED
    MediaRemoteAdapter.m
)

add_executable(MediaRemoteAdapterTestClient
    MediaRemoteAdapterTestClient.m
)

target_compile_options(MediaRemoteAdapter PRIVATE -fobjc-arc)
target_compile_options(MediaRemoteAdapterTestClient PRIVATE -fobjc-arc)

target_link_libraries(MediaRemoteAdapter PRIVATE
    "-framework Foundation"
    "-framework CoreFoundation"
    "-framework AppKit"
)

target_link_libraries(MediaRemoteAdapterTestClient PRIVATE
    "-framework Foundation"
)

set_target_properties(MediaRemoteAdapter PROPERTIES
    FRAMEWORK TRUE
    FRAMEWORK_VERSION "A"
    PUBLIC_HEADER "MediaRemoteAdapter.h"
    MACOSX_FRAMEWORK_IDENTIFIER "org.endlesssource.MediaRemoteAdapter"
    OUTPUT_NAME "MediaRemoteAdapter"
)
