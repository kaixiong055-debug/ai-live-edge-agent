namespace AiLiveEdge.Desktop.Services.Http;

public sealed record CloudApiSettings(string BaseUrl, int TimeoutSeconds, int HeartbeatIntervalSeconds)
{
    public static CloudApiSettings Empty { get; } = new(string.Empty, 15, 30);
}
