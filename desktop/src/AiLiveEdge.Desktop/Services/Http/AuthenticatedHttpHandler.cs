using AiLiveEdge.Desktop.Models.Auth;

namespace AiLiveEdge.Desktop.Services.Http;

public sealed class AuthenticatedHttpHandler : DelegatingHandler
{
    private readonly Func<AuthSession?> _getSession;
    private readonly Func<CancellationToken, Task<AuthSession?>> _refreshSession;
    private readonly Func<CancellationToken, Task<AuthSession?>> _forceRefreshSession;

    public AuthenticatedHttpHandler(
        Func<AuthSession?> getSession,
        Func<CancellationToken, Task<AuthSession?>> refreshSession,
        Func<CancellationToken, Task<AuthSession?>> forceRefreshSession)
    {
        _getSession = getSession;
        _refreshSession = refreshSession;
        _forceRefreshSession = forceRefreshSession;
    }

    protected override async Task<HttpResponseMessage> SendAsync(
        HttpRequestMessage request,
        CancellationToken cancellationToken)
    {
        var session = _getSession();
        if (session is not null && session.AccessTokenExpiresAt <= DateTimeOffset.UtcNow.AddMinutes(1))
        {
            session = await _refreshSession(cancellationToken);
        }
        if (session is not null)
        {
            request.Headers.Authorization = new System.Net.Http.Headers.AuthenticationHeaderValue(
                string.IsNullOrWhiteSpace(session.TokenType) ? "Bearer" : session.TokenType,
                session.AccessToken);
        }

        using var snapshot = await BufferedRequestSnapshot.CreateAsync(request, cancellationToken);
        var response = await base.SendAsync(request, cancellationToken);
        if (response.StatusCode != HttpStatusCode.Unauthorized)
        {
            return response;
        }

        response.Dispose();
        var refreshed = await _forceRefreshSession(cancellationToken);
        if (refreshed is null)
        {
            return new HttpResponseMessage(HttpStatusCode.Unauthorized)
            {
                RequestMessage = request,
                ReasonPhrase = "Session expired"
            };
        }

        using var retry = snapshot.CreateRequest();
        retry.Headers.Authorization = new System.Net.Http.Headers.AuthenticationHeaderValue(
            string.IsNullOrWhiteSpace(refreshed.TokenType) ? "Bearer" : refreshed.TokenType,
            refreshed.AccessToken);
        return await base.SendAsync(retry, cancellationToken);
    }

    private sealed class BufferedRequestSnapshot : IDisposable
    {
        private readonly byte[]? _contentBytes;
        private readonly string? _contentType;

        private BufferedRequestSnapshot(HttpRequestMessage request, byte[]? contentBytes, string? contentType)
        {
            Method = request.Method;
            RequestUri = request.RequestUri;
            _contentBytes = contentBytes;
            _contentType = contentType;
            foreach (var header in request.Headers)
            {
                Headers.Add(header.Key, header.Value.ToArray());
            }
        }

        private HttpMethod Method { get; }

        private Uri? RequestUri { get; }

        private Dictionary<string, string[]> Headers { get; } = new(StringComparer.OrdinalIgnoreCase);

        public static async Task<BufferedRequestSnapshot> CreateAsync(
            HttpRequestMessage request,
            CancellationToken cancellationToken)
        {
            byte[]? contentBytes = null;
            string? contentType = null;
            if (request.Content is not null)
            {
                contentBytes = await request.Content.ReadAsByteArrayAsync(cancellationToken);
                contentType = request.Content.Headers.ContentType?.ToString();
            }
            return new BufferedRequestSnapshot(request, contentBytes, contentType);
        }

        public HttpRequestMessage CreateRequest()
        {
            var request = new HttpRequestMessage(Method, RequestUri);
            foreach (var header in Headers.Where(header => !string.Equals(header.Key, "Authorization",
                         StringComparison.OrdinalIgnoreCase)))
            {
                request.Headers.TryAddWithoutValidation(header.Key, header.Value);
            }
            if (_contentBytes is not null)
            {
                request.Content = new ByteArrayContent(_contentBytes);
                if (!string.IsNullOrWhiteSpace(_contentType))
                {
                    request.Content.Headers.TryAddWithoutValidation("Content-Type", _contentType);
                }
            }
            return request;
        }

        public void Dispose()
        {
        }
    }
}
