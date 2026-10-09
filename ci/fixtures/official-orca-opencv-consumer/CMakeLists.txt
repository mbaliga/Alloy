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


set(ALLOY_JNI_ROOT "${CMAKE_CURRENT_LIST_DIR}/../../../app/src/main/jni")
add_executable(alloy_obj_color_consumer
    alloy_obj_color_consumer.cpp
    "${ALLOY_JNI_ROOT}/libslic3r/ObjColorUtils.cpp"
)
target_compile_features(alloy_obj_color_consumer PRIVATE cxx_std_17)
target_include_directories(alloy_obj_color_consumer PRIVATE
    "${ALLOY_JNI_ROOT}"
    "${ALLOY_JNI_ROOT}/libslic3r"
)
target_link_libraries(alloy_obj_color_consumer PRIVATE opencv_world)
