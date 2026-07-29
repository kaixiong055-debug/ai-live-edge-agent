namespace AiLiveEdge.Desktop.Services.Heartbeat;

public interface IAgentHeartbeatService : IDisposable
{
    string CloudStatus { get; }
    int ConsecutiveErrors { get; }
    void Start(Func<Task<HeartbeatStatusPayload>> statusFactory);
    Task StopAsync(CancellationToken cancellationToken = default);
}

public sealed record HeartbeatStatusPayload(
    string? RendererStatus,
    string? WebSocketStatus,
    string? CurrentMode);
