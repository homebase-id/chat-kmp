cmake_minimum_required(VERSION 3.22.1)
project(homebase_imageresize C)

add_library(homebase_imageresize SHARED image_resize.c)
# The debug variant would otherwise run stb at -O0, ten times slower than what ships.
target_compile_options(homebase_imageresize PRIVATE -O3)
target_link_options(homebase_imageresize PRIVATE "-Wl,-z,max-page-size=16384")
target_link_libraries(homebase_imageresize jnigraphics m)
