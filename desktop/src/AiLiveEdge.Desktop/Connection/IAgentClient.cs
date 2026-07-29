namespace AiLiveEdge.Desktop.Connection;

public enum AgentConnectionMode
{
    Local,
    Cloud
}

public interface IAgentClient : IDisposable
{
    AgentConnectionMode Mode { get; }

    Uri BaseAddress { get; }

    Task<JsonElement> GetRuntimeStatus(CancellationToken cancellationToken = default);

    Task<JsonElement> GetAsrStatus(CancellationToken cancellationToken = default);

    Task<JsonElement> StartAsr(CancellationToken cancellationToken = default);

    Task<JsonElement> StopAsr(CancellationToken cancellationToken = default);

    Task<JsonElement> SendCommand(string command, CancellationToken cancellationToken = default);

    Task<JsonElement> GetActionStatus(CancellationToken cancellationToken = default);

    Task<JsonElement> GetMediaStatus(CancellationToken cancellationToken = default);

    Task<JsonElement> GetCommands(CancellationToken cancellationToken = default);

    Task<JsonElement> GetAssets(CancellationToken cancellationToken = default);

    Task<JsonElement> StartAudioTest(CancellationToken cancellationToken = default);

    Task<JsonElement> UploadAsset(
        string fileName,
        string contentType,
        byte[] content,
        CancellationToken cancellationToken = default);
}
