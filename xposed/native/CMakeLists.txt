# libls_spoofer_native — 乐跑 shell spoof 的 C 级 hook（fopen / __system_property_get）
# 由 xposed 模块的 externalNativeBuild 自动编译，随 APK 打进 jniLibs。
cmake_minimum_required(VERSION 3.22)
project(ls_spoofer_native CXX)

add_library(ls_spoofer_native SHARED ls_spoofer_native.cpp)

target_link_libraries(ls_spoofer_native
    log
    dl
)

set_target_properties(ls_spoofer_native PROPERTIES
    CXX_STANDARD 17
    POSITION_INDEPENDENT_CODE ON
)
