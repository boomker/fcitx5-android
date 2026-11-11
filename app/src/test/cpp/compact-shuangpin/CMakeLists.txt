cmake_minimum_required(VERSION 3.18)
project(test_android_frontend_compact_shuangpin LANGUAGES CXX)

find_package(Fcitx5Core REQUIRED CONFIG)

set(CHINESE_ADDONS_BINARY_DIR "" CACHE PATH "Host build containing the Pinyin addon and test fixtures")
if(NOT EXISTS "${CHINESE_ADDONS_BINARY_DIR}/test/inputmethod/shuangpin.conf")
    message(FATAL_ERROR "Set CHINESE_ADDONS_BINARY_DIR to a built fcitx5-chinese-addons test directory")
endif()

add_executable(testandroidfrontend testandroidfrontend.cpp)
target_compile_features(testandroidfrontend PRIVATE cxx_std_20)
target_compile_definitions(testandroidfrontend PRIVATE
    FCITX_GETTEXT_DOMAIN="fcitx5-android"
    TEST_CHINESE_ADDONS_BINARY_DIR="${CHINESE_ADDONS_BINARY_DIR}")
target_link_libraries(testandroidfrontend PRIVATE Fcitx5::Core)

enable_testing()
add_test(NAME testandroidfrontend COMMAND testandroidfrontend)
