cmake_minimum_required(VERSION 3.22.1)
project(safetycore_native_tests LANGUAGES CXX)
enable_testing()
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(NATIVE_SOURCE_DIR "${CMAKE_CURRENT_LIST_DIR}/../../main/cpp")
include("${NATIVE_SOURCE_DIR}/cmake/FloatingPoint.cmake")
add_library(danger_assessor_under_test STATIC "${NATIVE_SOURCE_DIR}/v2/danger_assessor.cpp")
target_include_directories(danger_assessor_under_test PUBLIC "${NATIVE_SOURCE_DIR}/v2")
configure_safetycore_floating_point(danger_assessor_under_test)

# Keep test assertions independent of the production floating-point options.
add_executable(danger_assessor_test danger_assessor_test.cpp)
target_link_libraries(danger_assessor_test PRIVATE danger_assessor_under_test)
add_test(NAME danger_assessor_nonfinite COMMAND danger_assessor_test)
