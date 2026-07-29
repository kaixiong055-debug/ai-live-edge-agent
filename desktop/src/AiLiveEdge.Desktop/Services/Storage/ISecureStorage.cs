namespace AiLiveEdge.Desktop.Services.Storage;

public interface ISecureStorage
{
    Task SaveAsync<T>(string path, T value, CancellationToken cancellationToken = default);
    Task<T?> LoadAsync<T>(string path, CancellationToken cancellationToken = default);
    Task DeleteAsync(string path, CancellationToken cancellationToken = default);
}
