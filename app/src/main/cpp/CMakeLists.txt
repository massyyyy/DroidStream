cmake_minimum_required(VERSION 3.22.1)
project(droidstream_native C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)

# --- Scarica SOLO i sorgenti ENet (nessun sotto-progetto CMake) ---
include(FetchContent)
FetchContent_Declare(
    enet_src
    URL https://github.com/lsalzman/enet/archive/refs/tags/v1.3.18.tar.gz
)
FetchContent_GetProperties(enet_src)
if(NOT enet_src_POPULATED)
    FetchContent_Populate(enet_src)
endif()

# --- Compiliamo ENet noi, con i define che il suo CMake avrebbe messo ---
add_library(enet_vendor STATIC
    ${enet_src_SOURCE_DIR}/callbacks.c
    ${enet_src_SOURCE_DIR}/compress.c
    ${enet_src_SOURCE_DIR}/host.c
    ${enet_src_SOURCE_DIR}/list.c
    ${enet_src_SOURCE_DIR}/packet.c
    ${enet_src_SOURCE_DIR}/peer.c
    ${enet_src_SOURCE_DIR}/protocol.c
    ${enet_src_SOURCE_DIR}/unix.c
)
target_include_directories(enet_vendor PUBLIC ${enet_src_SOURCE_DIR}/include)
target_compile_definitions(enet_vendor PRIVATE
    HAS_SOCKLEN_T=1
    HAS_FCNTL=1
    HAS_POLL=1
    HAS_GETHOSTBYADDR_R=1
    HAS_GETHOSTBYNAME_R=1
)

# --- La nostra libreria ---
add_library(droidstream_native SHARED
    native_bridge.cpp
    control_enet.c
)
target_include_directories(droidstream_native PRIVATE ${enet_src_SOURCE_DIR}/include)

find_library(log-lib log)
target_link_libraries(droidstream_native ${log-lib} enet_vendor)
