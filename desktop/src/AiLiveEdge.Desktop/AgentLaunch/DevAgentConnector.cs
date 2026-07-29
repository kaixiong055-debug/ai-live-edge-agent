using AiLiveEdge.Desktop.Models;

namespace AiLiveEdge.Desktop.AgentLaunch;

public sealed class DevAgentConnector : IAgentLauncher
{
    private readonly AgentHealthChecker _healthChecker;

    public DevAgentConnector(AgentHealthChecker healthChecker)
    {
        _healthChecker = healthChecker;
    }

    public AgentRuntimeMode Mode => AgentRuntimeMode.Development;

    public async Task<AgentStartResult> EnsureAgentRunningAsync(
        IProgress<string>? progress = null,
        CancellationToken cancellationToken = default)
    {
        progress?.Report("正在连接 IDEA 中运行的 Agent…");
        var health = await _healthChecker.CheckAsync(cancellationToken);
        return health.State switch
        {
            AgentHealthState.Healthy => new AgentStartResult(
                AgentStartStatus.AlreadyRunning,
                "开发模式已连接到 IDEA Agent。",
                AgentVersion: health.AgentVersion),
            AgentHealthState.PortOccupiedByOtherService => new AgentStartResult(
                AgentStartStatus.PortConflict,
                "端口 18081 已被非 AI Live Edge Agent 服务占用。"),
            _ => new AgentStartResult(
                AgentStartStatus.DevelopmentAgentUnavailable,
                "开发模式不会启动 JAR。请先在 IDEA 中启动 Java Agent，确认 http://127.0.0.1:18081 可访问。")
        };
    }

    public Task<AgentStartResult> RestartManagedAgentAsync(
        IProgress<string>? progress = null,
        CancellationToken cancellationToken = default) =>
        EnsureAgentRunningAsync(progress, cancellationToken);

    public Task<AgentStartResult> StopManagedAgentAsync(CancellationToken cancellationToken = default) =>
        Task.FromResult(new AgentStartResult(
            AgentStartStatus.NotManaged,
            "开发模式下 Agent 由 IDEA 管理，Desktop 不会停止该进程。"));
}
