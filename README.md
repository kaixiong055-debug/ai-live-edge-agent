# AI Live Edge Agent

正式维护路径：

```text
D:\git-ai-live\ai-live-edge-agent
```
执行命令
cd D:\git-ai-live\ai-live-edge-agent\desktop
dotnet run --project src\AiLiveEdge.Desktop\AiLiveEdge.Desktop.csproj

当前默认模式是腾讯云实时 ASR。Sherpa-ONNX 本地离线识别保留为可选模式，FunASR 只保留配置和枚举，后续版本再实现。

## 当前能力

- Windows 麦克风自动选择、采集和重试。
- 48k/44.1k/32k 等输入格式统一转换为 `16000Hz / 16bit / mono / little-endian PCM`。
- 默认走腾讯云实时语音识别，临时结果显示在 Console，最终结果进入 `CommandMatcher`。
- `commands.json` 热加载、冷却、防抖、优先级。
- 本地 Renderer、WebSocket、素材管理、运行状态 Console。
- 可选 Sherpa-ONNX 本地离线 Provider 和 WAV 文件测试接口。

## 默认 ASR 配置

```yaml
ai-live:
  asr:
    enabled: true
    provider: TENCENT
    auto-start: true
    engine-model-type: "16k_zh"
    tencent:
      enabled: true
    sherpa:
      enabled: false
```

腾讯云参数保持：

- `engineModelType=16k_zh`
- `voiceFormat=1`
- `result_mod=0`
- `needvad=1`
- `vad_silence_time=1000`

SDK v1.0.68 兼容处理仍使用：

```java
setExtraParam("result_mod", "0")
```

## 腾讯云凭据

不要把真实凭据写进项目文件。运行前用环境变量提供：

```powershell
$env:TENCENT_ASR_APP_ID="你的 AppId"
$env:TENCENT_ASR_SECRET_ID="你的 SecretId"
$env:TENCENT_ASR_SECRET_KEY="你的 SecretKey"
```

缺少环境变量时 Spring Boot 仍会启动，`asrStatus=MISCONFIGURED`，Console 会显示缺少的变量名称，并且不会无限重连。

## 启动

```powershell
cd D:\git-ai-live\ai-live-edge-agent
.\mvnw.cmd spring-boot:run
```

Console：

```text
http://127.0.0.1:18081/console/index.html
```

Renderer：

```text
http://127.0.0.1:18081/renderer/index.html
```

## 麦克风

普通用户不需要填写麦克风名称。`ai-live.audio.device-name` 默认为空，Agent 会自动优先选择真实麦克风设备，例如名称包含 `麦克风`、`Microphone`、`Mic` 的输入设备。

高级用户可以指定设备名称关键字：

```yaml
ai-live:
  audio:
    device-name: "Realtek"
```

没有麦克风、设备占用或读取失败时，Console、Renderer、WebSocket、素材管理和测试动作仍可使用。

## Sherpa-ONNX 可选离线模式

切换到本地离线 ASR：

```yaml
ai-live:
  asr:
    provider: SHERPA_ONNX
    sherpa:
      enabled: true
    tencent:
      enabled: false
```

准备依赖：

```powershell
cd D:\git-ai-live\ai-live-edge-agent
powershell -ExecutionPolicy Bypass -File .\scripts\setup-sherpa-onnx.ps1
```

固定版本：

- Sherpa-ONNX Java API JAR：`sherpa-onnx-v1.12.10.jar`
- Windows x64 native-lib JAR：`sherpa-onnx-native-lib-win-x64-v1.12.10.jar`
- 模型：`csukuangfj/sherpa-onnx-streaming-paraformer-bilingual-zh-en`

默认腾讯云模式下不会加载 Sherpa JNI，不校验 Sherpa 模型，不启动 Sherpa 解码线程。

## 本地测试接口

麦克风 5 秒测试：

```powershell
Invoke-RestMethod -Method Post http://127.0.0.1:18081/local-api/audio/test
```

Sherpa WAV 文件识别测试仅在 `provider=SHERPA_ONNX` 且模型就绪时使用：

```powershell
curl.exe -F "file=@D:\test.wav" http://127.0.0.1:18081/local-api/asr/test/file
```

WAV 测试不进入 `CommandMatcher`，不触发 Renderer，不保存音频文件。

## 构建和验证

```powershell
cd D:\git-ai-live\ai-live-edge-agent
.\mvnw.cmd test
.\mvnw.cmd package
```

## 当前边界

当前阶段不实现 FunASR 服务器连接、云端注册、心跳、素材下发、抖音数据接入、AI 对话、OBS WebSocket、支付、授权、自动更新或 Electron 客户端。

本项目不会保存用户录音，不上传用户音频，不在配置或日志中输出腾讯云密钥。
# AI Live Edge Agent

正式项目路径：

```powershell
D:\git-ai-live\ai-live-edge-agent
```

当前版本：`0.5.0-A-SNAPSHOT`

## 当前默认模式

默认使用腾讯云实时 ASR：

```yaml
ai-live.asr.provider: TENCENT
ai-live.asr.tencent.enabled: true
ai-live.asr.sherpa.enabled: false
```

腾讯云凭据只通过环境变量提供：

```powershell
setx TENCENT_ASR_APP_ID "你的 AppId"
setx TENCENT_ASR_SECRET_ID "你的 SecretId"
setx TENCENT_ASR_SECRET_KEY "你的 SecretKey"
```

项目文件不包含真实凭据。缺少环境变量时，Spring Boot 仍会启动，ASR 状态为 `MISCONFIGURED`，不会无限重连。

## 动作适配器

V0.5-A 引入可插拔动作适配器：

```text
CommandMatcher
-> ActionDispatcher
-> ActionExecutorRegistry
   -> MEDIA
   -> VTUBE_STUDIO
```

当前实现：

- `MEDIA`：`SHOW_IMAGE`、`PLAY_GIF`、`PLAY_WEBM`、`HIDE`、`CLEAR`
- `VTUBE_STUDIO`：`TRIGGER_HOTKEY`

旧 `commands.json` 没有 `target` 时默认走 `MEDIA`。Renderer 未连接时，MEDIA 返回 `TARGET_UNAVAILABLE / RENDERER_NOT_CONNECTED`，不会伪装成执行成功。

详细架构见 [docs/action-adapter-architecture.md](docs/action-adapter-architecture.md)。

## VTube Studio

默认连接：

```text
ws://127.0.0.1:8001
```

V0.5-A 只允许连接 loopback 地址：`127.0.0.1`、`localhost`、`::1`。

首次使用步骤：

1. 启动 VTube Studio。
2. 加载一个 Live2D 模型。
3. 在 VTube Studio 设置中开启 `Allow Plugin API access`。
4. 打开 Console：`http://127.0.0.1:18081/console/index.html`。
5. 点击“连接 VTube Studio”。
6. 点击“授权 VTube Studio”。
7. 在 VTube Studio 弹窗中点击 `Allow`。
8. 授权成功后 Console 会显示模型和 Hotkey 列表。

Token 保存在本地运行数据目录 `data/tokens/`，该目录已加入 `.gitignore`。Token 不写入 `application.yml`，不输出到日志，不通过 Runtime API 或 Console API 返回。

## 启动

```powershell
cd D:\git-ai-live\ai-live-edge-agent
.\mvnw.cmd spring-boot:run
```

或：

```powershell
.\mvnw.cmd package
java -jar target\ai-live-edge-agent-0.5.0-A-SNAPSHOT.jar
```

## 测试

```powershell
.\mvnw.cmd test
.\mvnw.cmd package
```

## 当前边界

- Sherpa-ONNX 保留为可选离线模式。
- FunASR 保持关闭和 `UNAVAILABLE`，不实现服务器连接。
- 本阶段不实现 Warudo、OBS、HTTP/Webhook、Composite、腾讯云热词、安装包、云端授权或支付。

## Windows Desktop Shell 与安装程序

Windows 用户入口现采用 `.NET 8 + WPF + Microsoft WebView2`。Desktop 只负责 Agent 生命周期和承载现有 Console，不复制 ASR、素材或动作业务 UI。

```text
AI Live Edge.exe
→ 检查 /local-api/runtime
→ 必要时使用安装目录内 Java 17 Runtime 后台启动 Agent
→ 在 WebView2 内打开 /console/index.html
```

Desktop 不打开外部浏览器。OBS 仍使用：

```text
http://127.0.0.1:18081/renderer/index.html
```

构建入口：

```powershell
.\scripts\build-desktop.ps1
.\scripts\assemble-windows-dist.ps1 -JavaRuntimePath ... -AgentJarPath ... -DesktopPublishPath ...
.\scripts\build-installer.ps1 -IsccPath ...
```

详细说明见 [docs/windows-desktop-packaging.md](docs/windows-desktop-packaging.md)。
