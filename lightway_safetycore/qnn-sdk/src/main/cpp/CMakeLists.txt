cmake_minimum_required(VERSION 3.22.1)
project(qnnops)

add_library(qnnops SHARED qnnops.cpp)

find_library(log-lib log)
find_library(android-lib android)
find_library(jnigraphics-lib jnigraphics)

# Depth interpolation and normalization preserve invalid samples with NaN/Inf checks.
target_compile_options(qnnops PRIVATE -O3 -fno-fast-math -DNDEBUG)
target_compile_features(qnnops PRIVATE cxx_std_17)
target_link_libraries(qnnops ${log-lib} ${android-lib} ${jnigraphics-lib})

if(ANDROID)
    # Ensure ELF segments are aligned for 16KB page-size devices.
    target_link_options(qnnops PRIVATE "-Wl,-z,max-page-size=16384")
endif()
