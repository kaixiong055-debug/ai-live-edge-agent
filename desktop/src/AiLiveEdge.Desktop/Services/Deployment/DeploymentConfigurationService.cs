using AiLiveEdge.Desktop.Services.Settings;

namespace AiLiveEdge.Desktop.Services.Deployment;

public sealed class DeploymentConfigurationService : IDeploymentConfigurationService
{
    private static readonly JsonSerializerOptions JsonOptions = new(JsonSerializerDefaults.Web)
    {
        PropertyNameCaseInsensitive = true,
        Converters = { new JsonStringEnumConverter() }
    };

    public DeploymentConfiguration Current { get; private set; } = DeploymentConfiguration.Default;

    public async Task<DeploymentConfiguration> LoadAsync(CancellationToken cancellationToken = default)
    {
        Current = await LoadCoreAsync(cancellationToken);
        return Current;
    }

    public Uri GetRequiredApiBaseUri()
    {
        if (string.IsNullOrWhiteSpace(Current.ApiBaseUrl))
        {
            throw new InvalidOperationException("云服务未配置，请联系 AI Live Edge 管理员。");
        }
        return new Uri(NormalizeAndValidateApiBaseUrl(Current.ApiBaseUrl));
    }

    public string NormalizeAndValidateApiBaseUrl(string baseUrl)
    {
        var normalized = AppSettingsService.NormalizeCloudApiBaseUrl(baseUrl);
        var uri = new Uri(normalized);
        var isDevelopmentLoopback = Current.DeploymentMode == DeploymentMode.DEVELOPMENT
                                    && uri.Scheme == Uri.UriSchemeHttp
                                    && uri.IsLoopback;
        if (Current.DeploymentMode != DeploymentMode.DEVELOPMENT
            && uri.Scheme != Uri.UriSchemeHttps)
        {
            throw new InvalidOperationException("正式云服务地址必须使用 HTTPS。");
        }
        if (Current.DeploymentMode == DeploymentMode.SAAS
            && Current.AllowedHosts.Count == 0)
        {
            throw new InvalidOperationException("正式 SaaS 官方域名未配置，请联系 AI Live Edge 管理员。");
        }
        if (uri.Scheme != Uri.UriSchemeHttps && !isDevelopmentLoopback)
        {
            throw new InvalidOperationException("仅开发模式允许本地 HTTP 云服务地址。");
        }
        if (Current.AllowedHosts.Count > 0
            && !Current.AllowedHosts.Any(host => string.Equals(host, uri.Host, StringComparison.OrdinalIgnoreCase)))
        {
            throw new InvalidOperationException("云服务地址不在允许的官方域名列表中。");
        }
        return normalized;
    }

    private static async Task<DeploymentConfiguration> LoadCoreAsync(CancellationToken cancellationToken)
    {
        var config = DeploymentConfiguration.Default;
        try
        {
            if (File.Exists(AppPaths.DeploymentConfigFile))
            {
                config = JsonSerializer.Deserialize<DeploymentConfiguration>(
                             await File.ReadAllTextAsync(AppPaths.DeploymentConfigFile, cancellationToken),
                             JsonOptions)
                         ?? DeploymentConfiguration.Default;
            }
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException or JsonException)
        {
            DesktopLogger.Error("Failed to load deployment configuration; using default SaaS configuration.", ex);
        }

        if (config.DeploymentMode == DeploymentMode.DEVELOPMENT)
        {
            var envBaseUrl = Environment.GetEnvironmentVariable("AI_LIVE_API_BASE_URL");
            if (!string.IsNullOrWhiteSpace(envBaseUrl))
            {
                config = config with { ApiBaseUrl = envBaseUrl, AllowServerEditing = true };
            }
        }

        return Normalize(config);
    }

    private static DeploymentConfiguration Normalize(DeploymentConfiguration config)
    {
        var allowEditing = config.DeploymentMode switch
        {
            DeploymentMode.SAAS => false,
            DeploymentMode.DEVELOPMENT => true,
            _ => config.AllowServerEditing
        };
        var hosts = config.AllowedHosts
            .Where(host => !string.IsNullOrWhiteSpace(host))
            .Select(host => host.Trim())
            .Distinct(StringComparer.OrdinalIgnoreCase)
            .ToArray();
        return config with
        {
            AllowServerEditing = allowEditing,
            ApiBaseUrl = string.IsNullOrWhiteSpace(config.ApiBaseUrl)
                ? string.Empty
                : AppSettingsService.NormalizeCloudApiBaseUrl(config.ApiBaseUrl),
            AllowedHosts = hosts
        };
    }
}
