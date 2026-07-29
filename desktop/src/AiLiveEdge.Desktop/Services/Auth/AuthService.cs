using AiLiveEdge.Desktop.Models.Auth;
using AiLiveEdge.Desktop.Services.Device;
using AiLiveEdge.Desktop.Services.Http;
using AiLiveEdge.Desktop.Services.Session;
using AiLiveEdge.Desktop.Services.Versioning;

namespace AiLiveEdge.Desktop.Services.Auth;

public sealed class AuthService : IAuthService
{
    private static readonly JsonSerializerOptions JsonOptions = new(JsonSerializerDefaults.Web)
    {
        PropertyNameCaseInsensitive = true
    };

    private readonly ICloudApiSettingsService _settingsService;
    private readonly IDeviceIdentityService _deviceIdentityService;
    private readonly ISessionService _sessionService;
    private readonly IAppVersionService _versionService;
    private readonly SemaphoreSlim _refreshLock = new(1, 1);

    public AuthService(
        ICloudApiSettingsService settingsService,
        IDeviceIdentityService deviceIdentityService,
        ISessionService sessionService,
        IAppVersionService versionService)
    {
        _settingsService = settingsService;
        _deviceIdentityService = deviceIdentityService;
        _sessionService = sessionService;
        _versionService = versionService;
    }

    public AuthSession? CurrentSession => _sessionService.Current;

    public async Task<AuthSession?> RestoreSessionAsync(CancellationToken cancellationToken = default)
    {
        await _settingsService.LoadAsync(cancellationToken);
        var stored = await _sessionService.LoadAsync(cancellationToken);
        if (stored?.HasRefreshToken != true)
        {
            await _sessionService.ClearAsync(cancellationToken);
            return null;
        }

        try
        {
            await RefreshCurrentSessionAsync(cancellationToken);
            return await LoadCurrentUserAsync(cancellationToken);
        }
        catch (Exception ex) when (ex is ApiException or HttpRequestException or TaskCanceledException or InvalidOperationException)
        {
            DesktopLogger.Error("Session restore failed.", ex);
            await _sessionService.ExpireAsync("登录状态已失效，请重新登录", cancellationToken);
            return null;
        }
    }

    public async Task<AuthSession> LoginAsync(LoginRequest request, CancellationToken cancellationToken = default)
    {
        if (string.IsNullOrWhiteSpace(request.Username))
        {
            throw new InvalidOperationException("账号不能为空。");
        }
        if (string.IsNullOrWhiteSpace(request.Password))
        {
            throw new InvalidOperationException("密码不能为空。");
        }

        var device = await _deviceIdentityService.GetOrCreateAsync(cancellationToken);
        var payload = new
        {
            username = request.Username.Trim(),
            password = request.Password,
            deviceCode = device.DeviceCode,
            deviceName = device.DeviceName,
            deviceFingerprint = device.DeviceFingerprint,
            osName = device.OsName,
            osVersion = device.OsVersion,
            agentVersion = _versionService.Version
        };

        var response = await SendRawAsync<LoginResponse>(
            HttpMethod.Post,
            "ai-live/auth/login",
            payload,
            cancellationToken);
        var session = CreateSession(response);
        if (request.RememberLogin)
        {
            await _sessionService.SaveAsync(session, cancellationToken);
        }
        else
        {
            _sessionService.SetCurrent(session);
        }
        DesktopLogger.Info($"Agent login succeeded. userId={session.User?.Id}, tenantId={session.Tenant?.Id}, deviceId={session.Device?.Id}");
        return session;
    }

    public async Task<AuthSession?> RefreshCurrentSessionAsync(CancellationToken cancellationToken = default)
    {
        return await RefreshCurrentSessionCoreAsync(force: false, cancellationToken);
    }

    private async Task<AuthSession?> ForceRefreshCurrentSessionAsync(CancellationToken cancellationToken = default)
    {
        return await RefreshCurrentSessionCoreAsync(force: true, cancellationToken);
    }

    private async Task<AuthSession?> RefreshCurrentSessionCoreAsync(
        bool force,
        CancellationToken cancellationToken)
    {
        await _refreshLock.WaitAsync(cancellationToken);
        try
        {
            var current = _sessionService.Current;
            if (current?.HasRefreshToken != true)
            {
                return null;
            }
            if (!force && current.AccessTokenExpiresAt > DateTimeOffset.UtcNow.AddMinutes(1))
            {
                return current;
            }
            var response = await SendRawAsync<LoginResponse>(
                HttpMethod.Post,
                "ai-live/auth/refresh",
                new RefreshRequest(current.RefreshToken),
                cancellationToken);
            var refreshed = CreateSession(response);
            await StoreSessionAsync(refreshed, cancellationToken);
            DesktopLogger.Info($"Agent token refreshed. userId={refreshed.User?.Id}, tenantId={refreshed.Tenant?.Id}");
            return refreshed;
        }
        catch (ApiException ex) when (ex.IsSessionExpired)
        {
            await _sessionService.ExpireAsync("登录状态已失效，请重新登录", cancellationToken);
            return null;
        }
        finally
        {
            _refreshLock.Release();
        }
    }

    public async Task<AuthSession> LoadCurrentUserAsync(CancellationToken cancellationToken = default)
    {
        var response = await SendAuthorizedAsync<LoginResponse>(
            HttpMethod.Get,
            "ai-live/auth/me",
            null,
            cancellationToken);
        var session = CreateSession(response);
        await StoreSessionAsync(session, cancellationToken);
        return session;
    }

    public async Task<CurrentLicense?> LoadCurrentLicenseAsync(CancellationToken cancellationToken = default)
    {
        var license = await SendAuthorizedAsync<CurrentLicense>(
            HttpMethod.Get,
            "ai-live/license/current",
            null,
            cancellationToken);
        if (_sessionService.Current is not null)
        {
            await StoreSessionAsync(_sessionService.Current with { License = license }, cancellationToken);
        }
        return license;
    }

    public async Task LogoutAsync(CancellationToken cancellationToken = default)
    {
        try
        {
            if (_sessionService.Current is not null)
            {
                await SendAuthorizedAsync<bool>(HttpMethod.Post, "ai-live/auth/logout", new { }, cancellationToken);
            }
        }
        catch (Exception ex) when (ex is ApiException or HttpRequestException or TaskCanceledException or InvalidOperationException)
        {
            DesktopLogger.Error("Agent logout request failed; clearing local session.", ex);
        }
        finally
        {
            await _sessionService.ClearAsync(cancellationToken);
        }
    }

    public async Task SendHeartbeatAsync(
        string? rendererStatus,
        string? webSocketStatus,
        string? currentMode,
        CancellationToken cancellationToken = default)
    {
        await SendAuthorizedAsync<bool>(
            HttpMethod.Post,
            "ai-live/agent/heartbeat",
            new
            {
                agentVersion = _versionService.Version,
                rendererStatus,
                webSocketStatus,
                currentMode
            },
            cancellationToken);
    }

    private async Task<T> SendRawAsync<T>(
        HttpMethod method,
        string path,
        object? payload,
        CancellationToken cancellationToken)
    {
        using var httpClient = CreateRawHttpClient();
        using var request = CreateRequest(method, path, payload);
        using var response = await SendWithNetworkErrorsAsync(httpClient, request, cancellationToken);
        return await ReadCommonResult<T>(response, cancellationToken);
    }

    private async Task<T> SendAuthorizedAsync<T>(
        HttpMethod method,
        string path,
        object? payload,
        CancellationToken cancellationToken)
    {
        using var httpClient = CreateAuthenticatedHttpClient();
        using var request = CreateRequest(method, path, payload);
        using var response = await SendWithNetworkErrorsAsync(httpClient, request, cancellationToken);
        try
        {
            return await ReadCommonResult<T>(response, cancellationToken);
        }
        catch (ApiException ex) when (ex.IsSessionExpired || ex.IsDeviceOrLicenseInvalid)
        {
            await _sessionService.ExpireAsync(ex.Message, cancellationToken);
            throw;
        }
    }

    private HttpClient CreateRawHttpClient() => new()
    {
        BaseAddress = _settingsService.GetRequiredBaseUri(),
        Timeout = TimeSpan.FromSeconds(Math.Clamp(_settingsService.Current.TimeoutSeconds, 5, 120))
    };

    private HttpClient CreateAuthenticatedHttpClient() => new(new AuthenticatedHttpHandler(
        () => _sessionService.Current,
        RefreshCurrentSessionAsync,
        ForceRefreshCurrentSessionAsync)
    {
        InnerHandler = new HttpClientHandler()
    })
    {
        BaseAddress = _settingsService.GetRequiredBaseUri(),
        Timeout = TimeSpan.FromSeconds(Math.Clamp(_settingsService.Current.TimeoutSeconds, 5, 120))
    };

    private static async Task<HttpResponseMessage> SendWithNetworkErrorsAsync(
        HttpClient httpClient,
        HttpRequestMessage request,
        CancellationToken cancellationToken)
    {
        try
        {
            return await httpClient.SendAsync(request, cancellationToken);
        }
        catch (TaskCanceledException ex) when (!cancellationToken.IsCancellationRequested)
        {
            throw new InvalidOperationException("连接云端服务超时，请检查网络或服务器地址。", ex);
        }
        catch (HttpRequestException ex)
        {
            throw new InvalidOperationException("云端服务连接失败，请检查网络或服务器地址。", ex);
        }
    }

    private static HttpRequestMessage CreateRequest(HttpMethod method, string path, object? payload)
    {
        var request = new HttpRequestMessage(method, path);
        if (payload is not null)
        {
            request.Content = new StringContent(
                JsonSerializer.Serialize(payload, JsonOptions),
                Encoding.UTF8,
                "application/json");
        }
        return request;
    }

    private async Task StoreSessionAsync(AuthSession session, CancellationToken cancellationToken)
    {
        if (_sessionService.IsPersistent)
        {
            await _sessionService.SaveAsync(session, cancellationToken);
            return;
        }

        _sessionService.SetCurrent(session);
    }

    private static async Task<T> ReadCommonResult<T>(
        HttpResponseMessage response,
        CancellationToken cancellationToken)
    {
        var body = await response.Content.ReadAsStringAsync(cancellationToken);
        CommonResult<T>? result = null;
        if (!string.IsNullOrWhiteSpace(body))
        {
            try
            {
                result = JsonSerializer.Deserialize<CommonResult<T>>(body, JsonOptions);
            }
            catch (JsonException ex)
            {
                throw new ApiException(null, ex.Message, response.StatusCode);
            }
        }

        if (!response.IsSuccessStatusCode || result?.Code != 0)
        {
            throw new ApiException(result?.Code, result?.Msg, response.StatusCode);
        }
        return result.Data!;
    }

    private static AuthSession CreateSession(LoginResponse response)
    {
        if (string.IsNullOrWhiteSpace(response.AccessToken)
            || string.IsNullOrWhiteSpace(response.RefreshToken))
        {
            throw new ApiException(null, "服务端未返回完整登录凭证。", null);
        }
        var now = DateTimeOffset.UtcNow;
        return new AuthSession(
            response.AccessToken,
            response.RefreshToken,
            now.AddSeconds(Math.Max(0, response.ExpiresIn ?? 0)),
            now.AddSeconds(Math.Max(0, response.RefreshExpiresIn ?? 0)),
            string.IsNullOrWhiteSpace(response.TokenType) ? "Bearer" : response.TokenType,
            response.User,
            response.Tenant,
            response.Device,
            response.License,
            response.Capabilities);
    }

    private sealed record CommonResult<T>(
        [property: JsonPropertyName("code")] int Code,
        [property: JsonPropertyName("msg")] string? Msg,
        [property: JsonPropertyName("data")] T? Data);
}
