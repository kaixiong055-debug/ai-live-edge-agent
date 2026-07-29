using AiLiveEdge.Desktop.Services.Deployment;
using AiLiveEdge.Desktop.Services.Settings;

namespace AiLiveEdge.Desktop.Services.Http;

public sealed class CloudApiSettingsService : ICloudApiSettingsService
{
    private const int DefaultTimeoutSeconds = 15;
    private readonly IAppSettingsService _appSettingsService;
    private readonly IDeploymentConfigurationService _deploymentConfigurationService;

    public CloudApiSettingsService(
        IAppSettingsService appSettingsService,
        IDeploymentConfigurationService deploymentConfigurationService)
    {
        _appSettingsService = appSettingsService;
        _deploymentConfigurationService = deploymentConfigurationService;
    }

    public CloudApiSettings Current { get; private set; } = CloudApiSettings.Empty;

    public async Task<CloudApiSettings> LoadAsync(CancellationToken cancellationToken = default)
    {
        var deployment = await _deploymentConfigurationService.LoadAsync(cancellationToken);
        var settings = await _appSettingsService.LoadAsync(cancellationToken);
        Current = FromConfiguration(deployment, settings);
        return Current;
    }

    public async Task<CloudApiSettings> SaveAsync(string baseUrl, CancellationToken cancellationToken = default)
    {
        var deployment = _deploymentConfigurationService.Current;
        if (!deployment.AllowServerEditing)
        {
            throw new InvalidOperationException("当前部署模式不允许修改云服务地址。");
        }

        var normalized = _deploymentConfigurationService.NormalizeAndValidateApiBaseUrl(baseUrl);
        var settings = await _appSettingsService.UpdateAsync(
            current => current with { CloudApiBaseUrl = normalized },
            cancellationToken);
        Current = FromConfiguration(deployment, settings);
        return Current;
    }

    public Uri GetRequiredBaseUri()
    {
        if (string.IsNullOrWhiteSpace(Current.BaseUrl))
        {
            throw new InvalidOperationException("云服务未配置，请联系 AI Live Edge 管理员。");
        }
        return new Uri(_deploymentConfigurationService.NormalizeAndValidateApiBaseUrl(Current.BaseUrl));
    }

    private CloudApiSettings FromConfiguration(DeploymentConfiguration deployment, AppSettings settings)
    {
        var baseUrl = deployment.AllowServerEditing && !string.IsNullOrWhiteSpace(settings.CloudApiBaseUrl)
            ? settings.CloudApiBaseUrl
            : deployment.ApiBaseUrl;
        if (!string.IsNullOrWhiteSpace(baseUrl))
        {
            baseUrl = _deploymentConfigurationService.NormalizeAndValidateApiBaseUrl(baseUrl);
        }
        return new CloudApiSettings(
            baseUrl,
            DefaultTimeoutSeconds,
            settings.HeartbeatIntervalSeconds);
    }
}
