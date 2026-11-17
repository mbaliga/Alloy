# SPDX-License-Identifier: AGPL-3.0-only
# Portable C++17 core of the Alloy Ubuntu Touch preparation/inspection companion. No Qt, no network.
cmake_minimum_required(VERSION 3.16)
project(alloycore LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

add_library(alloycore STATIC
  src/gcode_safety.cpp src/plate_planner.cpp src/stl_reader.cpp src/preparation_session.cpp)
target_include_directories(alloycore PUBLIC include)
target_compile_options(alloycore PRIVATE -Wall -Wextra -Wpedantic)
# Bit-exact float parity with the Java planner: forbid fused multiply-add contraction and fast-math.
foreach(tgt alloycore)
  target_compile_options(${tgt} PUBLIC -ffp-contract=off -fno-fast-math)
endforeach()
set_target_properties(alloycore PROPERTIES POSITION_INDEPENDENT_CODE ON AUTOMOC OFF AUTORCC OFF AUTOUIC OFF)

if(NOT ALLOYCORE_NO_TESTS)
  enable_testing()
  set(ALLOY_FIXTURE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../ios/AlloyCore/Tests/AlloyCoreTests/Fixtures")
  add_executable(alloycore_unit_test tests/unit_test.cpp)
  target_link_libraries(alloycore_unit_test alloycore)
  add_executable(alloycore_parity_test tests/parity_test.cpp)
  target_link_libraries(alloycore_parity_test alloycore)
  target_compile_definitions(alloycore_parity_test PRIVATE ALLOY_FIXTURE_DIR="${ALLOY_FIXTURE_DIR}")
  add_test(NAME unit COMMAND alloycore_unit_test)
  add_test(NAME parity_vs_java COMMAND alloycore_parity_test)
endif()
