namespace AiLiveEdge.Desktop.Services.Device;

public interface IDeviceIdentityService
{
    Task<AgentDeviceIdentity> GetOrCreateAsync(CancellationToken cancellationToken = default);
}

public sealed record AgentDeviceIdentity(
    string DeviceCode,
    string DeviceName,
    string DeviceFingerprint,
    string OsName,
    string OsVersion);
