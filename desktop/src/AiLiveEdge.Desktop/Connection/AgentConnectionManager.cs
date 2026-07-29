namespace AiLiveEdge.Desktop.Connection;

public sealed class AgentConnectionManager : IDisposable
{
    private readonly SemaphoreSlim _configurationLock = new(1, 1);
    private AgentConnectionSettings _settings;
    private IAgentClient _client;

    public AgentConnectionManager()
    {
        _settings = LoadSettings();
        _client = CreateClient(_settings);
    }

    public AgentConnectionMode CurrentMode => _client.Mode;

    public bool HasSelectedMode => _settings.HasSelectedMode;

    public async Task<JsonElement> Execute(
        string operation,
        JsonElement payload,
        CancellationToken cancellationToken = default)
    {
        return operation switch
        {
            "getConnectionSettings" => GetPublicSettings(),
            "configureConnection" => await Configure(payload, cancellationToken),
            "getRuntimeStatus" => await _client.GetRuntimeStatus(cancellationToken),
            "getAsrStatus" => await _client.GetAsrStatus(cancellationToken),
            "startAsr" => await _client.StartAsr(cancellationToken),
            "stopAsr" => await _client.StopAsr(cancellationToken),
            "sendCommand" => await _client.SendCommand(
                ReadRequiredString(payload, "actionCode"), cancellationToken),
            "getActionStatus" => await _client.GetActionStatus(cancellationToken),
            "getMediaStatus" => await _client.GetMediaStatus(cancellationToken),
            "getCommands" => await _client.GetCommands(cancellationToken),
            "getAssets" => await _client.GetAssets(cancellationToken),
            "testAudio" => await _client.StartAudioTest(cancellationToken),
            "uploadAsset" => await UploadAsset(payload, cancellationToken),
            _ => throw new InvalidOperationException($"Unknown Agent operation: {operation}")
        };
    }

    private async Task<JsonElement> Configure(
        JsonElement payload,
        CancellationToken cancellationToken)
    {
        await _configurationLock.WaitAsync(cancellationToken);
        try
        {
            var modeText = ReadOptionalString(payload, "mode") ?? "LOCAL";
            if (!Enum.TryParse<AgentConnectionMode>(modeText, true, out var mode))
            {
                throw new ArgumentException("Connection mode must be LOCAL or CLOUD.");
            }

            var remember = ReadOptionalBoolean(payload, "remember") ?? true;
            var next = _settings with
            {
                Mode = mode,
                HasSelectedMode = remember || _settings.HasSelectedMode,
                LocalAddress = ReadOptionalString(payload, "localAddress") ?? _settings.LocalAddress,
                CloudAddress = ReadOptionalString(payload, "cloudAddress") ?? _settings.CloudAddress,
                DeviceId = string.Empty,
                TenantId = string.Empty,
                Token = string.Empty
            };

            var nextClient = CreateClient(next);
            var previousClient = _client;
            _settings = next;
            _client = nextClient;
            previousClient.Dispose();

            if (remember)
            {
                SaveSettings(next);
            }

            return GetPublicSettings();
        }
        finally
        {
            _configurationLock.Release();
        }
    }

    private async Task<JsonElement> UploadAsset(
        JsonElement payload,
        CancellationToken cancellationToken)
    {
        var fileName = Path.GetFileName(ReadRequiredString(payload, "fileName"));
        var contentType = ReadOptionalString(payload, "contentType") ?? "application/octet-stream";
        var base64 = ReadRequiredString(payload, "base64");
        var content = Convert.FromBase64String(base64);
        return await _client.UploadAsset(fileName, contentType, content, cancellationToken);
    }

    private JsonElement GetPublicSettings() => JsonSerializer.SerializeToElement(new
    {
        mode = _settings.Mode.ToString().ToUpperInvariant(),
        hasSelectedMode = _settings.HasSelectedMode,
        localAddress = _settings.LocalAddress,
        cloudAddress = _settings.CloudAddress
    });

    private static IAgentClient CreateClient(AgentConnectionSettings settings)
    {
        return settings.Mode switch
        {
            AgentConnectionMode.Cloud => new CloudAgentClient(
                new Uri(settings.CloudAddress),
                settings.DeviceId,
                settings.Token,
                settings.TenantId),
            _ => new LocalAgentClient(new Uri(settings.LocalAddress))
        };
    }

    private static AgentConnectionSettings LoadSettings()
    {
        try
        {
            if (File.Exists(AppPaths.AgentConnectionFile))
            {
                var settings = JsonSerializer.Deserialize<AgentConnectionSettings>(
                    File.ReadAllText(AppPaths.AgentConnectionFile));
                if (settings is not null)
                {
                    var cloudAddress = settings.CloudAddress;
                    var mode = settings.Mode == AgentConnectionMode.Cloud && string.IsNullOrWhiteSpace(cloudAddress)
                        ? AgentConnectionMode.Local
                        : settings.Mode;
                    return settings with
                    {
                        Mode = mode,
                        HasSelectedMode = mode == settings.Mode && settings.HasSelectedMode,
                        CloudAddress = cloudAddress,
                        DeviceId = string.Empty,
                        TenantId = string.Empty,
                        Token = string.Empty
                    };
                }
            }
        }
        catch (Exception ex)
        {
            DesktopLogger.Error("Failed to load Agent connection settings.", ex);
        }

        return AgentConnectionSettings.Default;
    }

    private static void SaveSettings(AgentConnectionSettings settings)
    {
        Directory.CreateDirectory(Path.GetDirectoryName(AppPaths.AgentConnectionFile)!);
        File.WriteAllText(
            AppPaths.AgentConnectionFile,
            JsonSerializer.Serialize(settings, new JsonSerializerOptions { WriteIndented = true }));
    }

    private static string ReadRequiredString(JsonElement payload, string propertyName)
    {
        return ReadOptionalString(payload, propertyName)
               ?? throw new ArgumentException($"Missing required property: {propertyName}");
    }

    private static string? ReadOptionalString(JsonElement payload, string propertyName)
    {
        if (payload.ValueKind != JsonValueKind.Object
            || !payload.TryGetProperty(propertyName, out var value)
            || value.ValueKind is JsonValueKind.Null or JsonValueKind.Undefined)
        {
            return null;
        }
        return value.GetString();
    }

    private static bool? ReadOptionalBoolean(JsonElement payload, string propertyName)
    {
        if (payload.ValueKind != JsonValueKind.Object
            || !payload.TryGetProperty(propertyName, out var value)
            || value.ValueKind is JsonValueKind.Null or JsonValueKind.Undefined)
        {
            return null;
        }
        return value.GetBoolean();
    }

    public void Dispose()
    {
        _client.Dispose();
        _configurationLock.Dispose();
    }

    private sealed record AgentConnectionSettings(
        AgentConnectionMode Mode,
        bool HasSelectedMode,
        string LocalAddress,
        string CloudAddress,
        string DeviceId,
        string TenantId,
        string Token)
    {
        public static AgentConnectionSettings Default { get; } = new(
            AgentConnectionMode.Local,
            false,
            LocalAgentClient.DefaultBaseAddress.AbsoluteUri,
            string.Empty,
            string.Empty,
            string.Empty,
            string.Empty);
    }
}
