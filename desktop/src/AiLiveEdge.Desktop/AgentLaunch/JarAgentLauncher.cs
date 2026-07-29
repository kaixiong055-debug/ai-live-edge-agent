using System.Diagnostics;
using System.Text.Json;
using AiLiveEdge.Desktop.AgentLaunch;
using AiLiveEdge.Desktop.Models;

namespace AiLiveEdge.Desktop.AgentLaunch;

public sealed class JarAgentLauncher : IAgentLauncher
{
    private const int StartupTimeoutSeconds = 30;
    private static readonly TimeSpan PollInterval = TimeSpan.FromMilliseconds(400);
    private readonly AgentHealthChecker _healthChecker;
    private readonly SemaphoreSlim _operationLock = new(1, 1);
    private Process? _managedProcess;

    public JarAgentLauncher(AgentHealthChecker healthChecker)
    {
        _healthChecker = healthChecker;
    }

    public AgentRuntimeMode Mode => AgentRuntimeMode.Production;

    public async Task<AgentStartResult> EnsureAgentRunningAsync(
        IProgress<string>? progress = null, CancellationToken cancellationToken = default)
    {
        await _operationLock.WaitAsync(cancellationToken);
        try
        {
            progress?.Report("正在检查本地服务……");
            var health = await _healthChecker.CheckAsync(cancellationToken);
            if (health.State == AgentHealthState.Healthy)
            {
                return new AgentStartResult(AgentStartStatus.AlreadyRunning, "Agent 已经在运行。",
                    AgentVersion: health.AgentVersion);
            }
            if (health.State == AgentHealthState.PortOccupiedByOtherService)
            {
                return new AgentStartResult(AgentStartStatus.PortConflict, "端口 18081 已被其他程序占用。");
            }

            if (!File.Exists(AppPaths.AgentJarFile))
            {
                return new AgentStartResult(AgentStartStatus.MissingAgentJar,
                    $"未找到 Agent JAR：{AppPaths.AgentJarFile}");
            }

            var javaExecutable = ResolveJavaExecutable();
            if (javaExecutable is null)
            {
                return new AgentStartResult(AgentStartStatus.MissingJavaRuntime,
                    "未找到 Java 17 或更高版本。请安装 Java 17，或设置 AI_LIVE_JAVA_PATH、AI_LIVE_JAVA_HOME、JAVA_HOME。");
            }

            progress?.Report("正在启动本地服务……");
            Process? process = null;
            try
            {
                process = Process.Start(CreateStartInfo(javaExecutable))
                          ?? throw new InvalidOperationException("Process.Start 未返回 Agent 进程。");
                _managedProcess = process;
                SaveManagedProcess(process, javaExecutable);
                DesktopLogger.Info($"Agent process started. pid={process.Id}");
            }
            catch (Exception ex)
            {
                if (process is not null)
                {
                    await StopSpecificProcessAsync(process, cancellationToken);
                }
                DeletePidFile();
                DesktopLogger.Error("Failed to start Agent process.", ex);
                return new AgentStartResult(AgentStartStatus.StartFailed, $"Agent 启动失败：{ex.Message}");
            }

            progress?.Report("正在等待 Agent……");
            var deadline = DateTimeOffset.UtcNow.AddSeconds(StartupTimeoutSeconds);
            while (DateTimeOffset.UtcNow < deadline)
            {
                cancellationToken.ThrowIfCancellationRequested();
                if (process.HasExited)
                {
                    DeletePidFile();
                    return new AgentStartResult(AgentStartStatus.ProcessExited,
                        $"Agent 进程已提前退出，退出代码：{process.ExitCode}", process.Id);
                }

                health = await _healthChecker.CheckAsync(cancellationToken);
                if (health.State == AgentHealthState.Healthy)
                {
                    return new AgentStartResult(AgentStartStatus.Started, "Agent 启动成功。",
                        process.Id, health.AgentVersion);
                }
                if (health.State == AgentHealthState.PortOccupiedByOtherService)
                {
                    await StopSpecificProcessAsync(process, cancellationToken);
                    DeletePidFile();
                    return new AgentStartResult(AgentStartStatus.PortConflict,
                        "端口 18081 已被其他程序占用，已停止本次启动的 Agent。", process.Id);
                }

                await Task.Delay(PollInterval, cancellationToken);
            }

            await StopSpecificProcessAsync(process, cancellationToken);
            DeletePidFile();
            return new AgentStartResult(AgentStartStatus.TimedOut,
                $"等待 Agent 启动超过 {StartupTimeoutSeconds} 秒，已停止本次启动的进程。", process.Id);
        }
        finally
        {
            _operationLock.Release();
        }
    }

    public async Task<AgentStartResult> RestartManagedAgentAsync(
        IProgress<string>? progress = null, CancellationToken cancellationToken = default)
    {
        var stopResult = await StopManagedAgentAsync(cancellationToken);
        if (stopResult.Status == AgentStartStatus.NotManaged)
        {
            return stopResult;
        }
        return await EnsureAgentRunningAsync(progress, cancellationToken);
    }

    public async Task<AgentStartResult> StopManagedAgentAsync(CancellationToken cancellationToken = default)
    {
        await _operationLock.WaitAsync(cancellationToken);
        try
        {
            var managed = LoadManagedProcess();
            if (managed is null)
            {
                return new AgentStartResult(AgentStartStatus.NotManaged,
                    "当前 Agent 不是由 AI Live Edge Desktop 管理，未执行停止操作。");
            }

            Process process;
            try
            {
                process = Process.GetProcessById(managed.ProcessId);
            }
            catch (ArgumentException)
            {
                DeletePidFile();
                return new AgentStartResult(AgentStartStatus.Started, "Agent 已经停止。");
            }

            if (!ValidateManagedProcess(process, managed))
            {
                return new AgentStartResult(AgentStartStatus.NotManaged,
                    "Agent PID 校验失败，为避免误杀进程，未执行停止操作。", managed.ProcessId);
            }

            var health = await _healthChecker.CheckAsync(cancellationToken);
            if (health.State != AgentHealthState.Healthy)
            {
                return new AgentStartResult(AgentStartStatus.NotManaged,
                    "Runtime API 校验失败，为避免误杀进程，未执行停止操作。", managed.ProcessId);
            }

            await StopSpecificProcessAsync(process, cancellationToken);
            DeletePidFile();
            _managedProcess = null;
            return new AgentStartResult(AgentStartStatus.Started, "Agent 已停止。", managed.ProcessId);
        }
        finally
        {
            _operationLock.Release();
        }
    }

    private static string? ResolveJavaExecutable()
    {
        var candidates = new List<string> { AppPaths.JavaExecutable };
        AddConfiguredJavaCandidate(candidates, Environment.GetEnvironmentVariable("AI_LIVE_JAVA_PATH"));
        AddJavaHomeCandidate(candidates, Environment.GetEnvironmentVariable("AI_LIVE_JAVA_HOME"));
        AddJavaHomeCandidate(candidates, Environment.GetEnvironmentVariable("JAVA_HOME"));

        var path = Environment.GetEnvironmentVariable("PATH");
        if (!string.IsNullOrWhiteSpace(path))
        {
            foreach (var directory in path.Split(Path.PathSeparator,
                         StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries))
            {
                candidates.Add(Path.Combine(directory.Trim('"'), "java.exe"));
            }
        }

        foreach (var candidate in candidates.Distinct(StringComparer.OrdinalIgnoreCase))
        {
            if (IsSupportedJava(candidate))
            {
                DesktopLogger.Info($"Using Java runtime: {Path.GetFullPath(candidate)}");
                return Path.GetFullPath(candidate);
            }
        }

        return null;
    }

    private static void AddConfiguredJavaCandidate(ICollection<string> candidates, string? value)
    {
        if (!string.IsNullOrWhiteSpace(value))
        {
            candidates.Add(Directory.Exists(value) ? Path.Combine(value, "bin", "java.exe") : value);
        }
    }

    private static void AddJavaHomeCandidate(ICollection<string> candidates, string? javaHome)
    {
        if (!string.IsNullOrWhiteSpace(javaHome))
        {
            candidates.Add(Path.Combine(javaHome, "bin", "java.exe"));
        }
    }

    private static bool IsSupportedJava(string executable)
    {
        if (!File.Exists(executable))
        {
            return false;
        }

        try
        {
            using var process = Process.Start(new ProcessStartInfo
            {
                FileName = executable,
                Arguments = "-version",
                UseShellExecute = false,
                CreateNoWindow = true,
                RedirectStandardError = true,
                RedirectStandardOutput = true
            });
            if (process is null || !process.WaitForExit(5000))
            {
                process?.Kill(entireProcessTree: true);
                return false;
            }

            var versionText = process.StandardError.ReadToEnd() + process.StandardOutput.ReadToEnd();
            var match = System.Text.RegularExpressions.Regex.Match(
                versionText, "version\\s+\"(?:1\\.)?(?<major>\\d+)");
            return process.ExitCode == 0
                   && match.Success
                   && int.TryParse(match.Groups["major"].Value, out var major)
                   && major >= 17;
        }
        catch (Exception ex)
        {
            DesktopLogger.Error($"Failed to validate Java runtime: {executable}", ex);
            return false;
        }
    }

    private static ProcessStartInfo CreateStartInfo(string javaExecutable)
    {
        var startInfo = new ProcessStartInfo
        {
            FileName = javaExecutable,
            WorkingDirectory = AppPaths.AgentDirectory,
            UseShellExecute = false,
            CreateNoWindow = true,
            WindowStyle = ProcessWindowStyle.Hidden
        };

        startInfo.ArgumentList.Add("-Dfile.encoding=UTF-8");
        startInfo.ArgumentList.Add("-jar");
        startInfo.ArgumentList.Add(AppPaths.AgentJarFile);
        startInfo.ArgumentList.Add("--server.address=127.0.0.1");
        startInfo.ArgumentList.Add("--server.port=18081");
        startInfo.ArgumentList.Add($"--ai-live.data-dir={AppPaths.DataDirectory}");
        startInfo.ArgumentList.Add($"--ai-live.command.config-path={AppPaths.CommandConfigFile}");
        startInfo.ArgumentList.Add($"--ai-live.assets.root-path={AppPaths.AssetsDirectory}");
        startInfo.ArgumentList.Add($"--ai-live.integrations.vtube-studio.token-path={AppPaths.VTubeStudioTokenFile}");
        startInfo.ArgumentList.Add("--ai-live.asr.auto-connect=false");
        startInfo.ArgumentList.Add($"--logging.file.name={AppPaths.AgentLogFile}");

        if (Directory.Exists(AppPaths.SherpaNativeDirectory)
            && File.Exists(AppPaths.SherpaApiJar)
            && Directory.Exists(AppPaths.SherpaModelDirectory))
        {
            startInfo.ArgumentList.Add($"--ai-live.asr.sherpa.native-root={AppPaths.SherpaNativeDirectory}");
            startInfo.ArgumentList.Add($"--ai-live.asr.sherpa.jar-path={AppPaths.SherpaApiJar}");
            startInfo.ArgumentList.Add($"--ai-live.asr.sherpa.model-root={AppPaths.SherpaModelDirectory}");
        }

        // ProcessStartInfo inherits the current user environment by default, including Tencent ASR variables.
        startInfo.Environment["AI_LIVE_DATA_DIR"] = AppPaths.DataDirectory;
        return startInfo;
    }

    private static async Task StopSpecificProcessAsync(Process process, CancellationToken cancellationToken)
    {
        try
        {
            if (process.HasExited)
            {
                return;
            }

            process.CloseMainWindow();
            try
            {
                await process.WaitForExitAsync(cancellationToken).WaitAsync(TimeSpan.FromSeconds(2), cancellationToken);
            }
            catch (TimeoutException)
            {
                process.Kill(entireProcessTree: true);
                await process.WaitForExitAsync(cancellationToken).WaitAsync(TimeSpan.FromSeconds(5), cancellationToken);
            }
        }
        catch (InvalidOperationException)
        {
            // Process exited between checks.
        }
    }

    private static void SaveManagedProcess(Process process, string executablePath)
    {
        var record = new ManagedAgentProcess(
            process.Id,
            process.StartTime.ToUniversalTime(),
            Path.GetFullPath(executablePath),
            Path.GetFullPath(AppPaths.AgentJarFile));
        var json = JsonSerializer.Serialize(record, new JsonSerializerOptions { WriteIndented = true });
        File.WriteAllText(AppPaths.AgentPidFile, json);
    }

    private static ManagedAgentProcess? LoadManagedProcess()
    {
        try
        {
            if (!File.Exists(AppPaths.AgentPidFile))
            {
                return null;
            }
            return JsonSerializer.Deserialize<ManagedAgentProcess>(File.ReadAllText(AppPaths.AgentPidFile));
        }
        catch (Exception ex)
        {
            DesktopLogger.Error("Failed to read Agent PID metadata.", ex);
            return null;
        }
    }

    private static bool ValidateManagedProcess(Process process, ManagedAgentProcess managed)
    {
        try
        {
            var recordedExecutable = Path.GetFullPath(managed.ExecutablePath);
            var actualExecutable = process.MainModule?.FileName;
            var actualExecutableMatches = !string.IsNullOrWhiteSpace(actualExecutable)
                                          && string.Equals(Path.GetFullPath(actualExecutable), recordedExecutable,
                                              StringComparison.OrdinalIgnoreCase);
            var jarMatches = string.Equals(managed.AgentJarPath, Path.GetFullPath(AppPaths.AgentJarFile),
                StringComparison.OrdinalIgnoreCase);
            var startTimeMatches = Math.Abs((process.StartTime.ToUniversalTime() - managed.StartedAtUtc).TotalSeconds) < 5;
            return actualExecutableMatches && jarMatches && startTimeMatches;
        }
        catch
        {
            return false;
        }
    }

    private static void DeletePidFile()
    {
        try
        {
            File.Delete(AppPaths.AgentPidFile);
        }
        catch (Exception ex)
        {
            DesktopLogger.Error("Failed to delete Agent PID metadata.", ex);
        }
    }

    private sealed record ManagedAgentProcess(
        int ProcessId,
        DateTime StartedAtUtc,
        string ExecutablePath,
        string AgentJarPath);
}
