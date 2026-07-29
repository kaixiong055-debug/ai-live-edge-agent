using Microsoft.Win32;

namespace AiLiveEdge.Desktop.Services.Device;

public sealed class DeviceIdentityService : IDeviceIdentityService
{
    public async Task<AgentDeviceIdentity> GetOrCreateAsync(CancellationToken cancellationToken = default)
    {
        var persisted = await LoadOrCreatePersistedIdentity(cancellationToken);
        var machineGuid = ReadMachineGuid();
        var deviceName = Environment.MachineName;
        var rawFingerprint = string.Join("|", new[]
        {
            "ai-live-edge",
            machineGuid,
            persisted.InstallationId,
            deviceName
        }.Where(value => !string.IsNullOrWhiteSpace(value)));

        return new AgentDeviceIdentity(
            persisted.DeviceCode,
            deviceName,
            rawFingerprint,
            "Windows",
            Environment.OSVersion.VersionString);
    }

    private static async Task<PersistedDeviceIdentity> LoadOrCreatePersistedIdentity(CancellationToken cancellationToken)
    {
        try
        {
            if (File.Exists(AppPaths.AgentDeviceIdentityFile))
            {
                var existing = JsonSerializer.Deserialize<PersistedDeviceIdentity>(
                    await File.ReadAllTextAsync(AppPaths.AgentDeviceIdentityFile, cancellationToken));
                if (!string.IsNullOrWhiteSpace(existing?.DeviceCode)
                    && !string.IsNullOrWhiteSpace(existing.InstallationId))
                {
                    return existing;
                }
            }
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException or JsonException)
        {
            DesktopLogger.Error("Failed to load device identity; recreating non-sensitive identity.", ex);
        }

        var identity = new PersistedDeviceIdentity(
            Guid.NewGuid().ToString("D"),
            Guid.NewGuid().ToString("D"));
        Directory.CreateDirectory(Path.GetDirectoryName(AppPaths.AgentDeviceIdentityFile)!);
        await File.WriteAllTextAsync(
            AppPaths.AgentDeviceIdentityFile,
            JsonSerializer.Serialize(identity, new JsonSerializerOptions { WriteIndented = true }),
            cancellationToken);
        return identity;
    }

    private static string ReadMachineGuid()
    {
        try
        {
            using var key = Registry.LocalMachine.OpenSubKey(@"SOFTWARE\Microsoft\Cryptography");
            return key?.GetValue("MachineGuid")?.ToString() ?? string.Empty;
        }
        catch (Exception ex) when (ex is UnauthorizedAccessException or IOException)
        {
            DesktopLogger.Error("Failed to read Windows MachineGuid; continuing with installation id.", ex);
            return string.Empty;
        }
    }

    private sealed record PersistedDeviceIdentity(string DeviceCode, string InstallationId);
}
