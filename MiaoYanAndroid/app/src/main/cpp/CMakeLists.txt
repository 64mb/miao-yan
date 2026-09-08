cmake_minimum_required(VERSION 3.22.1)

project(miaoyan_cmark LANGUAGES C CXX)

set(CMAKE_C_STANDARD 99)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(CMARK_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/third_party/cmark-gfm")
set(CMARK_SRC "${CMARK_ROOT}/src")
set(CMARK_EXT "${CMARK_ROOT}/extensions")
set(CMARK_GENERATED "${CMAKE_CURRENT_BINARY_DIR}/cmark-generated")

set(PROJECT_VERSION_MAJOR 0)
set(PROJECT_VERSION_MINOR 29)
set(PROJECT_VERSION_PATCH 0)
set(PROJECT_VERSION_GFM 13)
set(HAVE_STDBOOL_H 1)
set(HAVE___BUILTIN_EXPECT 1)
set(HAVE___ATTRIBUTE__ 1)

file(MAKE_DIRECTORY "${CMARK_GENERATED}")
configure_file("${CMARK_SRC}/config.h.in" "${CMARK_GENERATED}/config.h")
configure_file("${CMARK_SRC}/cmark-gfm_version.h.in" "${CMARK_GENERATED}/cmark-gfm_version.h" @ONLY)

add_library(cmark_gfm STATIC
    "${CMARK_SRC}/arena.c"
    "${CMARK_SRC}/blocks.c"
    "${CMARK_SRC}/buffer.c"
    "${CMARK_SRC}/cmark.c"
    "${CMARK_SRC}/cmark_ctype.c"
    "${CMARK_SRC}/commonmark.c"
    "${CMARK_SRC}/footnotes.c"
    "${CMARK_SRC}/houdini_href_e.c"
    "${CMARK_SRC}/houdini_html_e.c"
    "${CMARK_SRC}/houdini_html_u.c"
    "${CMARK_SRC}/html.c"
    "${CMARK_SRC}/inlines.c"
    "${CMARK_SRC}/iterator.c"
    "${CMARK_SRC}/latex.c"
    "${CMARK_SRC}/linked_list.c"
    "${CMARK_SRC}/man.c"
    "${CMARK_SRC}/map.c"
    "${CMARK_SRC}/node.c"
    "${CMARK_SRC}/plaintext.c"
    "${CMARK_SRC}/plugin.c"
    "${CMARK_SRC}/references.c"
    "${CMARK_SRC}/registry.c"
    "${CMARK_SRC}/render.c"
    "${CMARK_SRC}/scanners.c"
    "${CMARK_SRC}/syntax_extension.c"
    "${CMARK_SRC}/utf8.c"
    "${CMARK_SRC}/xml.c"
)
target_compile_definitions(cmark_gfm PUBLIC CMARK_GFM_STATIC_DEFINE)
target_include_directories(cmark_gfm PUBLIC "${CMARK_SRC}" "${CMARK_GENERATED}")
set_target_properties(cmark_gfm PROPERTIES POSITION_INDEPENDENT_CODE ON)

include(GenerateExportHeader)
generate_export_header(cmark_gfm
    BASE_NAME cmark_gfm
    EXPORT_FILE_NAME "${CMARK_GENERATED}/cmark-gfm_export.h"
)

add_library(cmark_gfm_extensions STATIC
    "${CMARK_EXT}/autolink.c"
    "${CMARK_EXT}/core-extensions.c"
    "${CMARK_EXT}/ext_scanners.c"
    "${CMARK_EXT}/strikethrough.c"
    "${CMARK_EXT}/table.c"
    "${CMARK_EXT}/tagfilter.c"
    "${CMARK_EXT}/tasklist.c"
)
target_compile_definitions(cmark_gfm_extensions PUBLIC
    CMARK_GFM_STATIC_DEFINE
    CMARK_GFM_EXTENSIONS_STATIC_DEFINE
)
target_include_directories(cmark_gfm_extensions PUBLIC "${CMARK_EXT}")
target_link_libraries(cmark_gfm_extensions PUBLIC cmark_gfm)
set_target_properties(cmark_gfm_extensions PROPERTIES POSITION_INDEPENDENT_CODE ON)

add_library(miaoyan_cmark SHARED cmark_jni.cpp)
target_compile_options(miaoyan_cmark PRIVATE -Wall -Wextra -Werror)
target_link_libraries(miaoyan_cmark PRIVATE cmark_gfm_extensions cmark_gfm)
