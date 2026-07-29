using System.Reflection;

namespace AiLiveEdge.Desktop.Services.Versioning;

public sealed class AppVersionService : IAppVersionService
{
    private readonly Assembly _assembly = typeof(AppVersionService).Assembly;

    public string ProductName =>
        _assembly.GetCustomAttribute<AssemblyProductAttribute>()?.Product
        ?? _assembly.GetName().Name
        ?? "AI Live Edge";

    public string Version =>
        _assembly.GetCustomAttribute<AssemblyInformationalVersionAttribute>()?.InformationalVersion
        ?? _assembly.GetName().Version?.ToString()
        ?? "0.0.0";
}
