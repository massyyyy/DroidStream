cmake_minimum_required(VERSION 3.22.1)
project(droidstream_native C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)

# --- ENet: scaricato automaticamente durante il configure (la CI ha rete) ---
include(FetchContent)
FetchContent_Declare(
    enet
    URL https://codeload.github.com/enet-org/enet/tar.gz/refs/tags/v1.3.18
)
FetchContent_MakeAvailable(enet)

add_library(droidstream_native SHARED
    native_bridge.cpp
    control_enet.c
)

# Include di ENet, indipendentemente da come li espone il suo CMake
target_include_directories(droidstream_native PRIVATE ${enet_SOURCE_DIR}/include)

find_library(log-lib log)
target_link_libraries(droidstream_native ${log-lib} enet)
