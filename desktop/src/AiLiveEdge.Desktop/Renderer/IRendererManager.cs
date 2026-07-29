using Microsoft.Web.WebView2.Core;

namespace AiLiveEdge.Desktop.Renderer;

public interface IRendererManager : IDisposable
{
    RendererStatus CurrentStatus { get; }

    Task<RendererStatus> EnsureStartedAsync(
        CoreWebView2Environment environment,
        CancellationToken cancellationToken = default);

    Task<RendererStatus> GetStatusAsync(CancellationToken cancellationToken = default);
}
