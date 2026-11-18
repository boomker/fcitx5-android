# ONNX Runtime GenAI Android 0.20.0-f5a.1

Gradle verifies this AAR against its SHA-256 and checks the 16 KB ELF alignment
of the native libraries before publishing it. The AAR and its marker are kept
outside Gradle's build directory so a clean checkout can publish without an
upstream download, the Android SDK, or a native rebuild.

The source is Microsoft's [ONNX Runtime GenAI 0.20.0 NuGet package](https://www.nuget.org/packages/Microsoft.ML.OnnxRuntimeGenAI/0.20.0).
Extract `runtimes/android/native/onnxruntime-genai.aar` from it. The AAR's SHA-256
must be `2922d8ab70739432204518032f22bbdeab39889c45fc57f3a91cb2f0d04eea78`.
The two `libmat.so` files are rebuilt from Microsoft telemetry SDK 3.10.310.1
with 16 KB ELF alignment and debug sections removed. The other AAR entries
retain their upstream contents. Upstream licenses and notices are in `licenses/`.

From the `onnxruntime-genai-android` directory, with Android NDK 28.0.13004108,
CMake 3.31.6, and build-tools 36.1.0 installed:

```sh
curl -fL https://api.nuget.org/v3-flatcontainer/microsoft.ml.onnxruntimegenai/0.20.0/microsoft.ml.onnxruntimegenai.0.20.0.nupkg -o /tmp/genai-0.20.0.nupkg
unzip -p /tmp/genai-0.20.0.nupkg runtimes/android/native/onnxruntime-genai.aar > /tmp/genai-0.20.0.aar
python3 rebuild_mat.py --rebuild --input /tmp/genai-0.20.0.aar \
  --output prebuilt/onnxruntime-genai-android-0.20.0-f5a.1.aar \
  --sdk "$ANDROID_HOME" --zipalign "$ANDROID_HOME/build-tools/36.1.0/zipalign"
```
