cmake_minimum_required(VERSION 3.4.1)
project(SliceBeam)

# Android Gradle Plugin requires the external native build entry point to be
# named CMakeLists.txt. Keep the substantive Alloy/SliceBeam configuration in
# the explicitly named file so static validation and source review remain
# straightforward.
include("${CMAKE_CURRENT_LIST_DIR}/CMakeLists.native.txt")
