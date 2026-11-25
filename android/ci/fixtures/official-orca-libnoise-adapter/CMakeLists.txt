cmake_minimum_required(VERSION 3.20)
project(OfficialOrcaLibnoiseAdapter NONE)

list(PREPEND CMAKE_MODULE_PATH "${ADAPTER_DIR}")

if (SUPPLY_NOISE_TARGET)
    add_library(pinned_libnoise INTERFACE)
    add_library(noise::noise ALIAS pinned_libnoise)
endif ()

find_package(libnoise REQUIRED)

if (NOT libnoise_FOUND OR NOT TARGET noise::noise)
    message(FATAL_ERROR "libnoise adapter did not retain the source target")
endif ()

add_library(official_libslic3r_fixture INTERFACE)
target_link_libraries(official_libslic3r_fixture INTERFACE ${LIBNOISE_LIBRARY})
