include(FindPackageHandleStandardArgs)

set(OpenCV_VERSION "4.6.0")
set(_opencv_root "${ALLOY_OPENCV_ANDROID_ROOT}")
set(_opencv_include "${_opencv_root}/jni/include")
set(_opencv_world "${_opencv_root}/staticlibs/${ANDROID_ABI}/libopencv_world.a")
set(_opencv_tegra_hal "${_opencv_root}/3rdparty/libs/${ANDROID_ABI}/libtegra_hal.a")
set(_opencv_version_header "${_opencv_include}/opencv2/core/version.hpp")

set(OpenCV_core_FOUND FALSE)
if(EXISTS "${_opencv_include}/opencv2/opencv.hpp"
        AND EXISTS "${_opencv_world}"
        AND EXISTS "${_opencv_tegra_hal}"
        AND EXISTS "${_opencv_version_header}")
    file(STRINGS "${_opencv_version_header}" _opencv_version_lines
        REGEX "^#define CV_VERSION_(MAJOR|MINOR|REVISION)")
    string(JOIN ";" _opencv_version_text ${_opencv_version_lines})
    if(_opencv_version_text MATCHES "CV_VERSION_MAJOR[ \\t]+4"
            AND _opencv_version_text MATCHES "CV_VERSION_MINOR[ \\t]+6"
            AND _opencv_version_text MATCHES "CV_VERSION_REVISION[ \\t]+0")
        set(OpenCV_core_FOUND TRUE)
        set(OpenCV_INCLUDE_DIRS "${_opencv_include}")
        set(OpenCV_LIBS opencv_world)
        if(NOT TARGET opencv_tegra_hal)
            add_library(opencv_tegra_hal UNKNOWN IMPORTED GLOBAL)
            set_target_properties(opencv_tegra_hal PROPERTIES
                IMPORTED_LOCATION "${_opencv_tegra_hal}")
        endif()
        if(NOT TARGET opencv_world)
            add_library(opencv_world UNKNOWN IMPORTED GLOBAL)
            set_target_properties(opencv_world PROPERTIES
                IMPORTED_LOCATION "${_opencv_world}"
                INTERFACE_INCLUDE_DIRECTORIES "${_opencv_include}"
                INTERFACE_LINK_LIBRARIES "opencv_tegra_hal;android;log;z;dl;m")
        endif()
    endif()
endif()

find_package_handle_standard_args(OpenCV
    REQUIRED_VARS _opencv_include _opencv_world _opencv_tegra_hal _opencv_version_header OpenCV_core_FOUND
    VERSION_VAR OpenCV_VERSION)
