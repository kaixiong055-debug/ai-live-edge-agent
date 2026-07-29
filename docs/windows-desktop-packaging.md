# AI Live Edge Windows Desktop 与安装打包

## 1. 范围

本阶段只提供 Windows Desktop Shell、Agent 私有 Runtime 启动入口、分发目录组装脚本和 Inno Setup 安装配置。

没有引入 Electron、Tauri、JavaFX、Windows Service、系统托盘、自动更新、许可证或云端功能。

Desktop 技术栈：

- .NET 8
- WPF
- Microsoft WebView2
- Windows x64
- Self-contained publish

安装器：Inno Setup 6，per-user 安装，不要求管理员权限。

## 2. 运行结构

正式分发目录：

```text
AI Live Edge
├─ AI Live Edge.exe
├─ Microsoft.Web.WebView2.*
├─ runtimes
├─ agent
│  ├─ ai-live-edge-agent.jar
│  ├─ runtime
│  │  └─ bin
│  │     ├─ java.exe
│  │     └─ javaw.exe
│  └─ resources                 # 可选 Sherpa 资源
├─ resources
│  ├─ defaults
│  │  └─ commands.json
│  ├─ default-assets            # 可选
│  └─ redist
│     └─ webview2-installer.exe # 可选
└─ version.json
```

用户数据目录：

```text
%LOCALAPPDATA%\AI Live Edge
├─ config
│  └─ commands.json
├─ assets
├─ logs
│  ├─ desktop.log
│  └─ agent.log
├─ tokens
├─ cache
├─ runtime
└─ desktop
   └─ webview2
```

程序不会把用户素材、Token、日志或可写配置放进安装目录。

## 3. Desktop 启动流程

```text
Desktop 启动
→ 获取 Named Mutex 单实例锁
→ 初始化 %LOCALAPPDATA% 用户目录
→ 请求 http://127.0.0.1:18081/local-api/runtime
→ 验证 serviceStatus、applicationVersion、serverPort
→ Agent 未运行时使用安装目录内 javaw.exe 启动 JAR
→ 等待最多 30 秒，每 400ms 检查一次 Runtime API
→ 初始化 WebView2
→ 在窗口内打开 http://127.0.0.1:18081/console/index.html
```

不调用外部 Chrome 或 Edge 浏览器，不使用 `Process.Start(URL)`、`cmd /c start` 或 `UseShellExecute=true` 打开 URL。

WebView2 只允许：

- `http://127.0.0.1:18081/`
- `http://localhost:18081/`
- `about:blank`

`NavigationStarting`、`NewWindowRequested`、`NavigationCompleted` 和 `ProcessFailed` 都有处理。公网 URL 和非 18081 端口会被阻止。

## 4. Agent 启动参数

Desktop 使用安装目录内的：

```text
agent\runtime\bin\javaw.exe
```

启动参数包括：

```text
-Dfile.encoding=UTF-8
-jar agent\ai-live-edge-agent.jar
--server.address=127.0.0.1
--server.port=18081
--ai-live.data-dir=%LOCALAPPDATA%\AI Live Edge
--ai-live.command.config-path=%LOCALAPPDATA%\AI Live Edge\config\commands.json
--ai-live.assets.root-path=%LOCALAPPDATA%\AI Live Edge\assets
--ai-live.integrations.vtube-studio.token-path=%LOCALAPPDATA%\AI Live Edge\tokens\vtube-studio.token
--ai-live.asr.auto-connect=false
--logging.file.name=%LOCALAPPDATA%\AI Live Edge\logs\agent.log
```

客户电脑不需要预装 Java。Desktop 不搜索系统 PATH 中的 Java，也不会回退到客户安装的 JDK/JRE。

当前用户环境变量会正常继承：

- `TENCENT_ASR_APP_ID`
- `TENCENT_ASR_SECRET_ID`
- `TENCENT_ASR_SECRET_KEY`

这些值不会写入安装目录、`version.json` 或 Desktop 日志。

## 5. 构建 Desktop

前置条件：Windows x64、.NET 8 SDK。

```powershell
cd D:\git-ai-live\ai-live-edge-agent
powershell -ExecutionPolicy Bypass -File .\scripts\build-desktop.ps1
```

自定义输出：

```powershell
.\scripts\build-desktop.ps1 `
  -Configuration Release `
  -RuntimeIdentifier win-x64 `
  -OutputPath "D:\build\ai-live-edge-desktop"
```

脚本执行 `dotnet restore` 和 `dotnet publish --self-contained true`，不执行 `dotnet test`。

## 6. 组装 Windows 分发目录

先由 Maven 生成 Agent JAR，再准备 Java 17 JDK 或 Runtime。脚本本身不依赖固定本机路径。

```powershell
.\scripts\assemble-windows-dist.ps1 `
  -JavaRuntimePath "D:\runtime\jdk-17" `
  -AgentJarPath ".\target\ai-live-edge-agent-0.5.0-A-SNAPSHOT.jar" `
  -DesktopPublishPath ".\.build\desktop-publish" `
  -OutputPath ".\dist\AI Live Edge"
```

当 `JavaRuntimePath` 是 JDK 17 时，脚本调用该 JDK 自带的 `jlink` 生成私有 Runtime，不复制 `javac`、`jmods` 和开发工具。传入已经生成的 Runtime 时，脚本直接复制 Runtime。

可选包含 WebView2 安装程序：

```powershell
.\scripts\assemble-windows-dist.ps1 `
  -JavaRuntimePath "D:\runtime\jdk-17" `
  -AgentJarPath ".\target\ai-live-edge-agent-0.5.0-A-SNAPSHOT.jar" `
  -DesktopPublishPath ".\.build\desktop-publish" `
  -WebView2BootstrapperPath "D:\redist\MicrosoftEdgeWebview2Setup.exe"
```

离线安装包使用：

```powershell
-WebView2OfflineInstallerPath "D:\redist\MicrosoftEdgeWebView2RuntimeInstallerX64.exe"
```

两个 WebView2 参数不能同时使用。第三方安装程序不提交到 Git。

可选 Sherpa 资源：

```powershell
-SherpaNativePath ".\runtime\native\windows-x86_64" `
-SherpaModelPath ".\data\models\sherpa-onnx\streaming-paraformer-zh-en"
```

组装脚本只复制明确指定的文件，不复制腾讯云密钥、VTube Studio Token、开发日志、测试输出或 `application-local.yml`。

## 7. 构建安装程序

前置条件：Inno Setup 6。

```powershell
.\scripts\build-installer.ps1 `
  -DistPath ".\dist\AI Live Edge" `
  -IsccPath "C:\Program Files (x86)\Inno Setup 6\ISCC.exe"
```

也可以设置：

```powershell
$env:INNO_SETUP_ISCC="C:\Program Files (x86)\Inno Setup 6\ISCC.exe"
.\scripts\build-installer.ps1
```

输出：

```text
dist\installer\AI-Live-Edge-Setup.exe
```

安装位置：

```text
%LOCALAPPDATA%\Programs\AI Live Edge
```

安装器创建桌面快捷方式、开始菜单入口和卸载入口。安装结束默认勾选启动 AI Live Edge。

## 8. WebView2 Runtime

安装器检查 WebView2 Runtime 注册表 `pv` 值。已安装时跳过；未安装且分发目录包含受信任的 Bootstrapper 或离线安装器时，以 `/silent /install` 执行。

没有提供 Runtime 安装器时不会联网下载未知文件。Desktop 启动时会显示中文缺失提示并写入 `desktop.log`。

## 9. 关闭行为

点击关闭时显示：

- 继续后台运行（默认）
- 退出并停止 Agent
- 取消

“继续后台运行”只关闭 Desktop，Agent 继续服务 OBS Renderer。

“退出并停止 Agent”只处理 Desktop 写入 PID 元数据的 Agent，并同时校验：

- PID
- 进程启动时间
- 实际 `java.exe/javaw.exe` 路径
- Agent JAR 路径
- Runtime API 身份

校验失败时不停止进程，避免误杀其他 Java 程序。

## 10. 卸载

程序文件正常删除。用户数据默认保留。卸载器会询问是否清理：

```text
%LOCALAPPDATA%\AI Live Edge
```

默认选择“否”，不会默认删除客户素材、配置、Token 或日志。

## 11. 手动验证清单

本轮代码阶段不执行以下步骤；真正打包时按顺序手工验证：

1. 执行 Maven package 生成 Agent JAR。
2. 执行 `build-desktop.ps1`。
3. 执行 `assemble-windows-dist.ps1`。
4. 检查分发目录不包含密钥、Token、日志和 JDK 开发工具。
5. 直接运行 `AI Live Edge.exe`。
6. 确认没有命令行黑框。
7. 确认没有打开 Chrome 或 Edge 浏览器窗口。
8. 确认 Console 在 Desktop 窗口内加载。
9. 确认 Renderer 原地址可由 OBS 使用。
10. 确认 Agent 启动后 ASR 与麦克风保持 STOPPED。
11. 双击第二次 Desktop，确认不会创建第二个窗口。
12. 预先占用 18081，确认显示端口冲突且不启动第二个 Agent。
13. 删除/移动私有 Java Runtime，确认显示明确错误。
14. 在无 WebView2 Runtime 环境验证缺失提示。
15. 测试 Console 的 `target=_blank` 与公网链接被阻止。
16. 测试重新启动 Agent 后 Console 自动恢复。
17. 关闭时选择继续后台运行，确认 OBS Renderer 不受影响。
18. 关闭时选择退出并停止 Agent，确认只停止记录的 Agent PID。
19. 编译并运行安装器，确认桌面和开始菜单快捷方式。
20. 卸载时选择保留用户数据，确认 assets/config/tokens/logs 仍存在。

## 12. 已知风险

- 当前 Agent 没有专用本地优雅 shutdown API，Desktop 在严格校验 PID 后先尝试关闭主窗口，超时后终止该进程树。
- WebView2 Runtime 安装器需要在构建机上由维护人员从 Microsoft 官方渠道准备，本项目不自动下载。
- 未进行代码签名，Windows SmartScreen 可能对首次发布的 EXE/安装包显示提示。
- 系统托盘、自动更新、Windows Service 和开机启动均未实现。
