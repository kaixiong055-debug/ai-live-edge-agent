namespace AiLiveEdge.Desktop.Models.Auth;

public sealed record AuthSession(
    string AccessToken,
    string RefreshToken,
    DateTimeOffset AccessTokenExpiresAt,
    DateTimeOffset RefreshTokenExpiresAt,
    string TokenType,
    CurrentUser? User,
    CurrentTenant? Tenant,
    AgentDeviceInfo? Device,
    CurrentLicense? License,
    AgentCapabilities? Capabilities)
{
    public bool HasRefreshToken => !string.IsNullOrWhiteSpace(RefreshToken)
                                   && RefreshTokenExpiresAt > DateTimeOffset.UtcNow;
}
