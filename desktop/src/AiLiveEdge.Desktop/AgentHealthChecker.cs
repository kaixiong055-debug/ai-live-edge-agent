using System.Net.Sockets;
using System.Text.Json;
using System.Net.Http;
using AiLiveEdge.Desktop.Models;

namespace AiLiveEdge.Desktop;

public sealed class AgentHealthChecker : IDisposable
{
    public static readonly Uri RuntimeUri = new("http://127.0.0.1:18081/local-api/runtime");
    private readonly HttpClient _httpClient = new()
    {
        Timeout = TimeSpan.FromSeconds(2)
    };

    public async Task<AgentHealthResult> CheckAsync(CancellationToken cancellationToken = default)
    {
        try
        {
            using var response = await _httpClient.GetAsync(RuntimeUri,
                HttpCompletionOption.ResponseHeadersRead, cancellationToken);
            if (!response.IsSuccessStatusCode)
            {
                return new AgentHealthResult(AgentHealthState.PortOccupiedByOtherService,
                    $"端口 18081 返回了非预期 HTTP 状态：{(int)response.StatusCode}");
            }

            await using var stream = await response.Content.ReadAsStreamAsync(cancellationToken);
            using var document = await JsonDocument.ParseAsync(stream, cancellationToken: cancellationToken);
            var root = document.RootElement;

            var serviceStatus = ReadString(root, "serviceStatus");
            var version = ReadString(root, "applicationVersion");
            var serverPort = root.TryGetProperty("serverPort", out var portElement) && portElement.TryGetInt32(out var port)
                ? port
                : 0;

            if (!string.Equals(serviceStatus, "UP", StringComparison.OrdinalIgnoreCase)
                || string.IsNullOrWhiteSpace(version)
                || serverPort != 18081)
            {
                return new AgentHealthResult(AgentHealthState.PortOccupiedByOtherService,
                    "端口 18081 返回的不是 AI Live Edge Agent Runtime API。");
            }

            return new AgentHealthResult(AgentHealthState.Healthy, "Agent 已连接。", version);
        }
        catch (OperationCanceledException) when (!cancellationToken.IsCancellationRequested)
        {
            return await ClassifyUnavailableAsync("Agent Runtime API 请求超时。", cancellationToken);
        }
        catch (HttpRequestException ex)
        {
            DesktopLogger.Info($"Agent health request unavailable: {ex.Message}");
            return await ClassifyUnavailableAsync("Agent 尚未启动。", cancellationToken);
        }
        catch (JsonException)
        {
            return new AgentHealthResult(AgentHealthState.PortOccupiedByOtherService,
                "端口 18081 返回了无法识别的数据。");
        }
    }

    private static string? ReadString(JsonElement root, string propertyName)
    {
        return root.TryGetProperty(propertyName, out var value) && value.ValueKind == JsonValueKind.String
            ? value.GetString()
            : null;
    }

    private static async Task<AgentHealthResult> ClassifyUnavailableAsync(
        string unavailableMessage, CancellationToken cancellationToken)
    {
        using var tcpClient = new TcpClient();
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        timeout.CancelAfter(TimeSpan.FromMilliseconds(500));
        try
        {
            await tcpClient.ConnectAsync("127.0.0.1", 18081, timeout.Token);
            return new AgentHealthResult(AgentHealthState.PortOccupiedByOtherService,
                "端口 18081 已被其他程序占用。");
        }
        catch (OperationCanceledException) when (!cancellationToken.IsCancellationRequested)
        {
            return new AgentHealthResult(AgentHealthState.Unavailable, unavailableMessage);
        }
        catch (SocketException)
        {
            return new AgentHealthResult(AgentHealthState.Unavailable, unavailableMessage);
        }
    }

    public void Dispose() => _httpClient.Dispose();
}
