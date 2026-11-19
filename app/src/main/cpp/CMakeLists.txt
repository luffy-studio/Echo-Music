cmake_minimum_required(VERSION 3.22.1)

project("echo_lame")

add_library(
    echo_lame
    SHARED
    lame_native.cpp
    lame/lame.c
)

target_include_directories(
    echo_lame
    PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/lame
)

find_library(
    log-lib
    log
)

target_link_libraries(
    echo_lame
    ${log-lib}
)
