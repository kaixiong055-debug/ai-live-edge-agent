using System.Windows;
using AiLiveEdge.Desktop.Connection;
using AiLiveEdge.Desktop.Services.Diagnostics;
using AiLiveEdge.Desktop.Services.Deployment;
using AiLiveEdge.Desktop.Services.Auth;
using AiLiveEdge.Desktop.Services.Device;
using AiLiveEdge.Desktop.Services.Heartbeat;
using AiLiveEdge.Desktop.Services.Http;
using AiLiveEdge.Desktop.Services.Session;
using AiLiveEdge.Desktop.Services.Settings;
using AiLiveEdge.Desktop.Services.Startup;
using AiLiveEdge.Desktop.Services.Storage;
using AiLiveEdge.Desktop.Services.Tray;
using AiLiveEdge.Desktop.Services.Versioning;
using WpfApplication = System.Windows.Application;
using WpfExitEventArgs = System.Windows.ExitEventArgs;
using WpfMessageBox = System.Windows.MessageBox;
using WpfMessageBoxButton = System.Windows.MessageBoxButton;
using WpfMessageBoxImage = System.Windows.MessageBoxImage;
using WpfStartupEventArgs = System.Windows.StartupEventArgs;

namespace AiLiveEdge.Desktop;

public partial class App : WpfApplication
{
    private SingleInstanceGuard? _singleInstanceGuard;
    private readonly GlobalExceptionHandler _globalExceptionHandler = new();

    protected override async void OnStartup(WpfStartupEventArgs e)
    {
        base.OnStartup(e);

        try
        {
            AppPaths.InitializeUserDirectories();
            DesktopLogger.Initialize(AppPaths.DesktopLogFile);
            _globalExceptionHandler.Register(this);
            DesktopLogger.Info("Desktop starting.");

            _singleInstanceGuard = SingleInstanceGuard.Acquire();
            if (!_singleInstanceGuard.IsPrimaryInstance)
            {
                _singleInstanceGuard.SignalPrimaryInstance();
                WpfMessageBox.Show("AI Live Edge 已经在运行。", "AI Live Edge",
                    WpfMessageBoxButton.OK, WpfMessageBoxImage.Information);
                Shutdown(0);
                return;
            }

            var appSettingsService = new AppSettingsService();
            var settings = await appSettingsService.LoadAsync();
            DesktopLogger.CleanupExpiredLogs(settings.LogRetentionDays);
            var startupService = new WindowsStartupService();
            var healthChecker = new AgentHealthChecker();
            var processManager = new AgentProcessManager(healthChecker);
            var connectionManager = new AgentConnectionManager();
            var deploymentConfigurationService = new DeploymentConfigurationService();
            var cloudApiSettingsService = new CloudApiSettingsService(
                appSettingsService,
                deploymentConfigurationService);
            var sessionService = new SessionService(new WindowsDpapiSecureStorage());
            var versionService = new AppVersionService();
            var authService = new AuthService(
                cloudApiSettingsService,
                new DeviceIdentityService(),
                sessionService,
                versionService);
            var heartbeatService = new AgentHeartbeatService(authService, cloudApiSettingsService);
            var trayIconService = new TrayIconService();
            var mainWindow = new MainWindow(
                healthChecker,
                processManager,
                connectionManager,
                authService,
                sessionService,
                heartbeatService,
                cloudApiSettingsService,
                appSettingsService,
                startupService,
                trayIconService,
                versionService,
                deploymentConfigurationService);
            MainWindow = mainWindow;
            _singleInstanceGuard.StartActivationListener(mainWindow.ActivateExistingWindow);
            mainWindow.Show();
            if (settings.StartMinimized)
            {
                mainWindow.HideToTray();
            }
        }
        catch (Exception ex)
        {
            DesktopLogger.Error("Desktop startup failed.", ex);
            WpfMessageBox.Show($"AI Live Edge 启动失败：{ex.Message}\n\n日志：{AppPaths.DesktopLogFile}",
                "AI Live Edge", WpfMessageBoxButton.OK, WpfMessageBoxImage.Error);
            Shutdown(1);
        }
    }

    protected override void OnExit(WpfExitEventArgs e)
    {
        _singleInstanceGuard?.Dispose();
        DesktopLogger.Info("Desktop exited.");
        base.OnExit(e);
    }
}
