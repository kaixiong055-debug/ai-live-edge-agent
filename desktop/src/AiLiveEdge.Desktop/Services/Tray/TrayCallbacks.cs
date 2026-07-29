namespace AiLiveEdge.Desktop.Services.Tray;

public sealed record TrayCallbacks(
    Func<Task> OpenMainWindow,
    Func<Task> ToggleBroadcast,
    Func<Task> Logout,
    Func<Task> ExitApplication);
