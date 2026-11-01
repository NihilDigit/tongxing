cmake_minimum_required(VERSION 3.22.1)
project(yuvbridge C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_subdirectory(third_party/libyuv)

add_library(yuvbridge SHARED
    yuv_bridge.cpp
)

target_include_directories(yuvbridge PRIVATE
    third_party/libyuv/include
)

target_link_libraries(yuvbridge
    yuv
    log
)

if(ANDROID)
    # Ensure ELF segments are aligned for 16KB page-size devices.
    target_link_options(yuvbridge PRIVATE "-Wl,-z,max-page-size=16384")
    # libyuv is built from source in this module; align it as well.
    if(TARGET yuv_shared)
        target_link_options(yuv_shared PRIVATE "-Wl,-z,max-page-size=16384")
    endif()
endif()
