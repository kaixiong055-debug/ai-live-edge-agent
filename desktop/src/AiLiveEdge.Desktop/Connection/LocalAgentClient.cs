using System.Net.Http.Headers;

namespace AiLiveEdge.Desktop.Connection;

public sealed class LocalAgentClient : IAgentClient
{
    public static readonly Uri DefaultBaseAddress = new("http://127.0.0.1:18081/");

    private readonly HttpClient _httpClient;

    public LocalAgentClient(Uri? baseAddress = null)
    {
        BaseAddress = NormalizeBaseAddress(baseAddress ?? DefaultBaseAddress);
        _httpClient = new HttpClient
        {
            BaseAddress = BaseAddress,
            Timeout = TimeSpan.FromSeconds(5)
        };
    }

    public AgentConnectionMode Mode => AgentConnectionMode.Local;

    public Uri BaseAddress { get; }

    public Task<JsonElement> GetRuntimeStatus(CancellationToken cancellationToken = default) =>
        Send(HttpMethod.Get, "local-api/runtime", cancellationToken: cancellationToken);

    public Task<JsonElement> GetAsrStatus(CancellationToken cancellationToken = default) =>
        Send(HttpMethod.Get, "local-api/asr/status", cancellationToken: cancellationToken);

    public Task<JsonElement> StartAsr(CancellationToken cancellationToken = default) =>
        Send(HttpMethod.Post, "local-api/asr/connect", cancellationToken: cancellationToken);

    public Task<JsonElement> StopAsr(CancellationToken cancellationToken = default) =>
        Send(HttpMethod.Post, "local-api/asr/disconnect", cancellationToken: cancellationToken);

    public Task<JsonElement> SendCommand(string command, CancellationToken cancellationToken = default) =>
        SendJson(HttpMethod.Post, "local-api/actions/test", new { actionCode = command }, cancellationToken);

    public Task<JsonElement> GetActionStatus(CancellationToken cancellationToken = default) =>
        Send(HttpMethod.Get, "local-api/runtime/actions/recent", cancellationToken: cancellationToken);

    public Task<JsonElement> GetMediaStatus(CancellationToken cancellationToken = default) =>
        Send(HttpMethod.Get, "local-api/assets", cancellationToken: cancellationToken);

    public Task<JsonElement> GetCommands(CancellationToken cancellationToken = default) =>
        Send(HttpMethod.Get, "local-api/commands", cancellationToken: cancellationToken);

    public Task<JsonElement> GetAssets(CancellationToken cancellationToken = default) =>
        GetMediaStatus(cancellationToken);

    public Task<JsonElement> StartAudioTest(CancellationToken cancellationToken = default) =>
        Send(HttpMethod.Post, "local-api/audio/test", cancellationToken: cancellationToken);

    public async Task<JsonElement> UploadAsset(
        string fileName,
        string contentType,
        byte[] content,
        CancellationToken cancellationToken = default)
    {
        using var form = new MultipartFormDataContent();
        using var fileContent = new ByteArrayContent(content);
        fileContent.Headers.ContentType = MediaTypeHeaderValue.Parse(
            string.IsNullOrWhiteSpace(contentType) ? "application/octet-stream" : contentType);
        form.Add(fileContent, "file", fileName);
        return await Send(HttpMethod.Post, "local-api/assets/upload", form, cancellationToken);
    }

    private Task<JsonElement> SendJson(
        HttpMethod method,
        string path,
        object value,
        CancellationToken cancellationToken)
    {
        var content = new StringContent(
            JsonSerializer.Serialize(value), Encoding.UTF8, "application/json");
        return Send(method, path, content, cancellationToken);
    }

    private async Task<JsonElement> Send(
        HttpMethod method,
        string path,
        HttpContent? content = null,
        CancellationToken cancellationToken = default)
    {
        using var request = new HttpRequestMessage(method, path) { Content = content };
        using var response = await _httpClient.SendAsync(request, cancellationToken);
        var responseText = await response.Content.ReadAsStringAsync(cancellationToken);
        if (!response.IsSuccessStatusCode)
        {
            throw new HttpRequestException(
                ReadError(responseText, response.ReasonPhrase),
                null,
                response.StatusCode);
        }

        return ParseJson(responseText);
    }

    private static JsonElement ParseJson(string value)
    {
        if (string.IsNullOrWhiteSpace(value))
        {
            return JsonSerializer.SerializeToElement(new { });
        }

        using var document = JsonDocument.Parse(value);
        return document.RootElement.Clone();
    }

    private static string ReadError(string responseText, string? fallback)
    {
        try
        {
            using var document = JsonDocument.Parse(responseText);
            var root = document.RootElement;
            if (root.TryGetProperty("error", out var error))
            {
                return error.GetString() ?? fallback ?? "Agent request failed.";
            }
            if (root.TryGetProperty("message", out var message))
            {
                return message.GetString() ?? fallback ?? "Agent request failed.";
            }
        }
        catch (JsonException)
        {
            // Preserve the HTTP fallback for non-JSON error responses.
        }
        return fallback ?? "Agent request failed.";
    }

    private static Uri NormalizeBaseAddress(Uri baseAddress)
    {
        if (!baseAddress.IsAbsoluteUri
            || baseAddress.Scheme != Uri.UriSchemeHttp
            || !baseAddress.IsLoopback)
        {
            throw new ArgumentException(
                "Local Agent address must be an absolute loopback HTTP URL.",
                nameof(baseAddress));
        }

        return new Uri(baseAddress.AbsoluteUri.TrimEnd('/') + "/");
    }

    public void Dispose() => _httpClient.Dispose();
}
