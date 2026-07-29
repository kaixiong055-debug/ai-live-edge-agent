using Microsoft.Web.WebView2.Core;

namespace AiLiveEdge.Desktop.LiveOutput;

public interface ILiveOutputWindowManager : IDisposable
{
    LiveOutputStatus CurrentStatus { get; }

    void SetWebViewEnvironment(CoreWebView2Environment environment);

    Task<LiveOutputStatus> OpenAsync(CancellationToken cancellationToken = default);

    Task<LiveOutputStatus> CloseAsync(CancellationToken cancellationToken = default);

    Task<LiveOutputStatus> UpdateSettingsAsync(
        LiveOutputSettings settings,
        CancellationToken cancellationToken = default);

    Task ShutdownAsync(CancellationToken cancellationToken = default);
}
