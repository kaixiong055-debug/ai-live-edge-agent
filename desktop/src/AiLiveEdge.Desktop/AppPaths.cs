namespace AiLiveEdge.Desktop;

public static class AppPaths
{
    public static string InstallDirectory { get; } = Path.GetFullPath(AppContext.BaseDirectory);
    public static string DataDirectory { get; } = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "AI Live Edge");

    public static string ConfigDirectory => Path.Combine(DataDirectory, "config");
    public static string AssetsDirectory => Path.Combine(DataDirectory, "assets");
    public static string LogsDirectory => Path.Combine(DataDirectory, "logs");
    public static string TokensDirectory => Path.Combine(DataDirectory, "tokens");
    public static string CacheDirectory => Path.Combine(DataDirectory, "cache");
    public static string RuntimeDirectory => Path.Combine(DataDirectory, "runtime");
    public static string DesktopDataDirectory => Path.Combine(DataDirectory, "desktop");
    public static string WebView2DataDirectory => Path.Combine(DesktopDataDirectory, "webview2");

    public static string DesktopLogFile => Path.Combine(LogsDirectory, "desktop.log");
    public static string AgentLogFile => Path.Combine(LogsDirectory, "agent.log");
    public static string AgentPidFile => Path.Combine(RuntimeDirectory, "agent-process.json");
    public static string AgentConnectionFile => Path.Combine(DesktopDataDirectory, "connection.json");
    public static string AgentRuntimeSettingsFile => Path.Combine(DesktopDataDirectory, "agent-runtime.json");
    public static string LiveOutputSettingsFile => Path.Combine(DesktopDataDirectory, "live-output.json");
    public static string CommandConfigFile => Path.Combine(ConfigDirectory, "commands.json");
    public static string VTubeStudioTokenFile => Path.Combine(TokensDirectory, "vtube-studio.token");

    public static string AgentDirectory => Path.Combine(InstallDirectory, "agent");
    public static string AgentJarFile => Path.Combine(AgentDirectory, "ai-live-edge-agent.jar");
    public static string JavaRuntimeDirectory => Path.Combine(AgentDirectory, "runtime");
    public static string JavawExecutable => Path.Combine(JavaRuntimeDirectory, "bin", "javaw.exe");
    public static string JavaExecutable => Path.Combine(JavaRuntimeDirectory, "bin", "java.exe");

    public static string ResourcesDirectory => Path.Combine(InstallDirectory, "resources");
    public static string DesktopAppDirectory => Path.Combine(InstallDirectory, "app");
    public static string DefaultCommandConfigFile => Path.Combine(ResourcesDirectory, "defaults", "commands.json");
    public static string DefaultAssetsDirectory => Path.Combine(ResourcesDirectory, "default-assets");
    public static string VersionFile => Path.Combine(InstallDirectory, "version.json");

    public static string SherpaNativeDirectory => Path.Combine(
        AgentDirectory, "resources", "runtime", "native", "windows-x86_64");
    public static string SherpaApiJar => Path.Combine(SherpaNativeDirectory, "sherpa-onnx-v1.12.10.jar");
    public static string SherpaModelDirectory => Path.Combine(
        AgentDirectory, "resources", "models", "sherpa-onnx", "streaming-paraformer-zh-en");

    public static void InitializeUserDirectories()
    {
        foreach (var directory in new[]
                 {
                     DataDirectory, ConfigDirectory, AssetsDirectory, LogsDirectory, TokensDirectory,
                     CacheDirectory, RuntimeDirectory, DesktopDataDirectory, WebView2DataDirectory
                 })
        {
            Directory.CreateDirectory(directory);
        }

        CopyDefaultCommandConfig();
        CopyDefaultAssets();
    }

    private static void CopyDefaultCommandConfig()
    {
        if (!File.Exists(CommandConfigFile) && File.Exists(DefaultCommandConfigFile))
        {
            File.Copy(DefaultCommandConfigFile, CommandConfigFile, overwrite: false);
        }
    }

    private static void CopyDefaultAssets()
    {
        if (!Directory.Exists(DefaultAssetsDirectory))
        {
            return;
        }

        foreach (var source in Directory.EnumerateFiles(DefaultAssetsDirectory, "*", SearchOption.TopDirectoryOnly))
        {
            var fileName = Path.GetFileName(source);
            if (string.IsNullOrWhiteSpace(fileName))
            {
                continue;
            }

            var destination = Path.GetFullPath(Path.Combine(AssetsDirectory, fileName));
            if (!destination.StartsWith(Path.GetFullPath(AssetsDirectory) + Path.DirectorySeparatorChar,
                    StringComparison.OrdinalIgnoreCase))
            {
                continue;
            }

            if (!File.Exists(destination))
            {
                File.Copy(source, destination, overwrite: false);
            }
        }
    }
}
