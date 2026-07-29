using AiLiveEdge.Desktop.AgentLaunch;
using AiLiveEdge.Desktop.Models;

namespace AiLiveEdge.Desktop;

public sealed class AgentProcessManager
{
    private readonly IAgentLauncher _developmentLauncher;
    private readonly IAgentLauncher _productionLauncher;
    private readonly SemaphoreSlim _modeLock = new(1, 1);
    private AgentRuntimeMode _mode;

    public AgentProcessManager(AgentHealthChecker healthChecker)
    {
        _developmentLauncher = new DevAgentConnector(healthChecker);
        _productionLauncher = new JarAgentLauncher(healthChecker);
        _mode = LoadMode();
        DesktopLogger.Info($"Agent runtime mode: {_mode}");
    }

    public AgentRuntimeMode Mode => _mode;

    private IAgentLauncher CurrentLauncher =>
        _mode == AgentRuntimeMode.Development
            ? _developmentLauncher
            : _productionLauncher;

    public Task<AgentStartResult> EnsureAgentRunningAsync(
        IProgress<string>? progress = null,
        CancellationToken cancellationToken = default) =>
        CurrentLauncher.EnsureAgentRunningAsync(progress, cancellationToken);

    public Task<AgentStartResult> RestartManagedAgentAsync(
        IProgress<string>? progress = null,
        CancellationToken cancellationToken = default) =>
        CurrentLauncher.RestartManagedAgentAsync(progress, cancellationToken);

    public Task<AgentStartResult> StopManagedAgentAsync(CancellationToken cancellationToken = default) =>
        CurrentLauncher.StopManagedAgentAsync(cancellationToken);

    public async Task<AgentRuntimeMode> SetModeAsync(
        AgentRuntimeMode mode,
        CancellationToken cancellationToken = default)
    {
        await _modeLock.WaitAsync(cancellationToken);
        try
        {
            if (_mode == mode)
            {
                return _mode;
            }

            if (_mode == AgentRuntimeMode.Production)
            {
                var stopResult = await _productionLauncher.StopManagedAgentAsync(cancellationToken);
                if (stopResult.Status == AgentStartStatus.NotManaged)
                {
                    DesktopLogger.Info("Production Agent is external or already stopped; mode switch continues.");
                }
            }

            _mode = mode;
            SaveMode(mode);
            DesktopLogger.Info($"Agent runtime mode changed to: {mode}");
            return _mode;
        }
        finally
        {
            _modeLock.Release();
        }
    }

    private static AgentRuntimeMode LoadMode()
    {
        try
        {
            if (File.Exists(AppPaths.AgentRuntimeSettingsFile))
            {
                using var document = JsonDocument.Parse(File.ReadAllText(AppPaths.AgentRuntimeSettingsFile));
                if (document.RootElement.TryGetProperty("mode", out var modeElement)
                    && Enum.TryParse<AgentRuntimeMode>(modeElement.GetString(), true, out var savedMode))
                {
                    return savedMode;
                }
            }
        }
        catch (Exception ex)
        {
            DesktopLogger.Error("Failed to load Agent runtime mode.", ex);
        }

#if DEBUG
        return AgentRuntimeMode.Development;
#else
        return AgentRuntimeMode.Production;
#endif
    }

    private static void SaveMode(AgentRuntimeMode mode)
    {
        Directory.CreateDirectory(Path.GetDirectoryName(AppPaths.AgentRuntimeSettingsFile)!);
        File.WriteAllText(
            AppPaths.AgentRuntimeSettingsFile,
            JsonSerializer.Serialize(new
            {
                mode = mode.ToString().ToUpperInvariant()
            }, new JsonSerializerOptions { WriteIndented = true }));
    }
}
