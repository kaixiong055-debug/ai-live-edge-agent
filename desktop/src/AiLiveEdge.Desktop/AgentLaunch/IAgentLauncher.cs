using AiLiveEdge.Desktop.Models;

namespace AiLiveEdge.Desktop.AgentLaunch;

public interface IAgentLauncher
{
    AgentRuntimeMode Mode { get; }

    Task<AgentStartResult> EnsureAgentRunningAsync(
        IProgress<string>? progress = null,
        CancellationToken cancellationToken = default);

    Task<AgentStartResult> RestartManagedAgentAsync(
        IProgress<string>? progress = null,
        CancellationToken cancellationToken = default);

    Task<AgentStartResult> StopManagedAgentAsync(CancellationToken cancellationToken = default);
}
