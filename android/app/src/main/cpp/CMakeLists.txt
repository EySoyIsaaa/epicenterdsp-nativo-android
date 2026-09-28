cmake_minimum_required(VERSION 3.22.1)

project(epicenter_dsp_native)

add_library(
    epicenterdsp
    SHARED
    EpicenterDSPCore.cpp
    EpicenterDSPJNI.cpp
)

find_library(
    log-lib
    log
)

target_compile_features(epicenterdsp PRIVATE cxx_std_17)
target_compile_options(epicenterdsp PRIVATE -O2)

# Android 15+ 16 KB memory-page-size support (Google Play requirement).
# Force 16 KB-aligned ELF LOAD segments so the library loads on devices that
# use 16 KB pages. Works on NDK r23+ regardless of the default page size.
target_link_options(epicenterdsp PRIVATE "-Wl,-z,max-page-size=16384")

target_link_libraries(
    epicenterdsp
    ${log-lib}
)
