namespace AiLiveEdge.Desktop.Services.Settings;

public sealed record AppSettings(
    int SettingsVersion,
    string CloudApiBaseUrl,
    int HeartbeatIntervalSeconds,
    AppCloseBehavior CloseBehavior,
    bool StartWithWindows,
    bool StartMinimized,
    bool MinimizeToTray,
    bool ShowTrayNotification,
    int LogRetentionDays,
    bool RendererAutoStart,
    string LastSelectedPage)
{
    public const int CurrentSettingsVersion = 1;

    public static AppSettings Default { get; } = new(
        CurrentSettingsVersion,
        string.Empty,
        30,
        AppCloseBehavior.MinimizeToTray,
        false,
        false,
        true,
        true,
        14,
        true,
        "home");
}
