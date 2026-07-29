namespace AiLiveEdge.Desktop.Models.Auth;

public sealed record AgentDeviceInfo(
    long Id,
    string? DeviceCode,
    string? DeviceName,
    int? Status);
