using System.Text;

namespace AiLiveEdge.Desktop;

public static class DesktopLogger
{
    private const long MaxBytes = 5L * 1024L * 1024L;
    private const int RetainedFiles = 3;
    private static readonly object Sync = new();
    private static string? _logDirectory;
    private static string _logPrefix = "desktop";

    public static void Initialize(string logFile)
    {
        lock (Sync)
        {
            Directory.CreateDirectory(Path.GetDirectoryName(logFile)!);
            _logDirectory = Path.GetDirectoryName(logFile)!;
            _logPrefix = Path.GetFileNameWithoutExtension(logFile);
            RotateIfNeeded();
        }
    }

    public static void Debug(string message) => Write("DEBUG", message, null);

    public static void Info(string message) => Write("INFO", message, null);

    public static void Warn(string message) => Write("WARN", message, null);

    public static void Error(string message, Exception? exception = null) => Write("ERROR", message, exception);

    public static void CleanupExpiredLogs(int retentionDays)
    {
        try
        {
            if (string.IsNullOrWhiteSpace(_logDirectory) || !Directory.Exists(_logDirectory))
            {
                return;
            }

            var cutoff = DateTimeOffset.Now.AddDays(-Math.Max(1, retentionDays));
            foreach (var file in Directory.EnumerateFiles(_logDirectory, $"{_logPrefix}-*.log*"))
            {
                if (File.GetLastWriteTime(file) < cutoff)
                {
                    File.Delete(file);
                }
            }
        }
        catch (Exception ex)
        {
            Write("WARN", "Failed to cleanup expired logs.", ex);
        }
    }

    public static IReadOnlyList<string> ReadRecentErrors(int maxCount)
    {
        lock (Sync)
        {
            try
            {
                if (string.IsNullOrWhiteSpace(CurrentLogFile) || !File.Exists(CurrentLogFile))
                {
                    return [];
                }

                return File.ReadLines(CurrentLogFile)
                    .Where(line => line.Contains("[ERROR]", StringComparison.OrdinalIgnoreCase))
                    .TakeLast(Math.Clamp(maxCount, 1, 50))
                    .Select(line => line.Length > 500 ? line[..500] : line)
                    .ToArray();
            }
            catch
            {
                return [];
            }
        }
    }

    private static void Write(string level, string message, Exception? exception)
    {
        lock (Sync)
        {
            if (string.IsNullOrWhiteSpace(CurrentLogFile))
            {
                return;
            }

            try
            {
                RotateIfNeeded();
                var line = $"{DateTimeOffset.Now:O} [{level}] {message}";
                if (exception is not null)
                {
                    line += $" | {exception.GetType().Name}: {exception.Message}";
                }
                File.AppendAllText(CurrentLogFile, line + Environment.NewLine, new UTF8Encoding(false));
            }
            catch
            {
                // Logging must never crash the Desktop shell.
            }
        }
    }

    private static string? CurrentLogFile => string.IsNullOrWhiteSpace(_logDirectory)
        ? null
        : Path.Combine(_logDirectory, $"{_logPrefix}-{DateTimeOffset.Now:yyyyMMdd}.log");

    private static void RotateIfNeeded()
    {
        var logFile = CurrentLogFile;
        if (string.IsNullOrWhiteSpace(logFile) || !File.Exists(logFile))
        {
            return;
        }

        if (new FileInfo(logFile).Length < MaxBytes)
        {
            return;
        }

        for (var index = RetainedFiles; index >= 1; index--)
        {
            var source = index == 1 ? logFile : $"{logFile}.{index - 1}";
            var destination = $"{logFile}.{index}";
            if (!File.Exists(source))
            {
                continue;
            }
            File.Move(source, destination, overwrite: true);
        }
    }
}
