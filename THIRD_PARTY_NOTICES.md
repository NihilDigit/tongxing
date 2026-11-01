# Third-party software and assets

The Apache-2.0 license at the repository root covers original TongXing source
code and documentation. Existing third-party notices remain in force.

## Bundled third-party source

| Component | Location | License and attribution |
|---|---|---|
| libyuv | `app/src/main/cpp/third_party/libyuv/` | BSD-3-Clause, The LibYuv Project Authors; retain `LICENSE`, `PATENTS`, and `AUTHORS` |
| Gradle Wrapper | `gradle/wrapper/`, `gradlew`, `gradlew.bat` | Apache-2.0, Gradle contributors |
| TensorFlow Lite schema | `scripts/vendor/tflite_schema.fbs` | TensorFlow 2.16.1, Apache-2.0, The TensorFlow Authors; original header retained |
| AgentCore | `lightway_agentcore/android/agentcore/` | Existing Apache-2.0 licensing; TongXing contributors |

## Source fetched during setup

`scripts/prepare_source_dependencies.py` retrieves the Android SDK fork from
`https://github.com/NihilDigit/llama.cpp`, pinned to
`219a99d74169bd26006669900af03e4f15b6d163`.

The fork retains the ggml authors' MIT license. Its bundled components include
nlohmann/json (MIT), cpp-httplib (MIT), and Khronos OpenCL headers (Apache-2.0).
Preserve the fork's license files and source notices when redistributing it.
TongXing's Kotlin/JNI integration uses this fork rather than an unmodified
upstream llama.cpp checkout.

## Dependencies fetched for the Android build

Versions and artifact coordinates are recorded in `gradle/libs.versions.toml`
and module Gradle files.

- AndroidX, Kotlin, kotlinx.coroutines, kotlinx.serialization, Ktor, OkHttp,
  TensorFlow Lite, and OpenCV are fetched by Gradle. Their published artifacts
  carry their respective licenses and notices.
- Sherpa-ONNX is Apache-2.0. The pinned official AAR also includes native
  dependencies; preserve its bundled notices when distributing an APK.
- Qualcomm QNN runtime and LiteRT delegate use Qualcomm's distribution terms.
  The project references Maven artifacts and provides no separate
  permission to redistribute Qualcomm binaries. Review their artifact terms
  before distributing an APK containing them.
- Other dependencies, including the MCP Kotlin SDK, DashScope SDK, NanoHTTPD,
  RxJava and test libraries, retain their published artifact licenses.

## Models

Model setup is documented in `README.md`. Model sources and checksums are
recorded in `models.lock.json`:

- MiDaS V2 Small (`qualcomm/Midas-V2`): MIT
- YOLOX Small (`qualcomm/Yolo-X`): Apache-2.0
- FFNet-78S-LowRes (`qualcomm/FFNet-78S-LowRes`): BSD-3-Clause
- MobileNetV2 classification and derived pooled-feature graph (`qualcomm/MobileNet-v2`): Apache-2.0
- Qwen3-1.7B (`Qwen/Qwen3-1.7B` and Q4_0 GGUF conversion): Apache-2.0
- Paraformer Chinese INT8 (`csukuangfj/sherpa-onnx-paraformer-zh-2024-03-09`): subject to the upstream ModelScope Paraformer model license linked from its model card

Public availability of a download is distinct from permission to redistribute
model weights or Qualcomm binaries.
