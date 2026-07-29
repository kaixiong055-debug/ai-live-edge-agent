namespace AiLiveEdge.Desktop.Services.Deployment;

public interface IDeploymentConfigurationService
{
    DeploymentConfiguration Current { get; }
    Task<DeploymentConfiguration> LoadAsync(CancellationToken cancellationToken = default);
    Uri GetRequiredApiBaseUri();
    string NormalizeAndValidateApiBaseUrl(string baseUrl);
}
