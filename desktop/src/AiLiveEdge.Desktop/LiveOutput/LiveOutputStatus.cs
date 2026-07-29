namespace AiLiveEdge.Desktop.LiveOutput;

public enum LiveOutputConnectionState
{
    Closed,
    Connecting,
    Connected,
    Failed
}

public sealed record LiveOutputStatus(
    bool IsOpen,
    LiveOutputConnectionState State,
    LiveOutputSettings Settings,
    string? ErrorMessage = null);
