namespace AiLiveEdge.Desktop.Services.Settings;

public interface IAppSettingsService
{
    AppSettings Current { get; }
    Task<AppSettings> LoadAsync(CancellationToken cancellationToken = default);
    Task<AppSettings> SaveAsync(AppSettings settings, CancellationToken cancellationToken = default);
    Task<AppSettings> UpdateAsync(Func<AppSettings, AppSettings> update, CancellationToken cancellationToken = default);
}
