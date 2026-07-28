# AI Live Edge Agent

Windows 本地 Edge Agent，运行在主播电脑上。V0.2 实现本地闭环：

麦克风 -> 腾讯云实时 ASR -> 口令匹配 -> 动作派发 -> WebSocket -> 本地 Renderer -> PNG/JPG/GIF/WebP/WebM 展示。

本项目不包含云端后台、Vue 管理端、抖音数据、AI 对话、OBS WebSocket 控制、授权、支付或自动更新。

## 项目路径

正式维护路径：

```text
D:\git-ai-live\ai-live-edge-agent
```

旧的 `ai-live-controller` 仅作为第一阶段来源，不再作为正式 Agent 继续开发。

## 架构

```text
AudioDeviceService / MicrophoneCaptureService
  -> SpeechRecognitionProvider
  -> CommandMatcher
  -> ActionDispatcher
  -> ActionExecutor
  -> RendererWebSocketGateway
  -> /renderer/index.html
```

动作执行规则集中在 `ActionExecutionCoordinator`。

## 启动

```powershell
cd D:\git-ai-live\ai-live-edge-agent
.\mvnw.cmd spring-boot:run
```

默认监听：

```text
http://127.0.0.1:18081
```

默认不会监听 `0.0.0.0`。

关闭麦克风和 ASR 自动启动：

```powershell
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.arguments=--ai-live.asr.auto-start=false"
```

## 腾讯云 ASR

先在腾讯云控制台开通语音识别 ASR，并创建 API 密钥。真实凭据只能放环境变量，不要写入代码、配置、README 或测试。

PowerShell 临时设置：

```powershell
$env:TENCENT_ASR_APP_ID="你的AppId"
$env:TENCENT_ASR_SECRET_ID="你的SecretId"
$env:TENCENT_ASR_SECRET_KEY="你的SecretKey"
```

## 麦克风选择

启动时会列出支持 16k PCM 的麦克风。默认使用系统默认麦克风。

指定设备名称关键字：

```yaml
ai-live:
  asr:
    microphone-name: "麦克风名称的一部分"
```

## Renderer

页面地址：

```text
http://127.0.0.1:18081/renderer/index.html
```

WebSocket：

```text
ws://127.0.0.1:18081/ws/renderer
```

URL 参数：

```text
http://127.0.0.1:18081/renderer/index.html?width=1920&height=1080&status=1
```

背景默认透明。`status=1` 时显示连接状态；不传时状态信息默认隐藏。

## OBS 浏览器源

1. 添加“浏览器源”。
2. URL 填写 `http://127.0.0.1:18081/renderer/index.html`。
3. 宽高推荐 `1920 x 1080`。
4. FPS 建议 30，动效较多时可用 60。
5. 启用透明背景，避免给浏览器源设置不透明背景色。

## 素材目录

默认目录：

```text
D:\git-ai-live\ai-live-edge-agent\data\assets
```

配置：

```yaml
ai-live:
  assets:
    root-path: data/assets
```

示例：

```text
data/assets/
  heart.png
  welcome.gif
  wave.webm
  default.png
```

动作配置只能引用素材根目录下文件。程序会拒绝 `../` 路径穿越和不支持的格式，不会把本地绝对路径发送给浏览器。

## commands.json

兼容旧格式：

```json
{
  "code": "heart",
  "name": "比心",
  "keywords": ["比心"],
  "cooldownMs": 3000,
  "priority": 100,
  "enabled": true
}
```

新格式：

```json
{
  "actionCode": "heart",
  "actionName": "比心",
  "keywords": ["比心"],
  "cooldownMs": 3000,
  "priority": 100,
  "enabled": true,
  "actionType": "SHOW_IMAGE",
  "assetPath": "heart.png",
  "durationMs": 5000,
  "loop": false,
  "transition": "FADE"
}
```

动作类型：

- `SHOW_IMAGE`
- `PLAY_GIF`
- `PLAY_WEBM`
- `HIDE`
- `CLEAR`

## 本地测试接口

仅允许本机访问，可通过 `ai-live.local-api.enabled=false` 关闭。

```powershell
Invoke-RestMethod -Method Get  http://127.0.0.1:18081/local-api/health
Invoke-RestMethod -Method Get  http://127.0.0.1:18081/local-api/actions
Invoke-RestMethod -Method Post http://127.0.0.1:18081/local-api/actions/test -ContentType "application/json" -Body '{"actionCode":"heart"}'
Invoke-RestMethod -Method Post http://127.0.0.1:18081/local-api/actions/clear
```

## 测试与打包

```powershell
.\mvnw.cmd test
.\mvnw.cmd package
```

## 常见问题

- 麦克风打不开：检查 Windows 隐私权限、设备是否被其他软件占用、`microphone-name` 是否匹配。
- 腾讯云连接失败：检查三个环境变量、ASR 服务是否开通、网络是否可访问腾讯云。
- Renderer 连接不上：确认服务已启动，端口为 `18081`，页面地址是 `127.0.0.1`。
- OBS 不显示透明背景：检查浏览器源是否启用透明背景，Renderer 页面本身是透明的。
- WebM 不播放：确认文件扩展名为 `.webm`，编码可被浏览器支持，视频默认静音自动播放。
- 素材路径错误：只填写相对 `data/assets` 的路径，不要写绝对路径或 `../`。
- 端口被占用：修改 `server.port`，或关闭占用 `18081` 的进程。

## V0.2 边界

已实现本地 Renderer 和动作可视化闭环。仍不实现 OBS 控制、抖音直播数据、AI 对话、云端素材同步、授权和支付。
