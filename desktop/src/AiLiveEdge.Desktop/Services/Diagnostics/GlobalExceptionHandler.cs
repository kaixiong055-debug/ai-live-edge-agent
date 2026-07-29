using System.Windows.Threading;
using WpfApplication = System.Windows.Application;
using WpfMessageBox = System.Windows.MessageBox;
using WpfMessageBoxButton = System.Windows.MessageBoxButton;
using WpfMessageBoxImage = System.Windows.MessageBoxImage;

namespace AiLiveEdge.Desktop.Services.Diagnostics;

public sealed class GlobalExceptionHandler
{
    private int _dialogOpen;

    public void Register(WpfApplication application)
    {
        application.DispatcherUnhandledException += OnDispatcherUnhandledException;
        AppDomain.CurrentDomain.UnhandledException += OnUnhandledException;
        TaskScheduler.UnobservedTaskException += OnUnobservedTaskException;
    }

    private void OnDispatcherUnhandledException(object sender, DispatcherUnhandledExceptionEventArgs e)
    {
        DesktopLogger.Error("Unhandled WPF dispatcher exception.", e.Exception);
        if (IsRecoverableUiException(e.Exception))
        {
            e.Handled = true;
            ShowFriendlyMessage("界面操作失败，请稍后重试。");
        }
    }

    private void OnUnhandledException(object sender, UnhandledExceptionEventArgs e)
    {
        if (e.ExceptionObject is Exception ex)
        {
            DesktopLogger.Error("Unhandled AppDomain exception.", ex);
        }
    }

    private void OnUnobservedTaskException(object? sender, UnobservedTaskExceptionEventArgs e)
    {
        DesktopLogger.Error("Unobserved task exception.", e.Exception);
        e.SetObserved();
    }

    private void ShowFriendlyMessage(string message)
    {
        if (Interlocked.Exchange(ref _dialogOpen, 1) == 1)
        {
            return;
        }

        try
        {
            WpfMessageBox.Show(message, "AI Live Edge", WpfMessageBoxButton.OK, WpfMessageBoxImage.Warning);
        }
        finally
        {
            Interlocked.Exchange(ref _dialogOpen, 0);
        }
    }

    private static bool IsRecoverableUiException(Exception exception) =>
        exception is InvalidOperationException
        or IOException
        or UnauthorizedAccessException
        or HttpRequestException
        or TaskCanceledException;
}
