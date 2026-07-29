namespace AiLiveEdge.Desktop.Services.Tray;

public interface ITrayIconService : IDisposable
{
    bool IsInitialized { get; }
    void Initialize(TrayCallbacks callbacks);
    void Update(TrayState state);
    void ShowBackgroundNotificationOnce();
    void DisableInteractions();
}
