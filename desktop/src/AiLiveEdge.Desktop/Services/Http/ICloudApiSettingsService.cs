namespace AiLiveEdge.Desktop.Services.Http;

public interface ICloudApiSettingsService
{
    CloudApiSettings Current { get; }
    Task<CloudApiSettings> LoadAsync(CancellationToken cancellationToken = default);
    Task<CloudApiSettings> SaveAsync(string baseUrl, CancellationToken cancellationToken = default);
    Uri GetRequiredBaseUri();
}
