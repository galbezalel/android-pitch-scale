cmake_minimum_required(VERSION 3.10.2)
project("soundtouch")

add_definitions(-DSOUNDTOUCH_INTEGER_SAMPLES=1)

include_directories(include)
include_directories(soundtouch)

file(GLOB ST_SRC "soundtouch/*.cpp")

add_library(
    soundtouchjni
    SHARED
    soundtouch_jni.cpp
    ${ST_SRC}
)

find_library(
    log-lib
    log
)

target_link_libraries(
    soundtouchjni
    ${log-lib}
)
