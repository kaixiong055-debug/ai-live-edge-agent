namespace AiLiveEdge.Desktop.Services.Startup;

public interface IWindowsStartupService
{
    bool IsEnabled();
    void SetEnabled(bool enabled);
}
