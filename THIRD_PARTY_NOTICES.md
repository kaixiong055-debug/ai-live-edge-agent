# Third Party Notices

## Sherpa-ONNX

- Version: `1.12.10`
- Java API JAR: `sherpa-onnx-v1.12.10.jar`
- Windows native-lib JAR: `sherpa-onnx-native-lib-win-x64-v1.12.10.jar`
- Project: https://github.com/k2-fsa/sherpa-onnx
- License: Apache License 2.0
- Use: local offline streaming speech recognition through Java/JNI.

## ONNX Runtime

- Used by the Sherpa-ONNX native runtime package.
- Project: https://github.com/microsoft/onnxruntime
- License: MIT License.
- Note: production redistribution should keep the notices bundled with the exact native package.

## Streaming Paraformer zh/en INT8 Model

- Model: `csukuangfj/sherpa-onnx-streaming-paraformer-bilingual-zh-en`
- Source: https://huggingface.co/csukuangfj/sherpa-onnx-streaming-paraformer-bilingual-zh-en
- License shown on model page: Apache-2.0
- Upstream conversion note: ONNX files are converted from the ModelScope DAMO Paraformer online ASR model linked by the model card.
- Default local path: `data/models/sherpa-onnx/streaming-paraformer-zh-en`
- Required files: `encoder.int8.onnx`, `decoder.int8.onnx`, `tokens.txt`

Large model files and native binaries are not packaged into the Spring Boot JAR.
