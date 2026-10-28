cmake_minimum_required(VERSION 3.22.1)
project(bluetooth_video_native LANGUAGES C CXX)

# Host-only mode for the JVM round-trip test of the experimental libopus call codec: builds just the
# Opus JNI bridge as a desktop library (no libaom, no Android log), e.g.
#   cmake -S app/src/main/cpp -B <temp>/opus-host -DBTV_OPUS_HOST_TEST=ON -DJNI_INCLUDE_DIRS=...
# then run the unit tests with BTV_NATIVE_OPUS_HOST_LIB=<built library>. See NativeOpusHostRoundTripTest.
option(BTV_OPUS_HOST_TEST "Build only the libopus JNI bridge for host JVM tests" OFF)

if(NOT BTV_OPUS_HOST_TEST)
    set(CMAKE_ASM_COMPILER "${CMAKE_C_COMPILER}" CACHE FILEPATH "" FORCE)
    # Debug APKs carry benchmark instrumentation, but codec code must still be
    # optimized or their FPS/thermal results are meaningless.
    add_compile_options(-O3)
endif()

# Reproducible builds (tools/check_reproducible_build.py): assert/__FILE__ strings in libopus embedded the
# absolute checkout path, so two builds in different directories differed. -ffile-prefix-map rewrites every
# source, generated-file and build-directory prefix to a fixed /btv/<name> before it reaches an object file.
# Both the logical (junction/symlink kept) and the resolved path are mapped. Applies to the directory scope,
# so it must be called before the add_subdirectory() of the tree it covers.
function(btv_map_source_prefix dir name)
    get_filename_component(abs_dir "${dir}" ABSOLUTE)
    get_filename_component(real_dir "${dir}" REALPATH)
    foreach(prefix IN ITEMS "${abs_dir}" "${real_dir}")
        add_compile_options("-ffile-prefix-map=${prefix}=/btv/${name}")
    endforeach()
endfunction()
if(NOT BTV_OPUS_HOST_TEST)
    btv_map_source_prefix("${CMAKE_CURRENT_LIST_DIR}" app)
    btv_map_source_prefix("${CMAKE_BINARY_DIR}" build)
endif()

# Experimental native call audio (DEBUG test_call_native_opus, default off): libopus 1.5.2, the
# official release tarball https://downloads.xiph.org/releases/opus/opus-1.5.2.tar.gz
# (SHA-256 65c1d2f78b9f2fb20082c38cbe47c951ad5839345876e46941612ee87f9a7ce1, xiph SHA256SUMS.txt)
# extracted to reference-repos/libopus. Fixed configuration: float build, no DRED/OSCE/deep PLC
# (no DNN weights), so the library adds only the classic SILK/CELT codec.
set(OPUS_SOURCE_DIR "${CMAKE_CURRENT_LIST_DIR}/../../../../reference-repos/libopus")
if(NOT EXISTS "${OPUS_SOURCE_DIR}/include/opus.h" OR NOT EXISTS "${OPUS_SOURCE_DIR}/package_version")
    message(FATAL_ERROR "Pinned libopus 1.5.2 source is missing at ${OPUS_SOURCE_DIR} (extract the release tarball named in CMakeLists.txt)")
endif()
file(READ "${OPUS_SOURCE_DIR}/package_version" BTV_OPUS_PACKAGE_VERSION)
if(NOT BTV_OPUS_PACKAGE_VERSION MATCHES "PACKAGE_VERSION=.1[.]5[.]2.")
    message(FATAL_ERROR "reference-repos/libopus is not the pinned 1.5.2 release: ${BTV_OPUS_PACKAGE_VERSION}")
endif()
set(OPUS_BUILD_SHARED_LIBRARY OFF CACHE BOOL "" FORCE)
set(OPUS_BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(OPUS_BUILD_PROGRAMS OFF CACHE BOOL "" FORCE)
set(OPUS_CUSTOM_MODES OFF CACHE BOOL "" FORCE)
set(OPUS_FIXED_POINT OFF CACHE BOOL "" FORCE)
set(OPUS_DRED OFF CACHE BOOL "" FORCE)
set(OPUS_OSCE OFF CACHE BOOL "" FORCE)
set(OPUS_INSTALL_PKG_CONFIG_MODULE OFF CACHE BOOL "" FORCE)
set(OPUS_INSTALL_CMAKE_CONFIG_MODULE OFF CACHE BOOL "" FORCE)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
if(NOT BTV_OPUS_HOST_TEST)
    btv_map_source_prefix("${OPUS_SOURCE_DIR}" libopus)
endif()
add_subdirectory("${OPUS_SOURCE_DIR}" "${CMAKE_BINARY_DIR}/libopus" EXCLUDE_FROM_ALL)

if(BTV_OPUS_HOST_TEST)
    if(NOT JNI_INCLUDE_DIRS)
        find_package(JNI REQUIRED)
    endif()
    add_library(bluetooth_video_opus_host SHARED native_opus_codec.cpp)
    target_compile_features(bluetooth_video_opus_host PRIVATE cxx_std_17)
    target_include_directories(bluetooth_video_opus_host PRIVATE ${JNI_INCLUDE_DIRS})
    target_link_libraries(bluetooth_video_opus_host PRIVATE opus)
    return()
endif()

# Pinned libaom checkout (THIRD_PARTY_NOTICES). Default: reference-repos/libaom; Gradle -PaomSourceDir=<dir> overrides.
if(NOT AOM_SOURCE_DIR)
    set(AOM_SOURCE_DIR "${CMAKE_CURRENT_LIST_DIR}/../../../../reference-repos/libaom")
endif()
if(NOT EXISTS "${AOM_SOURCE_DIR}/aom/aom_encoder.h")
    message(FATAL_ERROR "Pinned libaom source is missing at ${AOM_SOURCE_DIR}")
endif()

set(CONFIG_AV1_ENCODER 1 CACHE BOOL "" FORCE)
set(CONFIG_AV1_DECODER 0 CACHE BOOL "" FORCE)
set(CONFIG_REALTIME_ONLY 0 CACHE BOOL "" FORCE)
set(ENABLE_APPS 0 CACHE BOOL "" FORCE)
set(ENABLE_DOCS 0 CACHE BOOL "" FORCE)
set(ENABLE_EXAMPLES 0 CACHE BOOL "" FORCE)
set(ENABLE_TESTDATA 0 CACHE BOOL "" FORCE)
set(ENABLE_TESTS 0 CACHE BOOL "" FORCE)
set(ENABLE_TOOLS 0 CACHE BOOL "" FORCE)
set(CONFIG_WEBM_IO 0 CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS 0 CACHE BOOL "" FORCE)

btv_map_source_prefix("${AOM_SOURCE_DIR}" libaom)
add_subdirectory("${AOM_SOURCE_DIR}" "${CMAKE_BINARY_DIR}/libaom" EXCLUDE_FROM_ALL)

# Bundled libvpx VP9 encoder (DECISIONS "Design VX"): libvpx v1.17.0, the commit of tag v1.17.0
# (6df3ec34557879fff673706f4a1d9fbd0f3a6f0e, tree manifest SHA-256 in tools/fetch_native_sources.py) at
# reference-repos/libvpx; Gradle -PvpxSourceDir=<dir> overrides. libvpx builds with configure + make, so (as
# Android's external/libvpx and Chromium do) its configure output for each ABI is generated once by
# tools/generate_libvpx_config.py and checked in under libvpx_config/<abi>/: VP9 encoder only, real-time only,
# no VP8/decoder/high bit depth, NEON (arm64: dotprod/i8mm kernels picked at run time).
if(NOT VPX_SOURCE_DIR)
    set(VPX_SOURCE_DIR "${CMAKE_CURRENT_LIST_DIR}/../../../../reference-repos/libvpx")
endif()
if(NOT EXISTS "${VPX_SOURCE_DIR}/vpx/vp8cx.h" OR NOT EXISTS "${VPX_SOURCE_DIR}/CHANGELOG")
    message(FATAL_ERROR "Pinned libvpx v1.17.0 source is missing at ${VPX_SOURCE_DIR} (python tools/fetch_native_sources.py)")
endif()
file(STRINGS "${VPX_SOURCE_DIR}/CHANGELOG" BTV_VPX_CHANGELOG_HEAD LIMIT_COUNT 1)
if(NOT BTV_VPX_CHANGELOG_HEAD MATCHES " v1[.]17[.]0 ")
    message(FATAL_ERROR "${VPX_SOURCE_DIR} is not the pinned libvpx v1.17.0: ${BTV_VPX_CHANGELOG_HEAD}")
endif()
set(VPX_CONFIG_DIR "${CMAKE_CURRENT_LIST_DIR}/libvpx_config/${ANDROID_ABI}")
if(NOT EXISTS "${VPX_CONFIG_DIR}/sources.cmake")
    message(FATAL_ERROR "No libvpx configuration for ABI ${ANDROID_ABI} (tools/generate_libvpx_config.py)")
endif()
include("${VPX_CONFIG_DIR}/sources.cmake")
list(TRANSFORM VPX_SOURCES PREPEND "${VPX_SOURCE_DIR}/")
btv_map_source_prefix("${VPX_SOURCE_DIR}" libvpx)
add_library(btv_vpx_vp9_encoder STATIC ${VPX_SOURCES} "${VPX_CONFIG_DIR}/vpx_config.c")
target_include_directories(btv_vpx_vp9_encoder PUBLIC "${VPX_CONFIG_DIR}" "${VPX_SOURCE_DIR}")
# libvpx's configure adds -DNDEBUG outside --enable-debug; debug APKs carry benchmarks, so keep it release-like.
target_compile_definitions(btv_vpx_vp9_encoder PRIVATE NDEBUG)
target_compile_options(btv_vpx_vp9_encoder PRIVATE -w)
if(ANDROID_ABI STREQUAL "arm64-v8a")
    # libvpx build/make/Makefile: these kernels are built for the extension and called only when
    # vpx_ports/aarch64_cpudetect.c finds it (AT_HWCAP on Android).
    foreach(source IN LISTS VPX_SOURCES)
        if(source MATCHES "_neon_dotprod[.]c$")
            set_source_files_properties("${source}" PROPERTIES COMPILE_OPTIONS "-march=armv8.2-a+dotprod")
        elseif(source MATCHES "_neon_i8mm[.]c$")
            set_source_files_properties("${source}" PROPERTIES COMPILE_OPTIONS "-march=armv8.2-a+dotprod+i8mm")
        endif()
    endforeach()
endif()

add_library(bluetooth_video_native SHARED native_aom_encoder.cpp native_vpx_encoder.cpp native_opus_codec.cpp)
target_compile_features(bluetooth_video_native PRIVATE cxx_std_17)
target_compile_options(bluetooth_video_native PRIVATE -Wall -Wextra -Werror)
target_include_directories(
    bluetooth_video_native
    PRIVATE
    "${AOM_SOURCE_DIR}"
    "${CMAKE_BINARY_DIR}/libaom"
)
target_link_libraries(bluetooth_video_native PRIVATE aom btv_vpx_vp9_encoder opus log)

# 16 KB page-size compatibility (developer.android.com/guide/practices/page-sizes). NDK r28 defaults
# max-page-size to 16 KB but leaves common-page-size at 4 KB, so the GNU_RELRO segment can end on a
# 4 KB boundary: the Debug build's libbluetooth_video_native.so failed the documented RELRO check
# ((VirtAddr + MemSiz) % 0x4000 == 0, remainder 8192) while Release happened to pass. Both flags make
# the layout deterministic; tools/check_apk_16kb.py repeats the check on a built APK.
target_link_options(bluetooth_video_native PRIVATE
    "-Wl,-z,max-page-size=16384"
    "-Wl,-z,common-page-size=16384"
)
