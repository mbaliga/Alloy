cmake_minimum_required(VERSION 3.22)
project(AlloyOfficialOrcaOpenCvConsumer LANGUAGES CXX)

if(NOT DEFINED ORCA_SOURCE_DIR)
    message(FATAL_ERROR "ORCA_SOURCE_DIR must identify the pinned official Orca source")
endif()

find_package(OpenCV REQUIRED core)

add_executable(official_orca_opencv_consumer main.cpp)
target_compile_features(official_orca_opencv_consumer PRIVATE cxx_std_17)
target_include_directories(official_orca_opencv_consumer PRIVATE
    "${ORCA_SOURCE_DIR}/src/libslic3r"
)
target_link_libraries(official_orca_opencv_consumer PRIVATE opencv_world)
