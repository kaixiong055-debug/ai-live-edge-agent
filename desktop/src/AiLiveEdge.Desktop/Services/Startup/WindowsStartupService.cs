using Microsoft.Win32;

namespace AiLiveEdge.Desktop.Services.Startup;

public sealed class WindowsStartupService : IWindowsStartupService
{
    private const string RunKeyPath = @"Software\Microsoft\Windows\CurrentVersion\Run";
    private const string ValueName = "AI Live Edge";

    public bool IsEnabled()
    {
        using var key = Registry.CurrentUser.OpenSubKey(RunKeyPath, writable: false);
        var value = key?.GetValue(ValueName)?.ToString();
        return string.Equals(value, QuoteExecutablePath(), StringComparison.OrdinalIgnoreCase);
    }

    public void SetEnabled(bool enabled)
    {
        using var key = Registry.CurrentUser.CreateSubKey(RunKeyPath, writable: true)
                       ?? throw new InvalidOperationException("无法打开当前用户开机启动注册表项。");
        if (enabled)
        {
            key.SetValue(ValueName, QuoteExecutablePath(), RegistryValueKind.String);
            return;
        }

        key.DeleteValue(ValueName, throwOnMissingValue: false);
    }

    private static string QuoteExecutablePath()
    {
        var path = Environment.ProcessPath ?? Process.GetCurrentProcess().MainModule?.FileName;
        if (string.IsNullOrWhiteSpace(path))
        {
            throw new InvalidOperationException("无法识别当前应用程序路径。");
        }
        return $"\"{path}\"";
    }
}
