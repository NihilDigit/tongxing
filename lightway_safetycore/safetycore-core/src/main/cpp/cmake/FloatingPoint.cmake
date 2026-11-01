function(configure_safetycore_floating_point target)
    # Tracking and danger assessment use NaN/Inf sentinels and isfinite guards.
    if(MSVC AND NOT CMAKE_CXX_COMPILER_ID MATCHES "Clang")
        target_compile_options(${target} PRIVATE /O2 /fp:precise)
    else()
        target_compile_options(${target} PRIVATE -O3 -fno-fast-math)
    endif()
endfunction()
