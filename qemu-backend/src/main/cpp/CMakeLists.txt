cmake_minimum_required(VERSION 3.22.1)
project(qemu_ula C)

set(QEMU_PREBUILT "${PREBUILT_DIR}/${ANDROID_ABI}")

add_library(lib_qemu STATIC IMPORTED)
set_target_properties(lib_qemu PROPERTIES
    IMPORTED_LOCATION "${QEMU_PREBUILT}/libqemu-aarch64-softmmu.a")

add_library(lib_qemu_common STATIC IMPORTED)
set_target_properties(lib_qemu_common PROPERTIES
    IMPORTED_LOCATION "${QEMU_PREBUILT}/libqemu-common.a")

add_library(lib_glib STATIC IMPORTED)
set_target_properties(lib_glib PROPERTIES
    IMPORTED_LOCATION "${QEMU_PREBUILT}/libglib-2.0.a")

add_library(lib_gmodule STATIC IMPORTED)
set_target_properties(lib_gmodule PROPERTIES
    IMPORTED_LOCATION "${QEMU_PREBUILT}/libgmodule-2.0.a")

add_library(lib_pixman STATIC IMPORTED)
set_target_properties(lib_pixman PROPERTIES
    IMPORTED_LOCATION "${QEMU_PREBUILT}/libpixman-1.a")

add_library(lib_ffi STATIC IMPORTED)
set_target_properties(lib_ffi PROPERTIES
    IMPORTED_LOCATION "${QEMU_PREBUILT}/libffi.a")

add_library(lib_pcre2 STATIC IMPORTED)
set_target_properties(lib_pcre2 PROPERTIES
    IMPORTED_LOCATION "${QEMU_PREBUILT}/libpcre2-8.a")

add_library(lib_qemu_stubs STATIC IMPORTED)
set_target_properties(lib_qemu_stubs PROPERTIES
    IMPORTED_LOCATION "${QEMU_PREBUILT}/libqemu-stubs.a")

# qemu_img JNI bridge — tiny, no QEMU deps
add_library(qemu_img_ula SHARED qemu_img_jni.c)
target_link_options(qemu_img_ula PRIVATE -Wl,-z,max-page-size=16384)
target_link_libraries(qemu_img_ula android log)

# QEMU runtime JNI bridge
add_library(emul_ula SHARED qemu_jni.c vhost_stubs.c)
target_link_options(emul_ula PRIVATE -Wl,-z,max-page-size=16384)
target_include_directories(emul_ula PRIVATE "${QEMU_PREBUILT}/include")
target_link_libraries(emul_ula
    "-Wl,-Bsymbolic"
    "-Wl,--whole-archive"
    lib_qemu
    lib_qemu_common
    "-Wl,--no-whole-archive"
    lib_glib
    lib_gmodule
    lib_pixman
    lib_ffi
    lib_pcre2
    android
    log
    z
    m
    dl
    lib_qemu_stubs)
