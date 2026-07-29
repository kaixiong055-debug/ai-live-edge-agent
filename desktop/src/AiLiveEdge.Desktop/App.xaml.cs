using System.Windows;
using AiLiveEdge.Desktop.Connection;

namespace AiLiveEdge.Desktop;

public partial class App : Application
{
    private SingleInstanceGuard? _singleInstanceGuard;

    protected override void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);

        try
        {
            AppPaths.InitializeUserDirectories();
            DesktopLogger.Initialize(AppPaths.DesktopLogFile);
            DesktopLogger.Info("Desktop starting.");

            _singleInstanceGuard = SingleInstanceGuard.Acquire();
            if (!_singleInstanceGuard.IsPrimaryInstance)
            {
                _singleInstanceGuard.SignalPrimaryInstance();
                MessageBox.Show("AI Live Edge 已经在运行。", "AI Live Edge",
                    MessageBoxButton.OK, MessageBoxImage.Information);
                Shutdown(0);
                return;
            }

            var healthChecker = new AgentHealthChecker();
            var processManager = new AgentProcessManager(healthChecker);
            var connectionManager = new AgentConnectionManager();
            var mainWindow = new MainWindow(healthChecker, processManager, connectionManager);
            MainWindow = mainWindow;
            _singleInstanceGuard.StartActivationListener(mainWindow.ActivateExistingWindow);
            mainWindow.Show();
        }
        catch (Exception ex)
        {
            DesktopLogger.Error("Desktop startup failed.", ex);
            MessageBox.Show($"AI Live Edge 启动失败：{ex.Message}\n\n日志：{AppPaths.DesktopLogFile}",
                "AI Live Edge", MessageBoxButton.OK, MessageBoxImage.Error);
            Shutdown(1);
        }
    }

    protected override void OnExit(ExitEventArgs e)
    {
        _singleInstanceGuard?.Dispose();
        DesktopLogger.Info("Desktop exited.");
        base.OnExit(e);
    }
}
