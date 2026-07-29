namespace AiLiveEdge.Desktop.Services.Settings;

public sealed class AppSettingsService : IAppSettingsService
{
    private static readonly JsonSerializerOptions JsonOptions = new(JsonSerializerDefaults.Web)
    {
        WriteIndented = true,
        PropertyNameCaseInsensitive = true
    };

    static AppSettingsService()
    {
        JsonOptions.Converters.Add(new AppCloseBehaviorJsonConverter());
    }

    private readonly SemaphoreSlim _lock = new(1, 1);

    public AppSettings Current { get; private set; } = AppSettings.Default;

    public async Task<AppSettings> LoadAsync(CancellationToken cancellationToken = default)
    {
        await _lock.WaitAsync(cancellationToken);
        try
        {
            Current = await LoadCoreAsync(cancellationToken);
            return Current;
        }
        finally
        {
            _lock.Release();
        }
    }

    public async Task<AppSettings> SaveAsync(AppSettings settings, CancellationToken cancellationToken = default)
    {
        await _lock.WaitAsync(cancellationToken);
        try
        {
            Current = Normalize(settings);
            await WriteAtomicAsync(Current, cancellationToken);
            return Current;
        }
        finally
        {
            _lock.Release();
        }
    }

    public async Task<AppSettings> UpdateAsync(
        Func<AppSettings, AppSettings> update,
        CancellationToken cancellationToken = default)
    {
        await _lock.WaitAsync(cancellationToken);
        try
        {
            Current = Normalize(update(Current));
            await WriteAtomicAsync(Current, cancellationToken);
            return Current;
        }
        finally
        {
            _lock.Release();
        }
    }

    private static async Task<AppSettings> LoadCoreAsync(CancellationToken cancellationToken)
    {
        try
        {
            if (File.Exists(AppPaths.AppSettingsFile))
            {
                var settings = JsonSerializer.Deserialize<AppSettings>(
                    await File.ReadAllTextAsync(AppPaths.AppSettingsFile, cancellationToken),
                    JsonOptions);
                if (settings is not null)
                {
                    return Normalize(settings);
                }
            }
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException or JsonException)
        {
            DesktopLogger.Error("Failed to load app settings; using defaults.", ex);
        }

        return Normalize(AppSettings.Default with { CloudApiBaseUrl = await ReadLegacyCloudBaseUrl(cancellationToken) });
    }

    private static async Task<string> ReadLegacyCloudBaseUrl(CancellationToken cancellationToken)
    {
        try
        {
            if (!File.Exists(AppPaths.CloudApiSettingsFile))
            {
                return string.Empty;
            }

            using var document = JsonDocument.Parse(await File.ReadAllTextAsync(
                AppPaths.CloudApiSettingsFile,
                cancellationToken));
            return document.RootElement.TryGetProperty("baseUrl", out var camel)
                ? camel.GetString() ?? string.Empty
                : document.RootElement.TryGetProperty("BaseUrl", out var pascal)
                    ? pascal.GetString() ?? string.Empty
                    : string.Empty;
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException or JsonException)
        {
            DesktopLogger.Error("Failed to migrate legacy cloud API settings.", ex);
            return string.Empty;
        }
    }

    private static async Task WriteAtomicAsync(AppSettings settings, CancellationToken cancellationToken)
    {
        Directory.CreateDirectory(Path.GetDirectoryName(AppPaths.AppSettingsFile)!);
        var tempFile = AppPaths.AppSettingsFile + ".tmp";
        await File.WriteAllTextAsync(
            tempFile,
            JsonSerializer.Serialize(settings, JsonOptions),
            Encoding.UTF8,
            cancellationToken);
        File.Move(tempFile, AppPaths.AppSettingsFile, overwrite: true);
    }

    private static AppSettings Normalize(AppSettings settings) => settings with
    {
        SettingsVersion = AppSettings.CurrentSettingsVersion,
        CloudApiBaseUrl = NormalizeCloudApiBaseUrl(settings.CloudApiBaseUrl, allowEmpty: true),
        HeartbeatIntervalSeconds = Math.Clamp(settings.HeartbeatIntervalSeconds, 10, 300),
        LogRetentionDays = Math.Clamp(settings.LogRetentionDays, 1, 365),
        LastSelectedPage = string.IsNullOrWhiteSpace(settings.LastSelectedPage)
            ? AppSettings.Default.LastSelectedPage
            : settings.LastSelectedPage.Trim()
    };

    public static string NormalizeCloudApiBaseUrl(string? value, bool allowEmpty = false)
    {
        if (string.IsNullOrWhiteSpace(value))
        {
            if (allowEmpty)
            {
                return string.Empty;
            }
            throw new InvalidOperationException("请填写完整的 App API 地址，例如：https://example.com/app-api/。");
        }

        var trimmed = value.Trim().Replace('\\', '/');
        if (!Uri.TryCreate(trimmed, UriKind.Absolute, out var uri))
        {
            throw new InvalidOperationException("App API 地址格式不正确。");
        }

        var isHttpAllowed = uri.Scheme == Uri.UriSchemeHttps
                            || (uri.Scheme == Uri.UriSchemeHttp && IsLoopbackHost(uri));
        if (!isHttpAllowed)
        {
            throw new InvalidOperationException("远程 App API 地址必须使用 HTTPS；仅 localhost、127.0.0.1、::1 允许 HTTP。");
        }

        var path = NormalizePath(uri.AbsolutePath);
        var builder = new UriBuilder(uri)
        {
            Path = path,
            Query = string.Empty,
            Fragment = string.Empty
        };
        return builder.Uri.AbsoluteUri.TrimEnd('/') + "/";
    }

    private static string NormalizePath(string path)
    {
        var segments = path.Split('/', StringSplitOptions.RemoveEmptyEntries);
        var result = new List<string>();
        foreach (var segment in segments)
        {
            if (string.Equals(segment, "app-api", StringComparison.OrdinalIgnoreCase)
                && result.LastOrDefault()?.Equals("app-api", StringComparison.OrdinalIgnoreCase) == true)
            {
                continue;
            }
            result.Add(segment);
            if (string.Equals(segment, "app-api", StringComparison.OrdinalIgnoreCase))
            {
                break;
            }
        }
        if (!result.Any(segment => string.Equals(segment, "app-api", StringComparison.OrdinalIgnoreCase)))
        {
            result.Add("app-api");
        }
        return "/" + string.Join("/", result) + "/";
    }

    private static bool IsLoopbackHost(Uri uri) =>
        uri.IsLoopback
        || string.Equals(uri.Host, "localhost", StringComparison.OrdinalIgnoreCase)
        || string.Equals(uri.Host, "127.0.0.1", StringComparison.OrdinalIgnoreCase)
        || string.Equals(uri.Host, "::1", StringComparison.OrdinalIgnoreCase)
        || string.Equals(uri.Host, "[::1]", StringComparison.OrdinalIgnoreCase);
}
