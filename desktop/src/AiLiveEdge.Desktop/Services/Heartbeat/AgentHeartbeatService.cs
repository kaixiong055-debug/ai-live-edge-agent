using AiLiveEdge.Desktop.Services.Auth;
using AiLiveEdge.Desktop.Services.Http;

namespace AiLiveEdge.Desktop.Services.Heartbeat;

public sealed class AgentHeartbeatService : IAgentHeartbeatService
{
    private readonly IAuthService _authService;
    private readonly ICloudApiSettingsService _settingsService;
    private readonly SemaphoreSlim _singleHeartbeatLock = new(1, 1);
    private CancellationTokenSource? _heartbeatCancellation;
    private Task? _heartbeatTask;
    private bool _disposed;

    public AgentHeartbeatService(IAuthService authService, ICloudApiSettingsService settingsService)
    {
        _authService = authService;
        _settingsService = settingsService;
    }

    public string CloudStatus { get; private set; } = "DISCONNECTED";

    public int ConsecutiveErrors { get; private set; }

    public void Start(Func<Task<HeartbeatStatusPayload>> statusFactory)
    {
        ObjectDisposedException.ThrowIf(_disposed, this);
        if (_heartbeatTask is { IsCompleted: false })
        {
            return;
        }
        _heartbeatCancellation = new CancellationTokenSource();
        _heartbeatTask = RunLoop(statusFactory, _heartbeatCancellation.Token);
    }

    public async Task StopAsync(CancellationToken cancellationToken = default)
    {
        if (_heartbeatCancellation is null)
        {
            CloudStatus = "DISCONNECTED";
            return;
        }
        await _heartbeatCancellation.CancelAsync();
        try
        {
            if (_heartbeatTask is not null)
            {
                await _heartbeatTask.WaitAsync(cancellationToken);
            }
        }
        catch (OperationCanceledException)
        {
        }
        finally
        {
            _heartbeatCancellation.Dispose();
            _heartbeatCancellation = null;
            _heartbeatTask = null;
            CloudStatus = "DISCONNECTED";
            ConsecutiveErrors = 0;
        }
    }

    private async Task RunLoop(Func<Task<HeartbeatStatusPayload>> statusFactory, CancellationToken cancellationToken)
    {
        var interval = TimeSpan.FromSeconds(Math.Clamp(_settingsService.Current.HeartbeatIntervalSeconds, 10, 300));
        while (!cancellationToken.IsCancellationRequested)
        {
            await SendOnce(statusFactory, cancellationToken);
            await Task.Delay(interval, cancellationToken);
        }
    }

    private async Task SendOnce(Func<Task<HeartbeatStatusPayload>> statusFactory, CancellationToken cancellationToken)
    {
        if (!await _singleHeartbeatLock.WaitAsync(0, cancellationToken))
        {
            return;
        }
        try
        {
            CloudStatus = "CONNECTING";
            var status = await statusFactory();
            await _authService.SendHeartbeatAsync(
                status.RendererStatus,
                status.WebSocketStatus,
                status.CurrentMode,
                cancellationToken);
            ConsecutiveErrors = 0;
            CloudStatus = "CONNECTED";
        }
        catch (OperationCanceledException)
        {
            throw;
        }
        catch (ApiException ex) when (ex.IsSessionExpired || ex.IsDeviceOrLicenseInvalid)
        {
            ConsecutiveErrors++;
            CloudStatus = "SESSION_EXPIRED";
            DesktopLogger.Error($"Agent heartbeat rejected. code={ex.Code}, status={ex.StatusCode}");
        }
        catch (Exception ex) when (ex is HttpRequestException or TaskCanceledException or InvalidOperationException)
        {
            ConsecutiveErrors++;
            CloudStatus = "ERROR";
            DesktopLogger.Error($"Agent heartbeat failed. consecutiveErrors={ConsecutiveErrors}", ex);
        }
        finally
        {
            _singleHeartbeatLock.Release();
        }
    }

    public void Dispose()
    {
        if (_disposed)
        {
            return;
        }
        _disposed = true;
        _heartbeatCancellation?.Cancel();
        _heartbeatCancellation?.Dispose();
        _singleHeartbeatLock.Dispose();
    }
}
