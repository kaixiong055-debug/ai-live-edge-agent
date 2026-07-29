using AiLiveEdge.Desktop.Models.Auth;
using AiLiveEdge.Desktop.Services.Storage;

namespace AiLiveEdge.Desktop.Services.Session;

public sealed class SessionService : ISessionService
{
    private readonly ISecureStorage _secureStorage;
    private readonly SemaphoreSlim _expireLock = new(1, 1);
    private bool _expiredRaised;

    public SessionService(ISecureStorage secureStorage)
    {
        _secureStorage = secureStorage;
    }

    public AuthSession? Current { get; private set; }

    public bool IsPersistent { get; private set; }

    public event EventHandler<string>? SessionExpired;

    public async Task<AuthSession?> LoadAsync(CancellationToken cancellationToken = default)
    {
        Current = await _secureStorage.LoadAsync<AuthSession>(AppPaths.SecureSessionFile, cancellationToken);
        IsPersistent = Current is not null;
        _expiredRaised = false;
        return Current;
    }

    public async Task SaveAsync(AuthSession session, CancellationToken cancellationToken = default)
    {
        Current = session;
        IsPersistent = true;
        _expiredRaised = false;
        await _secureStorage.SaveAsync(AppPaths.SecureSessionFile, session, cancellationToken);
    }

    public async Task ClearAsync(CancellationToken cancellationToken = default)
    {
        Current = null;
        IsPersistent = false;
        await _secureStorage.DeleteAsync(AppPaths.SecureSessionFile, cancellationToken);
    }

    public void SetCurrent(AuthSession? session)
    {
        Current = session;
        IsPersistent = false;
        if (session is not null)
        {
            _expiredRaised = false;
        }
    }

    public async Task ExpireAsync(string reason, CancellationToken cancellationToken = default)
    {
        await _expireLock.WaitAsync(cancellationToken);
        try
        {
            if (_expiredRaised)
            {
                return;
            }
            _expiredRaised = true;
            await ClearAsync(cancellationToken);
            SessionExpired?.Invoke(this, reason);
        }
        finally
        {
            _expireLock.Release();
        }
    }
}
