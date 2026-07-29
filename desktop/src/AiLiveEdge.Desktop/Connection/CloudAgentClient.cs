using System.Net.Http.Headers;

namespace AiLiveEdge.Desktop.Connection;

public sealed class CloudAgentClient : IAgentClient
{
    private readonly HttpClient _httpClient;

    public CloudAgentClient(
        Uri? baseAddress,
        string? deviceId,
        string? token,
        string? tenantId)
    {
        BaseAddress = NormalizeBaseAddress(baseAddress
            ?? throw new ArgumentException("Cloud Agent address must be configured.", nameof(baseAddress)));
        DeviceId = deviceId?.Trim() ?? string.Empty;
        Token = token?.Trim() ?? string.Empty;
        TenantId = tenantId?.Trim() ?? string.Empty;
        _httpClient = new HttpClient
        {
            BaseAddress = BaseAddress,
            Timeout = TimeSpan.FromSeconds(15)
        };
    }

    public AgentConnectionMode Mode => AgentConnectionMode.Cloud;

    public Uri BaseAddress { get; }

    public string DeviceId { get; }

    public string Token { get; }

    public string TenantId { get; }

    public Task<JsonElement> GetRuntimeStatus(CancellationToken cancellationToken = default) =>
        Send(HttpMethod.Get, "v1/agent/runtime", cancellationToken: cancellationToken);

    public Task<JsonElement> GetAsrStatus(CancellationToken cancellationToken = default) =>
        Send(HttpMethod.Get, "v1/agent/asr/status", cancellationToken: cancellationToken);

    public Task<JsonElement> StartAsr(CancellationToken cancellationToken = default) =>
        Send(HttpMethod.Post, "v1/agent/asr/connect", cancellationToken: cancellationToken);

    public Task<JsonElement> StopAsr(CancellationToken cancellationToken = default) =>
        Send(HttpMethod.Post, "v1/agent/asr/disconnect", cancellationToken: cancellationToken);

    public Task<JsonElement> SendCommand(string command, CancellationToken cancellationToken = default) =>
        SendJson(HttpMethod.Post, "v1/agent/actions/test", new { actionCode = command }, cancellationToken);

    public Task<JsonElement> GetActionStatus(CancellationToken cancellationToken = default) =>
        Send(HttpMethod.Get, "v1/agent/actions/recent", cancellationToken: cancellationToken);

    public Task<JsonElement> GetMediaStatus(CancellationToken cancellationToken = default) =>
        Send(HttpMethod.Get, "v1/agent/assets", cancellationToken: cancellationToken);

    public Task<JsonElement> GetCommands(CancellationToken cancellationToken = default) =>
        Send(HttpMethod.Get, "v1/agent/commands", cancellationToken: cancellationToken);

    public Task<JsonElement> GetAssets(CancellationToken cancellationToken = default) =>
        GetMediaStatus(cancellationToken);

    public Task<JsonElement> StartAudioTest(CancellationToken cancellationToken = default) =>
        Send(HttpMethod.Post, "v1/agent/audio/test", cancellationToken: cancellationToken);

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
        return await Send(HttpMethod.Post, "v1/agent/assets/upload", form, cancellationToken);
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
        if (!string.IsNullOrWhiteSpace(Token))
        {
            request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", Token);
        }
        if (!string.IsNullOrWhiteSpace(DeviceId))
        {
            request.Headers.TryAddWithoutValidation("X-Device-Id", DeviceId);
        }
        if (!string.IsNullOrWhiteSpace(TenantId))
        {
            request.Headers.TryAddWithoutValidation("X-Tenant-Id", TenantId);
        }

        using var response = await _httpClient.SendAsync(request, cancellationToken);
        var responseText = await response.Content.ReadAsStringAsync(cancellationToken);
        if (!response.IsSuccessStatusCode)
        {
            throw new HttpRequestException(
                $"Cloud request failed ({(int)response.StatusCode} {response.ReasonPhrase}).",
                null,
                response.StatusCode);
        }

        if (string.IsNullOrWhiteSpace(responseText))
        {
            return JsonSerializer.SerializeToElement(new { });
        }
        using var document = JsonDocument.Parse(responseText);
        return document.RootElement.Clone();
    }

    private static Uri NormalizeBaseAddress(Uri baseAddress)
    {
        if (!baseAddress.IsAbsoluteUri || baseAddress.Scheme != Uri.UriSchemeHttps)
        {
            throw new ArgumentException("Cloud Agent address must be an absolute HTTPS URL.", nameof(baseAddress));
        }
        return new Uri(baseAddress.AbsoluteUri.TrimEnd('/') + "/");
    }

    public void Dispose() => _httpClient.Dispose();
}
