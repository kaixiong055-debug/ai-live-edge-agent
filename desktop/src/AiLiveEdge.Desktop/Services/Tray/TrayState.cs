namespace AiLiveEdge.Desktop.Services.Tray;

public sealed record TrayState(
    bool IsAuthenticated,
    bool CanStartBroadcast,
    bool IsBroadcastRunning,
    string StatusText);
