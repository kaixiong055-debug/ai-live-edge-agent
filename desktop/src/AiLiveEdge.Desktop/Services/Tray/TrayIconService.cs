using System.Drawing;
using Forms = System.Windows.Forms;

namespace AiLiveEdge.Desktop.Services.Tray;

public sealed class TrayIconService : ITrayIconService
{
    private Forms.NotifyIcon? _notifyIcon;
    private Forms.ToolStripMenuItem? _statusItem;
    private Forms.ToolStripMenuItem? _toggleBroadcastItem;
    private Forms.ToolStripMenuItem? _logoutItem;
    private Forms.ToolStripMenuItem? _exitItem;
    private TrayCallbacks? _callbacks;
    private bool _notificationShown;

    public bool IsInitialized => _notifyIcon is not null;

    public void Initialize(TrayCallbacks callbacks)
    {
        if (_notifyIcon is not null)
        {
            _callbacks = callbacks;
            return;
        }

        _callbacks = callbacks;
        _statusItem = new Forms.ToolStripMenuItem("状态：未登录") { Enabled = false };
        _toggleBroadcastItem = new Forms.ToolStripMenuItem("启动伴播");
        _logoutItem = new Forms.ToolStripMenuItem("退出登录");
        _exitItem = new Forms.ToolStripMenuItem("退出程序");

        var openItem = new Forms.ToolStripMenuItem("打开主界面");
        openItem.Click += async (_, _) => await InvokeAsync(() => _callbacks?.OpenMainWindow() ?? Task.CompletedTask);
        _toggleBroadcastItem.Click += async (_, _) => await InvokeAsync(() => _callbacks?.ToggleBroadcast() ?? Task.CompletedTask);
        _logoutItem.Click += async (_, _) => await InvokeAsync(() => _callbacks?.Logout() ?? Task.CompletedTask);
        _exitItem.Click += async (_, _) => await InvokeAsync(() => _callbacks?.ExitApplication() ?? Task.CompletedTask);

        _notifyIcon = new Forms.NotifyIcon
        {
            Text = "AI Live Edge",
            Icon = LoadIcon(),
            Visible = true,
            ContextMenuStrip = new Forms.ContextMenuStrip()
        };
        _notifyIcon.ContextMenuStrip.Items.Add(openItem);
        _notifyIcon.ContextMenuStrip.Items.Add(_statusItem);
        _notifyIcon.ContextMenuStrip.Items.Add(new Forms.ToolStripSeparator());
        _notifyIcon.ContextMenuStrip.Items.Add(_toggleBroadcastItem);
        _notifyIcon.ContextMenuStrip.Items.Add(_logoutItem);
        _notifyIcon.ContextMenuStrip.Items.Add(new Forms.ToolStripSeparator());
        _notifyIcon.ContextMenuStrip.Items.Add(_exitItem);
        _notifyIcon.DoubleClick += async (_, _) => await InvokeAsync(() => _callbacks?.OpenMainWindow() ?? Task.CompletedTask);
    }

    public void Update(TrayState state)
    {
        if (_notifyIcon is null)
        {
            return;
        }

        _notifyIcon.Text = state.StatusText.Length > 63
            ? state.StatusText[..63]
            : state.StatusText;
        if (_statusItem is not null)
        {
            _statusItem.Text = "状态：" + state.StatusText;
        }
        if (_toggleBroadcastItem is not null)
        {
            _toggleBroadcastItem.Text = state.IsBroadcastRunning ? "停止伴播" : "启动伴播";
            _toggleBroadcastItem.Enabled = state.IsAuthenticated
                                           && (state.IsBroadcastRunning || state.CanStartBroadcast);
        }
        if (_logoutItem is not null)
        {
            _logoutItem.Enabled = state.IsAuthenticated;
        }
    }

    public void ShowBackgroundNotificationOnce()
    {
        if (_notifyIcon is null || _notificationShown)
        {
            return;
        }

        _notificationShown = true;
        _notifyIcon.BalloonTipTitle = "AI Live Edge";
        _notifyIcon.BalloonTipText = "AI Live Edge 仍在后台运行";
        _notifyIcon.ShowBalloonTip(2500);
    }

    public void DisableInteractions()
    {
        if (_toggleBroadcastItem is not null)
        {
            _toggleBroadcastItem.Enabled = false;
        }
        if (_logoutItem is not null)
        {
            _logoutItem.Enabled = false;
        }
        if (_exitItem is not null)
        {
            _exitItem.Enabled = false;
        }
    }

    public void Dispose()
    {
        if (_notifyIcon is null)
        {
            return;
        }

        _notifyIcon.Visible = false;
        _notifyIcon.Dispose();
        _notifyIcon = null;
    }

    private static Icon LoadIcon()
    {
        try
        {
            var path = Environment.ProcessPath;
            if (!string.IsNullOrWhiteSpace(path) && File.Exists(path))
            {
                return Icon.ExtractAssociatedIcon(path) ?? SystemIcons.Application;
            }
        }
        catch (Exception ex)
        {
            DesktopLogger.Error("Failed to load tray icon from executable; using default icon.", ex);
        }

        return SystemIcons.Application;
    }

    private static async Task InvokeAsync(Func<Task>? action)
    {
        if (action is null)
        {
            return;
        }

        try
        {
            await action();
        }
        catch (Exception ex)
        {
            DesktopLogger.Error("Tray command failed.", ex);
        }
    }
}
