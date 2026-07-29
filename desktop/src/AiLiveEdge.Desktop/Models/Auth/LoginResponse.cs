namespace AiLiveEdge.Desktop.Models.Auth;

public sealed record LoginResponse(
    string AccessToken,
    string RefreshToken,
    long? ExpiresIn,
    long? RefreshExpiresIn,
    string? TokenType,
    CurrentUser? User,
    CurrentTenant? Tenant,
    AgentDeviceInfo? Device,
    CurrentLicense? License,
    AgentCapabilities? Capabilities);
