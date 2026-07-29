using System.Text;

namespace AiLiveEdge.Desktop;

public static class DesktopLogger
{
    private const long MaxBytes = 5L * 1024L * 1024L;
    private const int RetainedFiles = 3;
    private static readonly object Sync = new();
    private static string? _logFile;

    public static void Initialize(string logFile)
    {
        lock (Sync)
        {
            Directory.CreateDirectory(Path.GetDirectoryName(logFile)!);
            _logFile = logFile;
            RotateIfNeeded();
        }
    }

    public static void Info(string message) => Write("INFO", message, null);

    public static void Error(string message, Exception? exception = null) => Write("ERROR", message, exception);

    private static void Write(string level, string message, Exception? exception)
    {
        lock (Sync)
        {
            if (string.IsNullOrWhiteSpace(_logFile))
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
                File.AppendAllText(_logFile, line + Environment.NewLine, new UTF8Encoding(false));
            }
            catch
            {
                // Logging must never crash the Desktop shell.
            }
        }
    }

    private static void RotateIfNeeded()
    {
        if (string.IsNullOrWhiteSpace(_logFile) || !File.Exists(_logFile))
        {
            return;
        }

        if (new FileInfo(_logFile).Length < MaxBytes)
        {
            return;
        }

        for (var index = RetainedFiles; index >= 1; index--)
        {
            var source = index == 1 ? _logFile : $"{_logFile}.{index - 1}";
            var destination = $"{_logFile}.{index}";
            if (!File.Exists(source))
            {
                continue;
            }
            File.Move(source, destination, overwrite: true);
        }
    }
}
