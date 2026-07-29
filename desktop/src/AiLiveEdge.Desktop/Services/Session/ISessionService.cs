using AiLiveEdge.Desktop.Models.Auth;

namespace AiLiveEdge.Desktop.Services.Session;

public interface ISessionService
{
    AuthSession? Current { get; }
    bool IsPersistent { get; }
    event EventHandler<string>? SessionExpired;
    Task<AuthSession?> LoadAsync(CancellationToken cancellationToken = default);
    Task SaveAsync(AuthSession session, CancellationToken cancellationToken = default);
    Task ClearAsync(CancellationToken cancellationToken = default);
    void SetCurrent(AuthSession? session);
    Task ExpireAsync(string reason, CancellationToken cancellationToken = default);
}
