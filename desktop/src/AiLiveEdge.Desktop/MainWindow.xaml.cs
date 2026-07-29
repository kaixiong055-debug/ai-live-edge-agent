using System.ComponentModel;
using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Text.Json;
using System.Windows;
using System.Windows.Interop;
using System.Windows.Threading;
using AiLiveEdge.Desktop.AgentLaunch;
using AiLiveEdge.Desktop.Connection;
using AiLiveEdge.Desktop.LiveOutput;
using AiLiveEdge.Desktop.Models;
using AiLiveEdge.Desktop.Models.Auth;
using AiLiveEdge.Desktop.Renderer;
using AiLiveEdge.Desktop.Services.Auth;
using AiLiveEdge.Desktop.Services.Deployment;
using AiLiveEdge.Desktop.Services.Heartbeat;
using AiLiveEdge.Desktop.Services.Http;
using AiLiveEdge.Desktop.Services.Session;
using AiLiveEdge.Desktop.Services.Settings;
using AiLiveEdge.Desktop.Services.Startup;
using AiLiveEdge.Desktop.Services.Tray;
using AiLiveEdge.Desktop.Services.Versioning;
using Microsoft.Web.WebView2.Core;
using WpfMessageBox = System.Windows.MessageBox;
using WpfMessageBoxButton = System.Windows.MessageBoxButton;
using WpfMessageBoxImage = System.Windows.MessageBoxImage;
using WpfMessageBoxResult = System.Windows.MessageBoxResult;

namespace AiLiveEdge.Desktop;

public partial class MainWindow : Window
{
    private readonly AgentHealthChecker _healthChecker;
    private readonly AgentProcessManager _processManager;
    private readonly AgentConnectionManager _connectionManager;
    private readonly IAuthService _authService;
    private readonly ISessionService _sessionService;
    private readonly IAgentHeartbeatService _heartbeatService;
    private readonly ICloudApiSettingsService _cloudApiSettingsService;
    private readonly IAppSettingsService _appSettingsService;
    private readonly IWindowsStartupService _startupService;
    private readonly ITrayIconService _trayIconService;
    private readonly IAppVersionService _versionService;
    private readonly IDeploymentConfigurationService _deploymentConfigurationService;
    private readonly IRendererManager _rendererManager;
    private readonly ILiveOutputWindowManager _liveOutputWindowManager;
    private readonly SemaphoreSlim _initializeLock = new(1, 1);
    private readonly DispatcherTimer _healthTimer;
    private CoreWebView2Environment? _webViewEnvironment;
    private CancellationTokenSource _lifetimeCancellation = new();
    private bool _webViewInitialized;
    private bool _monitorRunning;
    private bool _allowClose;
    private bool _isExplicitExit;
    private bool _isShuttingDown;
    private bool _closeDialogOpen;
    private bool _trayBroadcastRunning;
    private string? _lastLoggedAsrStatus;
    private string? _lastLoggedRecognition;
    private string? _lastLoggedAsrError;

    public MainWindow(
        AgentHealthChecker healthChecker,
        AgentProcessManager processManager,
        AgentConnectionManager connectionManager,
        IAuthService authService,
        ISessionService sessionService,
        IAgentHeartbeatService heartbeatService,
        ICloudApiSettingsService cloudApiSettingsService,
        IAppSettingsService appSettingsService,
        IWindowsStartupService startupService,
        ITrayIconService trayIconService,
        IAppVersionService versionService,
        IDeploymentConfigurationService deploymentConfigurationService)
    {
        _healthChecker = healthChecker;
        _processManager = processManager;
        _connectionManager = connectionManager;
        _authService = authService;
        _sessionService = sessionService;
        _heartbeatService = heartbeatService;
        _cloudApiSettingsService = cloudApiSettingsService;
        _appSettingsService = appSettingsService;
        _startupService = startupService;
        _trayIconService = trayIconService;
        _versionService = versionService;
        _deploymentConfigurationService = deploymentConfigurationService;
        InitializeComponent();
        _rendererManager = new RendererManager(RendererWebView);
        _liveOutputWindowManager = new LiveOutputWindowManager(this);
        _sessionService.SessionExpired += SessionService_SessionExpired;
        _trayIconService.Initialize(new TrayCallbacks(
            () => Dispatcher.InvokeAsync(() =>
            {
                ActivateExistingWindow();
                return Task.CompletedTask;
            }).Task.Unwrap(),
            () => Dispatcher.InvokeAsync(ToggleBroadcastFromTray).Task.Unwrap(),
            () => Dispatcher.InvokeAsync(async () =>
            {
                await Logout(confirm: true);
                PostAuthState();
            }).Task.Unwrap(),
            () => Dispatcher.InvokeAsync(ExitApplicationFromTrayAsync).Task.Unwrap()));

        _healthTimer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(5) };
        _healthTimer.Tick += HealthTimer_Tick;
        LoadDistributionVersion();
    }

    public void ActivateExistingWindow()
    {
        Dispatcher.InvokeAsync(() =>
        {
            ShowInTaskbar = true;
            if (!IsVisible)
            {
                Show();
            }
            if (WindowState == WindowState.Minimized)
            {
                WindowState = WindowState.Normal;
            }
            Activate();
            Topmost = true;
            Topmost = false;
            Focus();
            var handle = new WindowInteropHelper(this).Handle;
            if (handle != IntPtr.Zero)
            {
                ShowWindow(handle, 9);
                SetForegroundWindow(handle);
            }
        });
    }

    private async void Window_Loaded(object sender, RoutedEventArgs e)
    {
        await InitializeDesktopAsync();
    }

    private async Task InitializeDesktopAsync()
    {
        if (!await _initializeLock.WaitAsync(0))
        {
            return;
        }

        try
        {
            await _cloudApiSettingsService.LoadAsync(_lifetimeCancellation.Token);
            ShowStarting("正在打开 AI Live Edge", "正在载入安全桌面应用界面。");
            await EnsureWebViewAsync();
            ShowConsole();

            var restored = await _authService.RestoreSessionAsync(_lifetimeCancellation.Token);
            if (restored is null)
            {
                PostAuthState("请登录 AI Live Cloud。");
                return;
            }

            await StartAuthenticatedServicesAsync();
            PostAuthState();
        }
        catch (OperationCanceledException)
        {
            // Window is closing.
        }
        catch (WebView2RuntimeNotFoundException ex)
        {
            DesktopLogger.Error("WebView2 Runtime is missing.", ex);
            ShowError("缺少 WebView2 Runtime", "无法打开 AI Live Edge 应用界面。",
                webViewRuntimeMissing: true);
        }
        catch (Exception ex)
        {
            DesktopLogger.Error("Desktop initialization failed.", ex);
            ShowError("本地服务启动失败", ex.Message);
        }
        finally
        {
            _initializeLock.Release();
        }
    }

    private async Task StartAuthenticatedServicesAsync()
    {
        if (_connectionManager.CurrentMode == AgentConnectionMode.Local)
        {
            var progress = new Progress<string>(message =>
            {
                DesktopLogger.Info(message);
                StatusMessage.Text = "正在准备本地服务，请稍候。";
            });

            ShowStarting("正在准备伴播环境", "正在检查本地 AI 服务，请稍候。");
            var result = await _processManager.EnsureAgentRunningAsync(progress, _lifetimeCancellation.Token);
            if (!result.IsSuccess)
            {
                if (result.Status == AgentStartStatus.DevelopmentAgentUnavailable)
                {
                    DesktopLogger.Info(result.Message);
                    _healthTimer.Start();
                    return;
                }
                ShowAgentError(result);
                return;
            }
            ConnectionText.Text = $"本地模式 · Agent {result.AgentVersion ?? "未知版本"}";
            if (_appSettingsService.Current.RendererAutoStart)
            {
                await EnsureRendererAsync();
            }
            if (_liveOutputWindowManager.CurrentStatus.Settings.AutoOpenLiveOutput)
            {
                await _liveOutputWindowManager.OpenAsync(_lifetimeCancellation.Token);
            }
            _healthTimer.Start();
        }
        else
        {
            ConnectionText.Text = "云端模式";
        }

        _heartbeatService.Start(CreateHeartbeatPayload);
        ShowConsole();
    }

    private async Task<HeartbeatStatusPayload> CreateHeartbeatPayload()
    {
        var renderer = _rendererManager.CurrentStatus;
        return await Task.FromResult(new HeartbeatStatusPayload(
            renderer.State.ToString(),
            renderer.ConnectionCount > 0 ? "CONNECTED" : "DISCONNECTED",
            _connectionManager.CurrentMode.ToString().ToUpperInvariant()));
    }

    private async Task EnsureWebViewAsync()
    {
        if (!_webViewInitialized)
        {
            Directory.CreateDirectory(AppPaths.WebView2DataDirectory);
            _webViewEnvironment = await CoreWebView2Environment.CreateAsync(
                browserExecutableFolder: null,
                userDataFolder: AppPaths.WebView2DataDirectory);
            await ConsoleWebView.EnsureCoreWebView2Async(_webViewEnvironment);
            _liveOutputWindowManager.SetWebViewEnvironment(_webViewEnvironment);

            var settings = ConsoleWebView.CoreWebView2.Settings;
#if DEBUG
            settings.AreDevToolsEnabled = true;
#else
            settings.AreDevToolsEnabled = false;
#endif
            settings.AreDefaultContextMenusEnabled = false;
            settings.IsStatusBarEnabled = false;
            settings.AreBrowserAcceleratorKeysEnabled = false;
            settings.IsPasswordAutosaveEnabled = false;
            settings.IsGeneralAutofillEnabled = false;
            ConsoleWebView.AllowExternalDrop = false;

            ConsoleWebView.CoreWebView2.NavigationStarting += CoreWebView2_NavigationStarting;
            ConsoleWebView.CoreWebView2.NavigationCompleted += CoreWebView2_NavigationCompleted;
            ConsoleWebView.CoreWebView2.NewWindowRequested += CoreWebView2_NewWindowRequested;
            ConsoleWebView.CoreWebView2.ProcessFailed += CoreWebView2_ProcessFailed;
            ConsoleWebView.CoreWebView2.WebMessageReceived += CoreWebView2_WebMessageReceived;
            _webViewInitialized = true;
        }

        NavigateToDesktopApp();
    }

    private async Task EnsureRendererAsync()
    {
        if (_connectionManager.CurrentMode != AgentConnectionMode.Local
            || _webViewEnvironment is null)
        {
            return;
        }

        StatusMessage.Text = "正在连接动作引擎 Renderer。";
        var status = await _rendererManager.EnsureStartedAsync(
            _webViewEnvironment,
            _lifetimeCancellation.Token);
        if (status.State == RendererConnectionState.CONNECTED)
        {
            DesktopLogger.Info(
                $"Renderer connected. connections={status.ConnectionCount}");
        }
        else
        {
            DesktopLogger.Error(
                $"Renderer automatic startup did not connect: {status.ErrorMessage}");
        }
    }

    private void NavigateToDesktopApp()
    {
        var uiPath = Path.Combine(
            AppDomain.CurrentDomain.BaseDirectory,
            "app",
            "index.html");
        if (!File.Exists(uiPath))
        {
            throw new FileNotFoundException("Desktop UI entry file is missing.", uiPath);
        }

        ConsoleWebView.Source = new Uri(uiPath);
    }

    private async void CoreWebView2_WebMessageReceived(object? sender, CoreWebView2WebMessageReceivedEventArgs e)
    {
        string? requestId = null;
        string? operationName = null;
        try
        {
            using var message = JsonDocument.Parse(e.WebMessageAsJson);
            var root = message.RootElement;
            requestId = root.GetProperty("id").GetString();
            var operation = root.GetProperty("operation").GetString()
                            ?? throw new InvalidOperationException("Agent operation is required.");
            operationName = operation;
            var payload = root.TryGetProperty("payload", out var payloadElement)
                ? payloadElement.Clone()
                : JsonSerializer.SerializeToElement(new { });

            if (operation == "startAsr")
            {
                DesktopLogger.Info("[ASR] Start clicked");
                DesktopLogger.Info("[ASR] Request start");
            }
            var data = await ExecuteBridgeOperation(operation, payload);
            LogAsrOperation(operation, data);
            if (operation == "startAsr")
            {
                _trayBroadcastRunning = true;
                UpdateTrayState();
            }
            else if (operation == "stopAsr")
            {
                _trayBroadcastRunning = false;
                UpdateTrayState();
            }

            if (operation == "configureConnection"
                && _connectionManager.CurrentMode == AgentConnectionMode.Local)
            {
                var result = await _processManager.EnsureAgentRunningAsync(
                    cancellationToken: _lifetimeCancellation.Token);
                if (!result.IsSuccess)
                {
                    throw new InvalidOperationException(result.Message);
                }
                await EnsureRendererAsync();
            }

            PostWebMessage(new { id = requestId, success = true, data });
        }
        catch (OperationCanceledException) when (_lifetimeCancellation.IsCancellationRequested)
        {
            // Window is closing.
        }
        catch (Exception ex)
        {
            if (operationName is "startAsr" or "stopAsr" or "getAsrStatus")
            {
                DesktopLogger.Error($"[ASR] Error: {ex.Message}", ex);
            }
            DesktopLogger.Error("Desktop Agent bridge request failed.", ex);
            PostWebMessage(new { id = requestId, success = false, error = ex.Message });
        }
    }

    private async Task<JsonElement> ExecuteBridgeOperation(string operation, JsonElement payload)
    {
        if (!IsAuthOperation(operation) && _sessionService.Current is null)
        {
            throw new UnauthorizedAccessException("请先登录 AI Live Cloud。");
        }

        return operation switch
        {
            "getAuthState" => SerializeAuthState(),
            "getAppSettings" => SerializeAppSettings(),
            "updateAppSettings" => await UpdateAppSettings(payload),
            "getAppInfo" => await SerializeAppInfo(),
            "openLogsDirectory" => OpenLogsDirectory(),
            "getRecentErrors" => SerializeRecentErrors(),
            "cleanupExpiredLogs" => CleanupExpiredLogs(),
            "exitApplication" => await ExitApplicationFromWebAsync(),
            "getCloudApiSettings" => SerializeCloudApiSettings(),
            "saveCloudApiSettings" => await SaveCloudApiSettings(payload),
            "login" => await Login(payload),
            "logout" => await Logout(),
            "refreshCurrentLicense" => SerializeLicense(
                await _authService.LoadCurrentLicenseAsync(_lifetimeCancellation.Token)),
            "canStartBroadcast" => await SerializeBroadcastGate(),
            "getRendererStatus" => await GetRendererStatusJson(),
            "getAgentRuntimeMode" => SerializeAgentRuntimeMode(),
            "setAgentRuntimeMode" => await SetAgentRuntimeMode(payload),
            "getLiveOutputStatus" => SerializeLiveOutputStatus(_liveOutputWindowManager.CurrentStatus),
            "openLiveOutput" => SerializeLiveOutputStatus(
                await _liveOutputWindowManager.OpenAsync(_lifetimeCancellation.Token)),
            "closeLiveOutput" => SerializeLiveOutputStatus(
                await _liveOutputWindowManager.CloseAsync(_lifetimeCancellation.Token)),
            "updateLiveOutputSettings" => SerializeLiveOutputStatus(
                await _liveOutputWindowManager.UpdateSettingsAsync(
                    ReadLiveOutputSettings(payload),
                    _lifetimeCancellation.Token)),
            _ => await _connectionManager.Execute(
                operation,
                payload,
                _lifetimeCancellation.Token)
        };
    }

    private static bool IsAuthOperation(string operation) =>
        operation is "getAuthState" or "getCloudApiSettings" or "saveCloudApiSettings" or "login" or "logout"
            or "getAppSettings" or "updateAppSettings" or "getAppInfo" or "openLogsDirectory"
            or "getRecentErrors" or "cleanupExpiredLogs" or "exitApplication";

    private async Task<JsonElement> Login(JsonElement payload)
    {
        if (payload.ValueKind == JsonValueKind.Object && payload.TryGetProperty("baseUrl", out _))
        {
            throw new InvalidOperationException("登录消息不接受云服务地址。");
        }

        var session = await _authService.LoginAsync(
            new LoginRequest(
                ReadRequiredString(payload, "username"),
                ReadRequiredString(payload, "password"),
                ReadOptionalBoolean(payload, "rememberLogin")
                ?? ReadOptionalBoolean(payload, "remember")
                ?? true),
            _lifetimeCancellation.Token);
        await _authService.LoadCurrentLicenseAsync(_lifetimeCancellation.Token);
        await StartAuthenticatedServicesAsync();
        return SerializeAuthState(session);
    }

    private async Task<JsonElement> Logout(bool confirm = true)
    {
        if (confirm)
        {
            var confirmed = WpfMessageBox.Show(
                "确认退出登录吗？本地设备标识和普通设置会保留。",
                "AI Live Edge",
                WpfMessageBoxButton.YesNo,
                WpfMessageBoxImage.Warning);
            if (confirmed != WpfMessageBoxResult.Yes)
            {
                return SerializeAuthState();
            }
        }

        await _heartbeatService.StopAsync(_lifetimeCancellation.Token);
        _healthTimer.Stop();
        await _authService.LogoutAsync(_lifetimeCancellation.Token);
        UpdateTrayState();
        return SerializeAuthState("已退出登录。");
    }

    private async Task<JsonElement> SaveCloudApiSettings(JsonElement payload)
    {
        if (!_deploymentConfigurationService.Current.AllowServerEditing)
        {
            throw new InvalidOperationException("当前部署模式不允许修改云服务地址。");
        }

        var baseUrl = ReadRequiredString(payload, "baseUrl");
        if (_sessionService.Current is not null)
        {
            var confirmed = WpfMessageBox.Show(
                "修改云端 App API 地址会退出当前账号，并停止当前云端心跳。是否继续？",
                "AI Live Edge",
                WpfMessageBoxButton.YesNo,
                WpfMessageBoxImage.Warning);
            if (confirmed != WpfMessageBoxResult.Yes)
            {
                return SerializeCloudApiSettings();
            }

            await _heartbeatService.StopAsync(_lifetimeCancellation.Token);
            _healthTimer.Stop();
            await _authService.LogoutAsync(_lifetimeCancellation.Token);
        }

        var settings = await _cloudApiSettingsService.SaveAsync(baseUrl, _lifetimeCancellation.Token);
        PostAuthState(_sessionService.Current is null ? "请登录 AI Live Cloud。" : null);
        UpdateTrayState();
        return SerializeCloudApiSettings(settings);
    }

    private JsonElement SerializeAppSettings()
    {
        var settings = _appSettingsService.Current;
        return JsonSerializer.SerializeToElement(new
        {
            settingsVersion = settings.SettingsVersion,
            cloudApiBaseUrl = settings.CloudApiBaseUrl,
            deploymentMode = _deploymentConfigurationService.Current.DeploymentMode.ToString(),
            allowServerEditing = _deploymentConfigurationService.Current.AllowServerEditing,
            heartbeatIntervalSeconds = settings.HeartbeatIntervalSeconds,
            closeBehavior = settings.CloseBehavior == AppCloseBehavior.ExitApplication
                ? "EXIT_APPLICATION"
                : "MINIMIZE_TO_TRAY",
            startWithWindows = _startupService.IsEnabled(),
            startMinimized = settings.StartMinimized,
            minimizeToTray = settings.MinimizeToTray,
            showTrayNotification = settings.ShowTrayNotification,
            logRetentionDays = settings.LogRetentionDays,
            rendererAutoStart = settings.RendererAutoStart,
            lastSelectedPage = settings.LastSelectedPage
        });
    }

    private async Task<JsonElement> UpdateAppSettings(JsonElement payload)
    {
        var next = _appSettingsService.Current;
        var startWithWindows = ReadOptionalBoolean(payload, "startWithWindows");
        if (startWithWindows.HasValue)
        {
            _startupService.SetEnabled(startWithWindows.Value);
            next = next with { StartWithWindows = startWithWindows.Value };
        }

        var closeBehavior = ReadOptionalString(payload, "closeBehavior");
        if (!string.IsNullOrWhiteSpace(closeBehavior))
        {
            next = next with
            {
                CloseBehavior = string.Equals(closeBehavior, "EXIT_APPLICATION", StringComparison.OrdinalIgnoreCase)
                    ? AppCloseBehavior.ExitApplication
                    : AppCloseBehavior.MinimizeToTray
            };
        }

        next = next with
        {
            StartMinimized = ReadOptionalBoolean(payload, "startMinimized") ?? next.StartMinimized,
            MinimizeToTray = ReadOptionalBoolean(payload, "minimizeToTray") ?? next.MinimizeToTray,
            ShowTrayNotification = ReadOptionalBoolean(payload, "showTrayNotification") ?? next.ShowTrayNotification,
            RendererAutoStart = ReadOptionalBoolean(payload, "rendererAutoStart") ?? next.RendererAutoStart,
            HeartbeatIntervalSeconds = ReadOptionalInt(payload, "heartbeatIntervalSeconds")
                                       ?? next.HeartbeatIntervalSeconds,
            LogRetentionDays = ReadOptionalInt(payload, "logRetentionDays") ?? next.LogRetentionDays,
            LastSelectedPage = ReadOptionalString(payload, "lastSelectedPage") ?? next.LastSelectedPage
        };

        await _appSettingsService.SaveAsync(next, _lifetimeCancellation.Token);
        DesktopLogger.CleanupExpiredLogs(next.LogRetentionDays);
        return SerializeAppSettings();
    }

    private async Task<JsonElement> SerializeAppInfo()
    {
        var device = _sessionService.Current?.Device?.DeviceCode;
        return await Task.FromResult(JsonSerializer.SerializeToElement(new
        {
            productName = _versionService.ProductName,
            version = _versionService.Version,
            displayVersion = ToDisplayVersion(_versionService.Version),
            installDirectory = AppPaths.InstallDirectory,
            dataDirectory = AppPaths.DataDirectory,
            logsDirectory = AppPaths.LogsDirectory,
            deviceCode = MaskDeviceCode(device),
            account = _sessionService.Current?.User?.Username,
            tenant = _sessionService.Current?.Tenant?.Name
        }));
    }

    private JsonElement OpenLogsDirectory()
    {
        OpenLogsDirectoryCore();
        return JsonSerializer.SerializeToElement(new { opened = true });
    }

    private JsonElement SerializeRecentErrors() =>
        JsonSerializer.SerializeToElement(new
        {
            errors = DesktopLogger.ReadRecentErrors(10)
        });

    private JsonElement CleanupExpiredLogs()
    {
        DesktopLogger.CleanupExpiredLogs(_appSettingsService.Current.LogRetentionDays);
        return JsonSerializer.SerializeToElement(new { cleaned = true });
    }

    private async Task<JsonElement> ExitApplicationFromWebAsync()
    {
        var confirmed = WpfMessageBox.Show(
            "确认退出 AI Live Edge 吗？",
            "AI Live Edge",
            WpfMessageBoxButton.YesNo,
            WpfMessageBoxImage.Warning);
        if (confirmed != WpfMessageBoxResult.Yes)
        {
            return JsonSerializer.SerializeToElement(new { exiting = false });
        }

        await ExitApplicationFromTrayAsync();
        return JsonSerializer.SerializeToElement(new { exiting = true });
    }

    private JsonElement SerializeAgentRuntimeMode(
        bool? connected = null,
        string? message = null) =>
        JsonSerializer.SerializeToElement(new
        {
            mode = _processManager.Mode.ToString().ToUpperInvariant(),
            connected,
            message
        });

    private async Task<JsonElement> SetAgentRuntimeMode(JsonElement payload)
    {
        var modeText = ReadOptionalString(payload, "mode")
                       ?? throw new ArgumentException("Agent runtime mode is required.");
        if (!Enum.TryParse<AgentRuntimeMode>(modeText, true, out var mode))
        {
            throw new ArgumentException("Agent runtime mode must be DEVELOPMENT or PRODUCTION.");
        }

        await _processManager.SetModeAsync(mode, _lifetimeCancellation.Token);
        var result = await _processManager.EnsureAgentRunningAsync(
            cancellationToken: _lifetimeCancellation.Token);
        if (result.IsSuccess)
        {
            if (_appSettingsService.Current.RendererAutoStart)
            {
                await EnsureRendererAsync();
            }
        }
        return SerializeAgentRuntimeMode(result.IsSuccess, result.Message);
    }

    private static JsonElement SerializeLiveOutputStatus(LiveOutputStatus status) =>
        JsonSerializer.SerializeToElement(new
        {
            isOpen = status.IsOpen,
            state = status.State.ToString().ToUpperInvariant(),
            settings = new
            {
                canvasMode = status.Settings.CanvasMode.ToString().ToUpperInvariant(),
                canvasWidth = status.Settings.CanvasWidth,
                canvasHeight = status.Settings.CanvasHeight,
                previewWindowWidth = status.Settings.PreviewWindowWidth,
                previewWindowHeight = status.Settings.PreviewWindowHeight,
                chromaKeyColor = status.Settings.ChromaKeyColor,
                autoOpenLiveOutput = status.Settings.AutoOpenLiveOutput,
                lastWindowLeft = status.Settings.LastWindowLeft,
                lastWindowTop = status.Settings.LastWindowTop
            },
            errorMessage = status.ErrorMessage
        });

    private LiveOutputSettings ReadLiveOutputSettings(JsonElement payload)
    {
        var current = _liveOutputWindowManager.CurrentStatus.Settings;
        var modeText = ReadOptionalString(payload, "canvasMode") ?? current.CanvasMode.ToString();
        if (!Enum.TryParse<LiveOutputCanvasMode>(modeText, true, out var mode))
        {
            throw new ArgumentException("canvasMode must be PORTRAIT, LANDSCAPE or CUSTOM.");
        }

        var width = ReadOptionalInt(payload, "canvasWidth") ?? current.CanvasWidth;
        var height = ReadOptionalInt(payload, "canvasHeight") ?? current.CanvasHeight;
        if (mode == LiveOutputCanvasMode.Portrait)
        {
            width = 1080;
            height = 1920;
        }
        else if (mode == LiveOutputCanvasMode.Landscape)
        {
            width = 1920;
            height = 1080;
        }

        return (current with
        {
            CanvasMode = mode,
            CanvasWidth = width,
            CanvasHeight = height,
            PreviewWindowWidth = ReadOptionalDouble(payload, "previewWindowWidth")
                                 ?? current.PreviewWindowWidth,
            PreviewWindowHeight = ReadOptionalDouble(payload, "previewWindowHeight")
                                  ?? current.PreviewWindowHeight,
            ChromaKeyColor = ReadOptionalString(payload, "chromaKeyColor")
                             ?? current.ChromaKeyColor,
            AutoOpenLiveOutput = ReadOptionalBoolean(payload, "autoOpenLiveOutput")
                                 ?? current.AutoOpenLiveOutput
        }).Normalize();
    }

    private static string? ReadOptionalString(JsonElement payload, string propertyName) =>
        payload.ValueKind == JsonValueKind.Object
        && payload.TryGetProperty(propertyName, out var value)
        && value.ValueKind == JsonValueKind.String
            ? value.GetString()
            : null;

    private static int? ReadOptionalInt(JsonElement payload, string propertyName) =>
        payload.ValueKind == JsonValueKind.Object
        && payload.TryGetProperty(propertyName, out var value)
        && value.TryGetInt32(out var result)
            ? result
            : null;

    private static double? ReadOptionalDouble(JsonElement payload, string propertyName) =>
        payload.ValueKind == JsonValueKind.Object
        && payload.TryGetProperty(propertyName, out var value)
        && value.TryGetDouble(out var result)
            ? result
            : null;

    private static bool? ReadOptionalBoolean(JsonElement payload, string propertyName) =>
        payload.ValueKind == JsonValueKind.Object
        && payload.TryGetProperty(propertyName, out var value)
        && value.ValueKind is JsonValueKind.True or JsonValueKind.False
            ? value.GetBoolean()
            : null;

    private static string ReadRequiredString(JsonElement payload, string propertyName) =>
        ReadOptionalString(payload, propertyName)
        ?? throw new ArgumentException($"Missing required property: {propertyName}");

    private void LogAsrOperation(string operation, JsonElement data)
    {
        if (operation is "startAsr" or "stopAsr" or "getAsrStatus")
        {
            var status = ReadJsonString(data, "asrStatus") ?? "UNKNOWN";
            if (operation is "startAsr" or "stopAsr"
                || !string.Equals(status, _lastLoggedAsrStatus, StringComparison.Ordinal))
            {
                DesktopLogger.Info($"[ASR] Agent status: {status}");
                _lastLoggedAsrStatus = status;
            }
        }

        if (operation != "getRuntimeStatus")
        {
            return;
        }

        var runtimeStatus = ReadJsonString(data, "asrStatus");
        if (!string.IsNullOrWhiteSpace(runtimeStatus)
            && !string.Equals(runtimeStatus, _lastLoggedAsrStatus, StringComparison.Ordinal))
        {
            DesktopLogger.Info($"[ASR] Agent status: {runtimeStatus}");
            _lastLoggedAsrStatus = runtimeStatus;
        }

        var recognized = ReadJsonString(data, "asrLastFinalText");
        if (!string.IsNullOrWhiteSpace(recognized)
            && !string.Equals(recognized, _lastLoggedRecognition, StringComparison.Ordinal))
        {
            DesktopLogger.Info($"[ASR] Recognized: {recognized}");
            _lastLoggedRecognition = recognized;
        }

        var error = ReadJsonString(data, "asrLastError");
        if (!string.IsNullOrWhiteSpace(error)
            && !string.Equals(error, _lastLoggedAsrError, StringComparison.Ordinal))
        {
            DesktopLogger.Error($"[ASR] Error: {error}");
            _lastLoggedAsrError = error;
        }
    }

    private static string? ReadJsonString(JsonElement element, string propertyName)
    {
        return element.ValueKind == JsonValueKind.Object
               && element.TryGetProperty(propertyName, out var value)
               && value.ValueKind == JsonValueKind.String
            ? value.GetString()
            : null;
    }

    private void PostWebMessage(object message)
    {
        ConsoleWebView.CoreWebView2.PostWebMessageAsJson(JsonSerializer.Serialize(message));
    }

    private void PostAuthState(string? message = null)
    {
        UpdateTrayState();
        if (!_webViewInitialized || ConsoleWebView.CoreWebView2 is null)
        {
            return;
        }
        PostWebMessage(new
        {
            type = "authStateChanged",
            data = CreateAuthState(message)
        });
    }

    private void UpdateTrayState()
    {
        var session = _sessionService.Current;
        var licenseLabel = session?.License?.Status switch
        {
            1 => "授权有效",
            2 => "授权暂停",
            4 => "授权到期",
            _ => session is null ? "未登录" : "授权异常"
        };
        _trayIconService.Update(new TrayState(
            session is not null,
            CanStartBroadcast(session),
            _trayBroadcastRunning,
            session is null
                ? "未登录"
                : $"{session.User?.Username ?? "已登录"} · {licenseLabel} · {_heartbeatService.CloudStatus}"));
    }

    private JsonElement SerializeAuthState(string? message = null)
    {
        UpdateTrayState();
        return JsonSerializer.SerializeToElement(CreateAuthState(message));
    }

    private JsonElement SerializeAuthState(AuthSession? session, string? message = null)
    {
        UpdateTrayState();
        return JsonSerializer.SerializeToElement(CreateAuthState(message, session));
    }

    private object CreateAuthState(string? message = null, AuthSession? session = null)
    {
        session ??= _sessionService.Current;
        return new
        {
            authenticated = session is not null,
            message,
            cloudStatus = _heartbeatService.CloudStatus,
            user = session?.User is null ? null : new
            {
                id = session.User.Id,
                username = session.User.Username,
                nickname = session.User.Nickname,
                avatar = session.User.Avatar
            },
            tenant = session?.Tenant is null ? null : new
            {
                id = session.Tenant.Id,
                name = session.Tenant.Name
            },
            device = session?.Device is null ? null : new
            {
                id = session.Device.Id,
                deviceCode = session.Device.DeviceCode,
                deviceName = session.Device.DeviceName,
                status = session.Device.Status
            },
            license = CreateLicenseState(session?.License),
            capabilities = session?.Capabilities is null ? null : new
            {
                agentRun = session.Capabilities.AgentRun,
                configRead = session.Capabilities.ConfigRead,
                platformManagedCredential = session.Capabilities.PlatformManagedCredential
            },
            canStartBroadcast = CanStartBroadcast(session),
            startDisabledReason = StartDisabledReason(session)
        };
    }

    private JsonElement SerializeCloudApiSettings(CloudApiSettings? settings = null)
    {
        settings ??= _cloudApiSettingsService.Current;
        return JsonSerializer.SerializeToElement(new
        {
            baseUrl = settings.BaseUrl,
            deploymentMode = _deploymentConfigurationService.Current.DeploymentMode.ToString(),
            allowServerEditing = _deploymentConfigurationService.Current.AllowServerEditing,
            timeoutSeconds = settings.TimeoutSeconds,
            heartbeatIntervalSeconds = settings.HeartbeatIntervalSeconds
        });
    }

    private JsonElement SerializeLicense(CurrentLicense? license) =>
        JsonSerializer.SerializeToElement(CreateLicenseState(license));

    private static object? CreateLicenseState(CurrentLicense? license) =>
        license is null
            ? null
            : new
            {
                licenseType = license.LicenseType,
                credentialMode = license.CredentialMode,
                status = license.Status,
                validFrom = license.ValidFrom,
                validUntil = license.ValidUntil,
                maxDeviceCount = license.MaxDeviceCount,
                maxConcurrentAgentCount = license.MaxConcurrentAgentCount,
                offlineGraceHours = license.OfflineGraceHours,
                licenseTypeLabel = license.LicenseType switch
                {
                    1 => "永久买断",
                    2 => "订阅授权",
                    _ => "未知授权"
                },
                credentialModeLabel = license.CredentialMode switch
                {
                    1 => "客户自管",
                    2 => "平台托管",
                    _ => "未知模式"
                },
                statusLabel = license.Status switch
                {
                    0 => "未开通",
                    1 => "正常授权",
                    2 => "已暂停",
                    3 => "已撤销",
                    4 => "已到期",
                    _ => "授权异常"
                }
            };

    private async Task<JsonElement> SerializeBroadcastGate()
    {
        await _authService.RefreshCurrentSessionAsync(_lifetimeCancellation.Token);
        return JsonSerializer.SerializeToElement(new
        {
            allowed = CanStartBroadcast(_sessionService.Current),
            reason = StartDisabledReason(_sessionService.Current)
        });
    }

    private static bool CanStartBroadcast(AuthSession? session) =>
        session?.License?.IsUsable == true
        && session.Capabilities?.AgentRun == true
        && session.Device?.Status == 1
        && session.AccessTokenExpiresAt > DateTimeOffset.UtcNow;

    private static string? StartDisabledReason(AuthSession? session)
    {
        if (session is null) return "请先登录。";
        if (session.AccessTokenExpiresAt <= DateTimeOffset.UtcNow) return "当前会话已失效。";
        if (session.Device?.Status != 1) return "当前设备不可用。";
        if (session.License?.IsUsable != true) return "AI 伴播授权当前不可用。";
        if (session.Capabilities?.AgentRun != true) return "账号没有运行 Agent 的权限。";
        return null;
    }

    private static string? MaskDeviceCode(string? deviceCode)
    {
        if (string.IsNullOrWhiteSpace(deviceCode))
        {
            return null;
        }
        return deviceCode.Length <= 8
            ? "****"
            : $"{deviceCode[..4]}****{deviceCode[^4..]}";
    }

    private static string ToDisplayVersion(string version)
    {
        var clean = version.Split('+', StringSplitOptions.RemoveEmptyEntries)[0];
        var parts = clean.Split('.', StringSplitOptions.RemoveEmptyEntries);
        return parts.Length >= 3
            ? $"V{parts[0]}.{parts[1]}.{parts[2]}"
            : $"V{clean}";
    }

    private async Task<JsonElement> GetRendererStatusJson()
    {
        if (_connectionManager.CurrentMode != AgentConnectionMode.Local)
        {
            return JsonSerializer.SerializeToElement(new
            {
                state = RendererConnectionState.DISCONNECTED.ToString(),
                pageAvailable = false,
                connectionCount = 0,
                lastAction = (string?)null,
                lastUpdatedAt = DateTimeOffset.UtcNow,
                errorMessage = "Renderer is managed by the local Desktop mode."
            });
        }

        var status = await _rendererManager.GetStatusAsync(_lifetimeCancellation.Token);
        return JsonSerializer.SerializeToElement(new
        {
            state = status.State.ToString(),
            status.PageAvailable,
            status.ConnectionCount,
            status.LastAction,
            status.LastUpdatedAt,
            status.ErrorMessage
        });
    }

    private void CoreWebView2_NavigationStarting(object? sender, CoreWebView2NavigationStartingEventArgs e)
    {
        if (IsAllowedNavigation(e.Uri))
        {
            return;
        }
        e.Cancel = true;
        DesktopLogger.Info("Blocked WebView2 navigation to a non-local URL.");
    }

    private void CoreWebView2_NewWindowRequested(object? sender, CoreWebView2NewWindowRequestedEventArgs e)
    {
        e.Handled = true;
        if (IsAllowedNavigation(e.Uri))
        {
            ConsoleWebView.CoreWebView2.Navigate(e.Uri);
        }
        else
        {
            DesktopLogger.Info("Blocked WebView2 new-window request to a non-local URL.");
        }
    }

    private void CoreWebView2_NavigationCompleted(object? sender, CoreWebView2NavigationCompletedEventArgs e)
    {
        if (!e.IsSuccess)
        {
            ShowError("应用界面加载失败", $"WebView2 错误：{e.WebErrorStatus}");
        }
    }

    private void CoreWebView2_ProcessFailed(object? sender, CoreWebView2ProcessFailedEventArgs e)
    {
        DesktopLogger.Error($"WebView2 process failed: {e.ProcessFailedKind}");
        Dispatcher.InvokeAsync(() => ShowError("应用界面运行失败", "WebView2 进程异常退出，请重试。"));
    }

    private static bool IsAllowedNavigation(string? rawUri)
    {
        if (string.Equals(rawUri, "about:blank", StringComparison.OrdinalIgnoreCase))
        {
            return true;
        }
        if (!Uri.TryCreate(rawUri, UriKind.Absolute, out var uri))
        {
            return false;
        }
        if (uri.IsFile)
        {
            var appDirectory = Path.GetFullPath(AppPaths.DesktopAppDirectory)
                               + Path.DirectorySeparatorChar;
            var requestedPath = Path.GetFullPath(uri.LocalPath);
            return requestedPath.StartsWith(appDirectory, StringComparison.OrdinalIgnoreCase);
        }
        if (string.Equals(uri.Scheme, Uri.UriSchemeHttps, StringComparison.OrdinalIgnoreCase)
            && !string.IsNullOrWhiteSpace(uri.Host))
        {
            return true;
        }
        return uri.IsLoopback
               && string.Equals(uri.Scheme, Uri.UriSchemeHttp, StringComparison.OrdinalIgnoreCase);
    }

    private async void HealthTimer_Tick(object? sender, EventArgs e)
    {
        if (_monitorRunning)
        {
            return;
        }
        if (_connectionManager.CurrentMode != AgentConnectionMode.Local)
        {
            return;
        }
        _monitorRunning = true;
        try
        {
            var runtime = await _connectionManager.Execute(
                "getRuntimeStatus",
                JsonSerializer.SerializeToElement(new { }),
                _lifetimeCancellation.Token);
            var serviceStatus = runtime.TryGetProperty("serviceStatus", out var status)
                ? status.GetString()
                : null;
            if (string.Equals(serviceStatus, "UP", StringComparison.OrdinalIgnoreCase))
            {
                var rendererStatus = await _rendererManager.GetStatusAsync(_lifetimeCancellation.Token);
                if (rendererStatus.State != RendererConnectionState.CONNECTED)
                {
                    await EnsureRendererAsync();
                }
                var agentVersion = runtime.TryGetProperty("applicationVersion", out var version)
                    ? version.GetString()
                    : null;
                ConnectionText.Text = $"本地模式 · Agent {agentVersion ?? "未知版本"}";
                if (StatusOverlay.Visibility == Visibility.Visible && _webViewInitialized)
                {
                    NavigateToDesktopApp();
                    ShowConsole();
                }
            }
            else
            {
                ShowError("本地服务响应异常", "当前地址返回的不是可用的 AI Live Edge Agent。");
            }
        }
        catch (OperationCanceledException)
        {
            // Window is closing.
        }
        catch (HttpRequestException)
        {
            if (_processManager.Mode == AgentRuntimeMode.Development)
            {
                ConnectionText.Text = "开发模式 · 等待 IDEA Agent";
                if (_webViewInitialized)
                {
                    ShowConsole();
                }
                return;
            }
            ShowError("本地服务连接已断开", "AI 服务当前不可用，可以重试或重新启动服务。");
        }
        finally
        {
            _monitorRunning = false;
        }
    }

    private void ShowStarting(string title, string message)
    {
        StatusOverlay.Visibility = Visibility.Visible;
        ConsoleWebView.Visibility = Visibility.Collapsed;
        StatusTitle.Text = title;
        StatusMessage.Text = message;
        StartupProgress.Visibility = Visibility.Visible;
        RuntimeHintPanel.Visibility = Visibility.Collapsed;
        ErrorButtons.Visibility = Visibility.Collapsed;
        ConnectionText.Text = message;
    }

    private void ShowAgentError(AgentStartResult result)
    {
        var title = result.Status switch
        {
            AgentStartStatus.PortConflict => "本地服务端口被占用",
            AgentStartStatus.MissingJavaRuntime => "缺少 Java Runtime",
            AgentStartStatus.DevelopmentAgentUnavailable => "开发模式 Agent 未连接",
            AgentStartStatus.MissingAgentJar => "缺少本地 AI 服务文件",
            AgentStartStatus.TimedOut => "本地服务启动超时",
            _ => "本地服务启动失败"
        };
        var message = result.Status switch
        {
            AgentStartStatus.PortConflict => "端口 18081 已被其他程序占用。",
            AgentStartStatus.MissingJavaRuntime => "未找到应用随附的 Java Runtime。",
            AgentStartStatus.DevelopmentAgentUnavailable =>
                "开发模式不会启动本地 JAR。请先在 IDEA 中启动 Java Agent，然后重试。",
            AgentStartStatus.MissingAgentJar => "未找到应用随附的本地 AI 服务文件。",
            AgentStartStatus.TimedOut => "本地 AI 服务未能在预期时间内启动。",
            _ => result.Message
        };
        ShowError(title, message);
    }

    private void ShowError(string title, string message, bool webViewRuntimeMissing = false)
    {
        StatusOverlay.Visibility = Visibility.Visible;
        ConsoleWebView.Visibility = Visibility.Collapsed;
        StatusTitle.Text = title;
        StatusMessage.Text = message;
        StartupProgress.Visibility = Visibility.Collapsed;
        RuntimeHintPanel.Visibility = webViewRuntimeMissing ? Visibility.Visible : Visibility.Collapsed;
        ErrorButtons.Visibility = Visibility.Visible;
        ConnectionText.Text = title;
    }

    private void ShowConsole()
    {
        StatusOverlay.Visibility = Visibility.Collapsed;
        ConsoleWebView.Visibility = Visibility.Visible;
    }

    private async void Retry_Click(object sender, RoutedEventArgs e)
    {
        await InitializeDesktopAsync();
    }

    private async void RestartAgent_Click(object sender, RoutedEventArgs e)
    {
        ShowStarting("正在重新启动本地服务", "正在安全停止并重新连接 AI 服务。");
        var progress = new Progress<string>(message => DesktopLogger.Info(message));
        try
        {
            var result = await _processManager.RestartManagedAgentAsync(progress, _lifetimeCancellation.Token);
            if (!result.IsSuccess)
            {
                ShowAgentError(result);
                return;
            }
            await EnsureWebViewAsync();
            await EnsureRendererAsync();
            ShowConsole();
        }
        catch (Exception ex)
        {
            DesktopLogger.Error("Agent restart failed.", ex);
            ShowError("本地服务重新启动失败", ex.Message);
        }
    }

    private void OpenLogs_Click(object sender, RoutedEventArgs e)
    {
        OpenLogsDirectoryCore();
    }

    private void OpenLogsDirectoryCore()
    {
        try
        {
            Directory.CreateDirectory(AppPaths.LogsDirectory);
            var startInfo = new ProcessStartInfo
            {
                FileName = "explorer.exe",
                UseShellExecute = false
            };
            startInfo.ArgumentList.Add(AppPaths.LogsDirectory);
            Process.Start(startInfo);
        }
        catch (Exception ex)
        {
            DesktopLogger.Error("Failed to open logs directory.", ex);
            WpfMessageBox.Show($"无法打开日志目录：{AppPaths.LogsDirectory}", "AI Live Edge",
                WpfMessageBoxButton.OK, WpfMessageBoxImage.Warning);
        }
    }

    public void HideToTray()
    {
        Hide();
        ShowInTaskbar = false;
        if (_appSettingsService.Current.ShowTrayNotification)
        {
            _trayIconService.ShowBackgroundNotificationOnce();
        }
    }

    private async Task ExitApplicationFromTrayAsync()
    {
        _isExplicitExit = true;
        _allowClose = true;
        await ShutdownForExitAsync(stopManagedAgent: false);
        Close();
    }

    private async Task ToggleBroadcastFromTray()
    {
        if (_sessionService.Current is null)
        {
            ActivateExistingWindow();
            return;
        }

        var empty = JsonSerializer.SerializeToElement(new { });
        var status = await _connectionManager.Execute("getAsrStatus", empty, _lifetimeCancellation.Token);
        var currentStatus = ReadJsonString(status, "status") ?? ReadJsonString(status, "asrStatus");
        var isRunning = currentStatus is "CONNECTED" or "RUNNING";
        if (isRunning)
        {
            await _connectionManager.Execute("stopAsr", empty, _lifetimeCancellation.Token);
        }
        else
        {
            await _authService.RefreshCurrentSessionAsync(_lifetimeCancellation.Token);
            if (!CanStartBroadcast(_sessionService.Current))
            {
                ActivateExistingWindow();
                return;
            }
            await _connectionManager.Execute("startAsr", empty, _lifetimeCancellation.Token);
        }
        UpdateTrayState();
    }

    private async void Window_Closing(object? sender, CancelEventArgs e)
    {
        if (_allowClose)
        {
            return;
        }

        if (!_isExplicitExit
            && _appSettingsService.Current.CloseBehavior == AppCloseBehavior.MinimizeToTray
            && _appSettingsService.Current.MinimizeToTray)
        {
            e.Cancel = true;
            HideToTray();
            return;
        }

        e.Cancel = true;
        if (_closeDialogOpen)
        {
            return;
        }

        _closeDialogOpen = true;
        try
        {
            var dialog = new CloseAgentDialog { Owner = this };
            dialog.ShowDialog();
            if (dialog.Choice == CloseAgentChoice.Cancel)
            {
                return;
            }

            if (dialog.Choice == CloseAgentChoice.StopAgent
                && _connectionManager.CurrentMode == AgentConnectionMode.Local)
            {
                ShowStarting("正在停止本地服务", "正在安全结束本次 AI 服务会话。");
                var result = await _processManager.StopManagedAgentAsync(_lifetimeCancellation.Token);
                if (result.Status == AgentStartStatus.NotManaged)
                {
                    ShowError("无法安全停止本地服务",
                        "服务身份校验未通过，为避免影响其他程序，未执行停止操作。");
                    return;
                }
            }

            await ShutdownForExitAsync(stopManagedAgent: false);
            _allowClose = true;
            Close();
        }
        finally
        {
            _closeDialogOpen = false;
        }
    }

    private void Window_Closed(object? sender, EventArgs e)
    {
        _healthTimer.Stop();
        _trayIconService.Dispose();
        _heartbeatService.Dispose();
        _sessionService.SessionExpired -= SessionService_SessionExpired;
        _lifetimeCancellation.Cancel();
        _lifetimeCancellation.Dispose();
        _healthChecker.Dispose();
        _rendererManager.Dispose();
        _liveOutputWindowManager.Dispose();
        _connectionManager.Dispose();
    }

    private async Task ShutdownForExitAsync(bool stopManagedAgent)
    {
        if (_isShuttingDown)
        {
            return;
        }

        _isShuttingDown = true;
        _trayIconService.DisableInteractions();
        _healthTimer.Stop();
        try
        {
            await _heartbeatService.StopAsync(CancellationToken.None);
        }
        catch (Exception ex)
        {
            DesktopLogger.Error("Failed to stop heartbeat during shutdown.", ex);
        }

        try
        {
            _lifetimeCancellation.Cancel();
        }
        catch (Exception ex)
        {
            DesktopLogger.Error("Failed to cancel desktop lifetime token.", ex);
        }

        try
        {
            await _liveOutputWindowManager.ShutdownAsync(CancellationToken.None);
        }
        catch (Exception ex)
        {
            DesktopLogger.Error("Failed to shutdown LiveOutput during shutdown.", ex);
        }

        if (stopManagedAgent)
        {
            try
            {
                await _processManager.StopManagedAgentAsync(CancellationToken.None);
            }
            catch (Exception ex)
            {
                DesktopLogger.Error("Failed to stop managed Agent during shutdown.", ex);
            }
        }
    }

    private async void SessionService_SessionExpired(object? sender, string reason)
    {
        try
        {
            await Dispatcher.InvokeAsync(async () =>
            {
                await _heartbeatService.StopAsync(_lifetimeCancellation.Token);
                _healthTimer.Stop();
                PostAuthState(reason);
            });
        }
        catch (OperationCanceledException)
        {
        }
        catch (Exception ex)
        {
            DesktopLogger.Error("Session expired handler failed.", ex);
        }
    }

    private void LoadDistributionVersion()
    {
        try
        {
            if (!File.Exists(AppPaths.VersionFile))
            {
                return;
            }
            var version = JsonSerializer.Deserialize<DistributionVersionInfo>(File.ReadAllText(AppPaths.VersionFile));
            if (!string.IsNullOrWhiteSpace(version?.DesktopVersion))
            {
                Title = $"AI Live Edge {version.DesktopVersion}";
            }
            DesktopLogger.Info($"Version loaded. desktop={version?.DesktopVersion}, agent={version?.AgentVersion}, " +
                               $"architecture={version?.Architecture}, install={AppPaths.InstallDirectory}, data={AppPaths.DataDirectory}");
        }
        catch (Exception ex)
        {
            DesktopLogger.Error("Failed to load version.json.", ex);
        }
    }

    [DllImport("user32.dll")]
    private static extern bool SetForegroundWindow(IntPtr hWnd);

    [DllImport("user32.dll")]
    private static extern bool ShowWindow(IntPtr hWnd, int nCmdShow);
}
