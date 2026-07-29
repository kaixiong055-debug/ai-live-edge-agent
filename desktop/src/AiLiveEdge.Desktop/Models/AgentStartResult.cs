namespace AiLiveEdge.Desktop.Models;

public enum AgentStartStatus
{
    AlreadyRunning,
    Started,
    MissingAgentJar,
    MissingJavaRuntime,
    DevelopmentAgentUnavailable,
    PortConflict,
    StartFailed,
    ProcessExited,
    TimedOut,
    NotManaged
}

public sealed record AgentStartResult(
    AgentStartStatus Status,
    string Message,
    int? ProcessId = null,
    string? AgentVersion = null)
{
    public bool IsSuccess => Status is AgentStartStatus.AlreadyRunning or AgentStartStatus.Started;
}
