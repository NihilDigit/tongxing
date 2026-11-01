cmake_minimum_required(VERSION 3.22.1)

project(scops)

add_library(
    scops
    SHARED
    scops.cpp
)

# SafetyCore V2 library
add_library(
    safetycore_v2
    SHARED
    v2/kalman_filter.cpp
    v2/hungarian.cpp
    v2/scale_recovery.cpp
    v2/obstacle_extractor.cpp
    v2/deep_sort_tracker.cpp
    v2/danger_assessor.cpp
    v2/safetycore_v2_jni.cpp
)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

target_compile_options(scops PRIVATE -fno-exceptions -fno-rtti)
include(cmake/FloatingPoint.cmake)
configure_safetycore_floating_point(scops)
configure_safetycore_floating_point(safetycore_v2)

target_include_directories(safetycore_v2 PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/v2)

find_library(
    log-lib
    log
)

target_link_libraries(
    scops
    ${log-lib}
)

target_link_libraries(
    safetycore_v2
    ${log-lib}
)

if(ANDROID)
    # Ensure ELF segments are aligned for 16KB page-size devices.
    target_link_options(scops PRIVATE "-Wl,-z,max-page-size=16384")
    target_link_options(safetycore_v2 PRIVATE "-Wl,-z,max-page-size=16384")
endif()
