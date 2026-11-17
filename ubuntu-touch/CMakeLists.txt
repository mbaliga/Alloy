# SPDX-License-Identifier: AGPL-3.0-only
# Click-package build for Ubuntu Touch (Clickable `builder: cmake`). Qt 5.15 / ubuntu-sdk-20.04.
# Also builds on a stock Ubuntu with qtdeclarative5-dev for headless tests (-DALLOY_BUILD_UI_TESTS=ON).
cmake_minimum_required(VERSION 3.16)
project(alloy LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_AUTOMOC ON)
set(CMAKE_AUTORCC ON)
set(CMAKE_INCLUDE_CURRENT_DIR ON)

# Core library and its tests (the tests also run here without Qt: cmake -S core).
set(ALLOYCORE_NO_TESTS ON)
add_subdirectory(core)

find_package(Qt5 5.12 REQUIRED COMPONENTS Core Gui Qml Quick Concurrent)

add_library(alloybackend STATIC src/alloybackend.cpp src/alloybackend.h)
target_link_libraries(alloybackend PUBLIC alloycore Qt5::Core Qt5::Gui Qt5::Qml Qt5::Concurrent)
target_include_directories(alloybackend PUBLIC src)

# The executable is named after the click app: the .desktop Exec= line uses it.
add_executable(alloy src/main.cpp qml.qrc)
target_link_libraries(alloy alloybackend Qt5::Quick)

option(ALLOY_BUILD_UI_TESTS "Build the headless UI smoke test" OFF)
if(ALLOY_BUILD_UI_TESTS)
  find_package(Qt5 REQUIRED COMPONENTS Test)
  enable_testing()
  add_executable(alloy_ui_smoke tests/ui_smoke.cpp qml.qrc)
  target_link_libraries(alloy_ui_smoke alloybackend Qt5::Quick Qt5::Test)
  add_test(NAME ui_smoke_offscreen COMMAND alloy_ui_smoke)
  set_tests_properties(ui_smoke_offscreen PROPERTIES ENVIRONMENT "QT_QPA_PLATFORM=offscreen")
endif()

# ---- Click packaging. Clickable runs `DESTDIR=<install dir> make install` with CMAKE_INSTALL_PREFIX=/. so every
# DESTINATION below is relative and lands at the click root. ----
set(APP_ID "alloy.mbaliga")          # click package name == app id; OWNER-DECISION, see README.md
set(APP_VERSION "0.1.0")
set(APP_MAINTAINER "Alloy maintainers <alloy@invalid.example>")  # OWNER-FILL before OpenStore submission
if(NOT DEFINED CLICK_ARCH)
  # dpkg-architecture honours DEB_HOST_ARCH, which Clickable's cross-build containers set for --arch arm64/armhf.
  execute_process(COMMAND dpkg-architecture -qDEB_HOST_ARCH OUTPUT_VARIABLE CLICK_ARCH OUTPUT_STRIP_TRAILING_WHITESPACE ERROR_QUIET)
  if(NOT CLICK_ARCH)
    set(CLICK_ARCH "amd64")
  endif()
endif()
set(CLICK_FRAMEWORK "ubuntu-sdk-20.04")

configure_file(manifest.json.in ${CMAKE_CURRENT_BINARY_DIR}/manifest.json @ONLY)
configure_file(alloy.desktop.in ${CMAKE_CURRENT_BINARY_DIR}/alloy.desktop @ONLY)

install(TARGETS alloy RUNTIME DESTINATION .)
install(FILES ${CMAKE_CURRENT_BINARY_DIR}/manifest.json ${CMAKE_CURRENT_BINARY_DIR}/alloy.desktop
              alloy.apparmor DESTINATION .)
install(FILES assets/icon.png DESTINATION assets)
