using AiLiveEdge.Desktop.Models.Auth;

namespace AiLiveEdge.Desktop.Services.Auth;

public interface IAuthService
{
    AuthSession? CurrentSession { get; }
    Task<AuthSession?> RestoreSessionAsync(CancellationToken cancellationToken = default);
    Task<AuthSession> LoginAsync(LoginRequest request, CancellationToken cancellationToken = default);
    Task<AuthSession?> RefreshCurrentSessionAsync(CancellationToken cancellationToken = default);
    Task<AuthSession> LoadCurrentUserAsync(CancellationToken cancellationToken = default);
    Task<CurrentLicense?> LoadCurrentLicenseAsync(CancellationToken cancellationToken = default);
    Task SendHeartbeatAsync(string? rendererStatus, string? webSocketStatus, string? currentMode, CancellationToken cancellationToken = default);
    Task LogoutAsync(CancellationToken cancellationToken = default);
}
