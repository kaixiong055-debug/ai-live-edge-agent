# Windows Desktop Shell 实施报告

## 1. 正式项目路径

```text
D:\git-ai-live\ai-live-edge-agent
```

只修改该项目，没有修改 `ai-live-controller`、`ai-live-server`、`ai-live-ui` 或其他历史目录。

## 2. Desktop 技术栈

- .NET 8
- WPF
- Microsoft WebView2 `1.0.4078.44`
- Windows x64
- Self-contained publish

## 3. Desktop 工程路径

```text
desktop\AiLiveEdge.Desktop.sln
desktop\src\AiLiveEdge.Desktop\AiLiveEdge.Desktop.csproj
```

## 4. 新增文件

```text
desktop\AiLiveEdge.Desktop.sln
desktop\Directory.Build.props
desktop\src\AiLiveEdge.Desktop\AiLiveEdge.Desktop.csproj
desktop\src\AiLiveEdge.Desktop\App.xaml
desktop\src\AiLiveEdge.Desktop\App.xaml.cs
desktop\src\AiLiveEdge.Desktop\MainWindow.xaml
desktop\src\AiLiveEdge.Desktop\MainWindow.xaml.cs
desktop\src\AiLiveEdge.Desktop\CloseAgentDialog.xaml
desktop\src\AiLiveEdge.Desktop\CloseAgentDialog.xaml.cs
desktop\src\AiLiveEdge.Desktop\AgentProcessManager.cs
desktop\src\AiLiveEdge.Desktop\AgentHealthChecker.cs
desktop\src\AiLiveEdge.Desktop\AppPaths.cs
desktop\src\AiLiveEdge.Desktop\SingleInstanceGuard.cs
desktop\src\AiLiveEdge.Desktop\DesktopLogger.cs
desktop\src\AiLiveEdge.Desktop\Models\AgentHealthResult.cs
desktop\src\AiLiveEdge.Desktop\Models\AgentStartResult.cs
desktop\src\AiLiveEdge.Desktop\Models\DistributionVersionInfo.cs
desktop\src\AiLiveEdge.Desktop\app.manifest
src\main\java\cn\ai\live\edgeagent\config\AiLivePathResolver.java
installer\AI-Live-Edge.iss
scripts\build-desktop.ps1
scripts\assemble-windows-dist.ps1
scripts\build-installer.ps1
docs\windows-desktop-packaging.md
docs\windows-desktop-implementation-report.md
```

## 5. 修改文件

```text
.gitignore
README.md
commands.json
src\main\resources\application.yml
src\main\java\cn\ai\live\edgeagent\config\AiLiveProperties.java
src\main\java\cn\ai\live\edgeagent\assets\AssetService.java
src\main\java\cn\ai\live\edgeagent\command\CommandConfigLoader.java
src\main\java\cn\ai\live\edgeagent\command\CommandConfigManager.java
src\main\java\cn\ai\live\edgeagent\asr\SherpaOnnxSpeechRecognitionProvider.java
src\main\java\cn\ai\live\edgeagent\integrations\vtubestudio\VTubeStudioTokenStore.java
src\main\java\cn\ai\live\edgeagent\runtime\RuntimeStatusService.java
```

`commands.json` 只移除了 UTF-8 BOM，没有修改命令内容。

## 6. Desktop 入口 EXE 名称

```text
AI Live Edge.exe
```

## 7. Desktop 默认窗口尺寸

- 默认：`1280 × 820`
- 最小：`1000 × 650`

## 8. WebView2 加载地址

```text
http://127.0.0.1:18081/console/index.html
```

## 9. 是否会打开外部浏览器

不会。

没有使用：

- `Process.Start(URL)`
- `cmd /c start`
- `start http://...`
- `UseShellExecute=true` 打开 URL
- `Desktop.browse`

## 10. 外部导航拦截方式

监听：

- `NavigationStarting`
- `NavigationCompleted`
- `NewWindowRequested`
- `ProcessFailed`

只允许：

- `http://127.0.0.1:18081/`
- `http://localhost:18081/`
- `about:blank`

其他协议、主机和端口全部阻止。`target="_blank"` 的 localhost 地址改为当前 WebView 打开，公网地址不打开。

## 11. Agent 检测方式

请求：

```text
http://127.0.0.1:18081/local-api/runtime
```

同时校验：

- HTTP 成功
- `serviceStatus=UP`
- `applicationVersion` 非空
- `serverPort=18081`

HTTP 不可用时再用 TCP 检查 18081 是否被非 HTTP/其他程序占用。

## 12. Agent 启动方式

优先：

```text
agent\runtime\bin\javaw.exe
```

回退：

```text
agent\runtime\bin\java.exe
```

执行：

```text
javaw.exe -jar agent\ai-live-edge-agent.jar
```

同时传入 localhost、18081、用户数据目录、commands、assets、Token、日志和 `auto-connect=false` 参数。

## 13. Java Runtime 路径规则

Java 只能来自安装目录：

```text
<InstallPath>\agent\runtime\bin\javaw.exe
<InstallPath>\agent\runtime\bin\java.exe
```

不读取注册表 Java，不搜索 PATH，不执行任意用户提供的程序路径。

## 14. 是否依赖客户电脑安装 Java

不依赖。

安装包携带私有 Java 17 Runtime。

## 15. 是否显示命令行黑框

不显示。

使用：

```text
UseShellExecute=false
CreateNoWindow=true
WindowStyle=Hidden
```

并优先使用 `javaw.exe`。

## 16. Agent 重复启动防护

- 先验证 Runtime API。
- 已运行时不重复启动。
- 18081 被其他服务占用时不启动。
- Desktop 使用全局单实例锁，避免同一机器重复触发启动流程。
- 每次启动后记录 PID 和启动时间。

## 17. Desktop 单实例实现

使用 Named Mutex：

```text
Global\AI-Live-Edge-Desktop
```

并使用命名事件唤醒已有窗口。若系统不允许创建 Global 对象，则回退到当前会话的 Local 对象。

第二次启动不会创建第二个主窗口，会尝试激活已有窗口并显示“AI Live Edge 已经在运行”。

## 18. 端口冲突处理

如果 18081 可连接，但 Runtime API 结构不符合本项目，显示：

```text
端口 18081 已被其他程序占用
```

不会继续启动第二个 Agent。

## 19. Agent 启动超时处理

- 超时：30 秒
- 轮询：400 毫秒

超时后只停止本次 Desktop 启动并记录的进程，并显示结构化错误。

## 20. 用户数据目录

```text
%LOCALAPPDATA%\AI Live Edge
```

子目录：

```text
config
assets
logs
tokens
cache
runtime
desktop
```

## 21. 日志目录

```text
%LOCALAPPDATA%\AI Live Edge\logs\desktop.log
%LOCALAPPDATA%\AI Live Edge\logs\agent.log
```

Desktop 日志达到 5 MB 后轮转，保留 3 份历史文件。

## 22. 腾讯云环境变量处理

Desktop 启动 Agent 时继承当前用户环境变量：

```text
TENCENT_ASR_APP_ID
TENCENT_ASR_SECRET_ID
TENCENT_ASR_SECRET_KEY
```

没有把凭据写入安装目录、启动元数据或 Desktop 日志。

原 `application.yml` 中不安全的默认凭据已移除，现在只接受环境变量，缺失时为空。

## 23. ASR 启动后是否保持 STOPPED

保持。

Desktop 显式传入：

```text
--ai-live.asr.auto-connect=false
```

没有改动用户手动“连接 ASR”的业务流程。

## 24. 关闭窗口处理

关闭时显示自定义三选项：

- 继续后台运行（默认）
- 退出并停止 Agent
- 取消

继续后台运行时只关闭 Desktop，Agent、OBS Renderer、MEDIA、VTube Studio 和已连接 ASR 继续运行。

停止 Agent 时只处理通过 Desktop PID 文件确认的进程。

## 25. 安装目录

```text
%LOCALAPPDATA%\Programs\AI Live Edge
```

per-user 安装，不要求管理员权限。

## 26. 安装程序名称

```text
AI-Live-Edge-Setup.exe
```

## 27. 桌面快捷方式

创建：

```text
AI Live Edge
```

## 28. 开始菜单入口

创建：

- AI Live Edge
- 卸载 AI Live Edge

## 29. WebView2 Runtime 处理

安装器检测 WebView2 Runtime 注册表 `pv` 值。

- 已安装：跳过。
- 未安装且包含受信任安装器：执行 `/silent /install`。
- 安装失败：显示明确错误和退出代码。
- 未提供安装器：不在线下载，Desktop 显示中文缺失提示并写日志。

## 30. build-desktop.ps1 使用方法

```powershell
.\scripts\build-desktop.ps1
```

或：

```powershell
.\scripts\build-desktop.ps1 `
  -Configuration Release `
  -RuntimeIdentifier win-x64 `
  -OutputPath "D:\build\desktop-publish"
```

## 31. assemble-windows-dist.ps1 使用方法

```powershell
.\scripts\assemble-windows-dist.ps1 `
  -JavaRuntimePath "D:\runtime\jdk-17" `
  -AgentJarPath ".\target\ai-live-edge-agent-0.5.0-A-SNAPSHOT.jar" `
  -DesktopPublishPath ".\.build\desktop-publish" `
  -OutputPath ".\dist\AI Live Edge"
```

可选参数：

```text
-WebView2BootstrapperPath
-WebView2OfflineInstallerPath
-DefaultAssetsPath
-SherpaNativePath
-SherpaModelPath
```

## 32. build-installer.ps1 使用方法

```powershell
.\scripts\build-installer.ps1 `
  -DistPath ".\dist\AI Live Edge" `
  -IsccPath "C:\Program Files (x86)\Inno Setup 6\ISCC.exe"
```

也支持环境变量：

```text
INNO_SETUP_ISCC
```

## 33. 是否修改 MEDIA

没有修改 MEDIA Adapter、Renderer 动作协议或执行逻辑。

## 34. 是否修改 VTube Studio Adapter

没有修改 VTube Studio WebSocket、授权、模型、Hotkey 或动作执行逻辑。

只把 Token 文件路径改为可配置，使正式安装时能够写入用户 `tokens` 目录。

## 35. 是否修改 Server/UI

没有修改云端 Server、云端 UI 或其他项目。

现有 Console 页面没有重做，仍是唯一业务控制界面。

## 36. 未验证内容

按照任务限制，本轮没有执行：

- Maven test/package
- dotnet test/build/publish
- Inno Setup 编译
- 真实安装/卸载
- 腾讯云连接
- OBS 测试
- VTube Studio 测试
- 长时间运行验证

只执行了 XML/XAML、YAML、JSON、括号结构、必需文件、敏感信息和生成目录静态检查。

## 37. 手动验证步骤

完整清单见：

```text
docs\windows-desktop-packaging.md
```

核心顺序：

1. Maven package 生成 JAR。
2. 执行 `build-desktop.ps1`。
3. 执行 `assemble-windows-dist.ps1`。
4. 直接验证分发目录中的 Desktop。
5. 执行 `build-installer.ps1`。
6. 验证安装、快捷方式、WebView2、关闭选项和卸载保留数据。

## 38. 已知风险

- Agent 尚无专用安全 shutdown API，因此严格校验 PID 后最终可能需要终止该进程树。
- 安装包和 EXE 尚未代码签名，SmartScreen 可能提示。
- WebView2 安装器必须由维护人员从 Microsoft 官方渠道准备，本项目不自动下载。
- 真实 Windows x64、WebView2、Inno Setup 和 Java Runtime 组合尚未执行编译验证。
- 卸载前若 Agent 仍运行，可能需要先从 Desktop 选择“退出并停止 Agent”。

## 39. 下一阶段建议

下一轮单独执行 Windows 验证与真实打包：

1. Maven package。
2. Desktop restore/publish。
3. jlink 私有 Java Runtime。
4. 分发目录安全审计。
5. Inno Setup 编译。
6. 干净 Windows 10/11 虚拟机安装验证。
7. OBS Renderer 验证。
8. ASR 启动保持 STOPPED 验证。
9. 完成后再考虑系统托盘和安全的本地 shutdown 机制。
