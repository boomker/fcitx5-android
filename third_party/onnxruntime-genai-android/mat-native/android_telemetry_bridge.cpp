// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.
// From ONNX Runtime GenAI v0.17.0/cmake/telemetry/android_telemetry_bridge.cpp.

#include "http/HttpClient_Android.hpp"

extern "C" __attribute__((visibility("default"))) bool
OrtGenAIIsAndroidTelemetryReady() noexcept {
  try {
    return Microsoft::Applications::Events::HttpClient_Android::GetClientInstance() != nullptr;
  } catch (...) {
    return false;
  }
}
