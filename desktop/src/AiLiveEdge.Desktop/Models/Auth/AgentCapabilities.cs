namespace AiLiveEdge.Desktop.Models.Auth;

public sealed record AgentCapabilities(
    bool AgentRun,
    bool ConfigRead,
    bool PlatformManagedCredential);
