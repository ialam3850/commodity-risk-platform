# C++ pricing and risk core: Black-76, Greeks, implied vol, historical and
# Monte Carlo VaR. Builds a static library, its tests, a benchmark, and
# (optionally) a Python module via pybind11.
cmake_minimum_required(VERSION 3.20)
project(pricing_core LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)  # the library also goes into the Python module
set(CMAKE_EXPORT_COMPILE_COMMANDS ON)
if(NOT CMAKE_BUILD_TYPE)
  set(CMAKE_BUILD_TYPE Release)
endif()

option(PC_BUILD_TESTS "Build unit tests" ON)
option(PC_BUILD_PYTHON "Build the pybind11 Python module" ON)
option(PC_SANITIZE "Build with AddressSanitizer + UBSan" OFF)

add_compile_options(-Wall -Wextra -Wpedantic -Wshadow -Wconversion)
if(PC_SANITIZE)
  add_compile_options(-fsanitize=address,undefined -fno-omit-frame-pointer)
  add_link_options(-fsanitize=address,undefined)
endif()

find_package(Threads REQUIRED)
add_library(pricing STATIC src/black76.cpp src/var.cpp)
target_include_directories(pricing PUBLIC include)
target_link_libraries(pricing PUBLIC Threads::Threads)

# Writes testdata/black76_golden.csv, which the Java service's tests also check.
add_executable(gen_golden tools/gen_golden.cpp)
target_link_libraries(gen_golden PRIVATE pricing)

add_executable(bench_var tools/bench_var.cpp)
target_link_libraries(bench_var PRIVATE pricing)

include(FetchContent)
if(PC_BUILD_TESTS)
  enable_testing()
  FetchContent_Declare(googletest
    URL https://github.com/google/googletest/archive/refs/tags/v1.15.2.tar.gz
    DOWNLOAD_EXTRACT_TIMESTAMP TRUE)
  set(INSTALL_GTEST OFF CACHE BOOL "" FORCE)
  FetchContent_MakeAvailable(googletest)
  add_executable(pricing_tests tests/test_black76.cpp tests/test_var.cpp)
  target_link_libraries(pricing_tests PRIVATE pricing GTest::gtest_main)
  include(GoogleTest)
  gtest_discover_tests(pricing_tests)
endif()

if(PC_BUILD_PYTHON)
  FetchContent_Declare(pybind11
    URL https://github.com/pybind/pybind11/archive/refs/tags/v2.13.6.tar.gz
    DOWNLOAD_EXTRACT_TIMESTAMP TRUE)
  FetchContent_MakeAvailable(pybind11)
  pybind11_add_module(pricing_core python/bindings.cpp)
  target_link_libraries(pricing_core PRIVATE pricing)
endif()
