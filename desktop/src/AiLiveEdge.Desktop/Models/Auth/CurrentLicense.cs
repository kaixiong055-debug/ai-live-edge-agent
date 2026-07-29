namespace AiLiveEdge.Desktop.Models.Auth;

public sealed record CurrentLicense(
    int? LicenseType,
    int? CredentialMode,
    int? Status,
    DateTimeOffset? ValidFrom,
    DateTimeOffset? ValidUntil,
    int? MaxDeviceCount,
    int? MaxConcurrentAgentCount,
    int? OfflineGraceHours)
{
    public bool IsUsable => Status == 1 && (!ValidUntil.HasValue || ValidUntil.Value > DateTimeOffset.UtcNow);
}
