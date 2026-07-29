namespace AiLiveEdge.Desktop.Renderer;

public enum RendererConnectionState
{
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    FAILED
}

public sealed record RendererStatus(
    RendererConnectionState State,
    bool PageAvailable,
    int ConnectionCount,
    string? LastAction,
    DateTimeOffset LastUpdatedAt,
    string? ErrorMessage = null)
{
    public static RendererStatus Disconnected { get; } = new(
        RendererConnectionState.DISCONNECTED,
        false,
        0,
        null,
        DateTimeOffset.UtcNow);
}
