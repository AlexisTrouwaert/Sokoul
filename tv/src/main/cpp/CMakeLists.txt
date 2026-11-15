cmake_minimum_required(VERSION 3.22.1)
project(sokoulcurl C)
set(CURL_IMPERSONATE_ABI_DIR "${CURL_IMPERSONATE_ROOT}/${ANDROID_ABI}")

add_library(curl-impersonate SHARED IMPORTED)
set_target_properties(curl-impersonate PROPERTIES
    IMPORTED_LOCATION "${CURL_IMPERSONATE_ABI_DIR}/libcurl-impersonate.so")

add_library(sokoulcurl SHARED sokoul_curl.c)
target_include_directories(sokoulcurl PRIVATE "${CURL_IMPERSONATE_ABI_DIR}/include")
target_link_libraries(sokoulcurl curl-impersonate android)
target_link_options(sokoulcurl PRIVATE "-Wl,-z,max-page-size=16384")
