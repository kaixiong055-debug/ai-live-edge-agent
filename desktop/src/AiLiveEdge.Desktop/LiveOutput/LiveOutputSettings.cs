namespace AiLiveEdge.Desktop.LiveOutput;

public enum LiveOutputCanvasMode
{
    Portrait,
    Landscape,
    Custom
}

public sealed record LiveOutputSettings(
    LiveOutputCanvasMode CanvasMode,
    int CanvasWidth,
    int CanvasHeight,
    double PreviewWindowWidth,
    double PreviewWindowHeight,
    string ChromaKeyColor,
    bool AutoOpenLiveOutput,
    double? LastWindowLeft,
    double? LastWindowTop)
{
    public static LiveOutputSettings Default { get; } = new(
        LiveOutputCanvasMode.Portrait,
        1080,
        1920,
        405,
        720,
        "#00FF00",
        false,
        null,
        null);

    public LiveOutputSettings Normalize()
    {
        var width = Math.Clamp(CanvasWidth, 320, 7680);
        var height = Math.Clamp(CanvasHeight, 320, 7680);
        var previewWidth = Math.Clamp(PreviewWindowWidth, 240, 1920);
        var previewHeight = Math.Clamp(PreviewWindowHeight, 240, 1080);
        var color = IsHexColor(ChromaKeyColor) ? ChromaKeyColor.ToUpperInvariant() : "#00FF00";
        return this with
        {
            CanvasWidth = width,
            CanvasHeight = height,
            PreviewWindowWidth = previewWidth,
            PreviewWindowHeight = previewHeight,
            ChromaKeyColor = color
        };
    }

    private static bool IsHexColor(string? value) =>
        !string.IsNullOrWhiteSpace(value)
        && System.Text.RegularExpressions.Regex.IsMatch(value, "^#[0-9A-Fa-f]{6}$");
}
