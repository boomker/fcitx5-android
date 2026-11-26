cmake_minimum_required(VERSION 3.18)
project(test_android_frontend_compact_shuangpin LANGUAGES CXX)

find_package(Fcitx5Core REQUIRED CONFIG)

set(CHINESE_ADDONS_BINARY_DIR "" CACHE PATH "Host build containing the Pinyin addon and test fixtures")
if(NOT EXISTS "${CHINESE_ADDONS_BINARY_DIR}/test/inputmethod/shuangpin.conf")
    message(FATAL_ERROR "Set CHINESE_ADDONS_BINARY_DIR to a built fcitx5-chinese-addons test directory")
endif()

add_executable(testandroidfrontend testandroidfrontend.cpp)
add_executable(testuppercaseenglishfrontend testuppercaseenglishfrontend.cpp)
target_compile_features(testuppercaseenglishfrontend PRIVATE cxx_std_20)
target_compile_definitions(testuppercaseenglishfrontend PRIVATE
    FCITX_GETTEXT_DOMAIN="fcitx5-android"
    TEST_CHINESE_ADDONS_BINARY_DIR="${CHINESE_ADDONS_BINARY_DIR}")
target_link_libraries(testuppercaseenglishfrontend PRIVATE Fcitx5::Core)
add_executable(testexpandedcandidateorder testexpandedcandidateorder.cpp)
target_compile_features(testexpandedcandidateorder PRIVATE cxx_std_20)
add_executable(testexpandedcandidatefrontend testexpandedcandidatefrontend.cpp)
target_compile_features(testexpandedcandidatefrontend PRIVATE cxx_std_20)
get_filename_component(CHINESE_ADDONS_TEST_SOURCE_DIR
    "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../lib/fcitx5-chinese-addons/src/main/cpp/fcitx5-chinese-addons"
    ABSOLUTE)
add_executable(testenglishprediction testenglishprediction.cpp
    "${CHINESE_ADDONS_TEST_SOURCE_DIR}/im/pinyin/englishprediction.cpp")
target_compile_features(testenglishprediction PRIVATE cxx_std_20)
target_include_directories(testenglishprediction PRIVATE
    "${CHINESE_ADDONS_TEST_SOURCE_DIR}/im/pinyin")
target_link_libraries(testenglishprediction PRIVATE Fcitx5::Core)
target_compile_definitions(testexpandedcandidatefrontend PRIVATE
    FCITX_GETTEXT_DOMAIN="fcitx5-android"
    TEST_CHINESE_ADDONS_BINARY_DIR="${CHINESE_ADDONS_BINARY_DIR}"
    TEST_CHINESE_ADDONS_SOURCE_DIR="${CHINESE_ADDONS_TEST_SOURCE_DIR}")
target_link_libraries(testexpandedcandidatefrontend PRIVATE Fcitx5::Core)
target_compile_definitions(testuppercaseenglishfrontend PRIVATE
    TEST_CHINESE_ADDONS_SOURCE_DIR="${CHINESE_ADDONS_TEST_SOURCE_DIR}")
target_compile_features(testandroidfrontend PRIVATE cxx_std_20)
target_compile_definitions(testandroidfrontend PRIVATE
    FCITX_GETTEXT_DOMAIN="fcitx5-android"
    TEST_CHINESE_ADDONS_BINARY_DIR="${CHINESE_ADDONS_BINARY_DIR}")
target_link_libraries(testandroidfrontend PRIVATE Fcitx5::Core)

enable_testing()
add_test(NAME testandroidfrontend COMMAND testandroidfrontend)
add_test(NAME testuppercaseenglishfrontend COMMAND testuppercaseenglishfrontend)
add_test(NAME testenglishprediction COMMAND testenglishprediction)
add_test(NAME testexpandedcandidateorder COMMAND testexpandedcandidateorder)
add_test(NAME testexpandedcandidatefrontend COMMAND testexpandedcandidatefrontend)
