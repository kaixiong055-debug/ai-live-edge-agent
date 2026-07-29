using Microsoft.Web.WebView2.Core;
using Microsoft.Web.WebView2.Wpf;

namespace AiLiveEdge.Desktop.Renderer;

public sealed class RendererManager : IRendererManager
{
    public static readonly Uri RendererUri = new("http://127.0.0.1:18081/renderer/index.html");

    private static readonly Uri RuntimeUri = new("http://127.0.0.1:18081/local-api/runtime");
    private static readonly Uri RecentActionsUri = new(
        "http://127.0.0.1:18081/local-api/runtime/actions/recent");
    private readonly WebView2 _rendererWebView;
    private readonly HttpClient _httpClient = new() { Timeout = TimeSpan.FromSeconds(4) };
    private readonly SemaphoreSlim _operationLock = new(1, 1);
    private TaskCompletionSource<bool>? _navigationCompletion;
    private bool _webViewInitialized;
    private bool _disposed;

    public RendererManager(WebView2 rendererWebView)
    {
        _rendererWebView = rendererWebView;
        _rendererWebView.NavigationCompleted += RendererWebView_NavigationCompleted;
    }

    public RendererStatus CurrentStatus { get; private set; } = RendererStatus.Disconnected;

    public async Task<RendererStatus> EnsureStartedAsync(
        CoreWebView2Environment environment,
        CancellationToken cancellationToken = default)
    {
        ObjectDisposedException.ThrowIf(_disposed, this);
        await _operationLock.WaitAsync(cancellationToken);
        try
        {
            var current = await ReadRuntimeStatus(cancellationToken);
            if (current.State == RendererConnectionState.CONNECTED)
            {
                return SetStatus(current);
            }

            SetStatus(current with
            {
                State = RendererConnectionState.CONNECTING,
                ErrorMessage = null,
                LastUpdatedAt = DateTimeOffset.UtcNow
            });

            using var pageRequest = new HttpRequestMessage(HttpMethod.Get, RendererUri);
            using var pageResponse = await _httpClient.SendAsync(
                pageRequest,
                HttpCompletionOption.ResponseHeadersRead,
                cancellationToken);
            if (!pageResponse.IsSuccessStatusCode)
            {
                return SetStatus(new RendererStatus(
                    RendererConnectionState.FAILED,
                    false,
                    0,
                    current.LastAction,
                    DateTimeOffset.UtcNow,
                    $"Renderer page returned HTTP {(int)pageResponse.StatusCode}."));
            }

            if (!_webViewInitialized)
            {
                await _rendererWebView.EnsureCoreWebView2Async(environment);
                var settings = _rendererWebView.CoreWebView2.Settings;
                settings.AreDevToolsEnabled = false;
                settings.AreDefaultContextMenusEnabled = false;
                settings.AreBrowserAcceleratorKeysEnabled = false;
                settings.IsStatusBarEnabled = false;
                _rendererWebView.CoreWebView2.ProcessFailed += RendererWebView_ProcessFailed;
                _webViewInitialized = true;
            }

            _navigationCompletion = new TaskCompletionSource<bool>(
                TaskCreationOptions.RunContinuationsAsynchronously);
            _rendererWebView.Source = RendererUri;
            var navigationSucceeded = await _navigationCompletion.Task.WaitAsync(
                TimeSpan.FromSeconds(8),
                cancellationToken);
            if (!navigationSucceeded)
            {
                return SetStatus(new RendererStatus(
                    RendererConnectionState.FAILED,
                    true,
                    0,
                    current.LastAction,
                    DateTimeOffset.UtcNow,
                    "Renderer page navigation failed."));
            }

            for (var attempt = 0; attempt < 20; attempt++)
            {
                await Task.Delay(TimeSpan.FromMilliseconds(250), cancellationToken);
                current = await ReadRuntimeStatus(cancellationToken);
                if (current.State == RendererConnectionState.CONNECTED)
                {
                    return SetStatus(current);
                }
            }

            return SetStatus(current with
            {
                State = RendererConnectionState.FAILED,
                PageAvailable = true,
                ErrorMessage = "Renderer WebSocket did not connect in time.",
                LastUpdatedAt = DateTimeOffset.UtcNow
            });
        }
        catch (Exception ex) when (ex is HttpRequestException or TimeoutException or TaskCanceledException)
        {
            DesktopLogger.Error("Renderer startup failed.", ex);
            return SetStatus(new RendererStatus(
                RendererConnectionState.FAILED,
                false,
                0,
                CurrentStatus.LastAction,
                DateTimeOffset.UtcNow,
                ex.Message));
        }
        finally
        {
            _navigationCompletion = null;
            _operationLock.Release();
        }
    }

    public async Task<RendererStatus> GetStatusAsync(CancellationToken cancellationToken = default)
    {
        ObjectDisposedException.ThrowIf(_disposed, this);
        try
        {
            return SetStatus(await ReadRuntimeStatus(cancellationToken));
        }
        catch (Exception ex) when (ex is HttpRequestException or TaskCanceledException)
        {
            return SetStatus(new RendererStatus(
                RendererConnectionState.DISCONNECTED,
                false,
                0,
                CurrentStatus.LastAction,
                DateTimeOffset.UtcNow,
                ex.Message));
        }
    }

    private async Task<RendererStatus> ReadRuntimeStatus(CancellationToken cancellationToken)
    {
        using var response = await _httpClient.GetAsync(
            RuntimeUri,
            HttpCompletionOption.ResponseHeadersRead,
            cancellationToken);
        response.EnsureSuccessStatusCode();
        await using var stream = await response.Content.ReadAsStreamAsync(cancellationToken);
        using var document = await JsonDocument.ParseAsync(
            stream,
            cancellationToken: cancellationToken);
        var root = document.RootElement;
        var connectionCount = root.TryGetProperty("rendererConnectionCount", out var countElement)
                              && countElement.TryGetInt32(out var count)
            ? count
            : 0;
        var lastAction = ReadString(root, "mediaLastAction");
        if (root.TryGetProperty("currentAction", out var currentAction)
            && currentAction.ValueKind == JsonValueKind.Object)
        {
            lastAction = ReadString(currentAction, "actionCode") ?? lastAction;
        }
        if (string.IsNullOrWhiteSpace(lastAction))
        {
            lastAction = await ReadLastAction(cancellationToken);
        }

        return new RendererStatus(
            connectionCount > 0
                ? RendererConnectionState.CONNECTED
                : _webViewInitialized
                    ? RendererConnectionState.CONNECTING
                    : RendererConnectionState.DISCONNECTED,
            true,
            connectionCount,
            lastAction,
            DateTimeOffset.UtcNow);
    }

    private async Task<string?> ReadLastAction(CancellationToken cancellationToken)
    {
        using var response = await _httpClient.GetAsync(
            RecentActionsUri,
            HttpCompletionOption.ResponseHeadersRead,
            cancellationToken);
        if (!response.IsSuccessStatusCode)
        {
            return CurrentStatus.LastAction;
        }
        await using var stream = await response.Content.ReadAsStreamAsync(cancellationToken);
        using var document = await JsonDocument.ParseAsync(
            stream,
            cancellationToken: cancellationToken);
        var root = document.RootElement;
        if (root.ValueKind != JsonValueKind.Array || root.GetArrayLength() == 0)
        {
            return CurrentStatus.LastAction;
        }
        return ReadString(root[0], "actionCode") ?? CurrentStatus.LastAction;
    }

    private static string? ReadString(JsonElement element, string propertyName)
    {
        return element.TryGetProperty(propertyName, out var value)
               && value.ValueKind == JsonValueKind.String
            ? value.GetString()
            : null;
    }

    private RendererStatus SetStatus(RendererStatus status)
    {
        CurrentStatus = status;
        return status;
    }

    private void RendererWebView_NavigationCompleted(
        object? sender,
        CoreWebView2NavigationCompletedEventArgs e)
    {
        _navigationCompletion?.TrySetResult(e.IsSuccess);
    }

    private void RendererWebView_ProcessFailed(object? sender, CoreWebView2ProcessFailedEventArgs e)
    {
        SetStatus(new RendererStatus(
            RendererConnectionState.FAILED,
            true,
            0,
            CurrentStatus.LastAction,
            DateTimeOffset.UtcNow,
            $"WebView2 renderer process failed: {e.ProcessFailedKind}"));
    }

    public void Dispose()
    {
        if (_disposed)
        {
            return;
        }
        _disposed = true;
        _rendererWebView.NavigationCompleted -= RendererWebView_NavigationCompleted;
        if (_webViewInitialized)
        {
            _rendererWebView.CoreWebView2.ProcessFailed -= RendererWebView_ProcessFailed;
            _rendererWebView.CoreWebView2.Navigate("about:blank");
        }
        _httpClient.Dispose();
        _operationLock.Dispose();
    }
}
