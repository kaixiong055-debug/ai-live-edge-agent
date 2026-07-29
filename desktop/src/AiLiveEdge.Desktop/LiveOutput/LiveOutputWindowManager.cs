using System.Windows;
using Microsoft.Web.WebView2.Core;

namespace AiLiveEdge.Desktop.LiveOutput;

public sealed class LiveOutputWindowManager : ILiveOutputWindowManager
{
    private readonly Window _owner;
    private readonly SemaphoreSlim _operationLock = new(1, 1);
    private CoreWebView2Environment? _environment;
    private LiveOutputWindow? _window;
    private LiveOutputSettings _settings;
    private bool _disposed;
    private bool _shuttingDown;

    public LiveOutputWindowManager(Window owner)
    {
        _owner = owner;
        _settings = LoadSettings();
        CurrentStatus = new LiveOutputStatus(
            false,
            LiveOutputConnectionState.Closed,
            _settings);
    }

    public LiveOutputStatus CurrentStatus { get; private set; }

    public void SetWebViewEnvironment(CoreWebView2Environment environment) => _environment = environment;

    public async Task<LiveOutputStatus> OpenAsync(CancellationToken cancellationToken = default)
    {
        ObjectDisposedException.ThrowIf(_disposed, this);
        await _operationLock.WaitAsync(cancellationToken);
        try
        {
            if (_window is { IsLoaded: true })
            {
                _window.ActivateOutput();
                return CurrentStatus;
            }
            if (_environment is null)
            {
                throw new InvalidOperationException("WebView2 environment is not ready.");
            }

            await _owner.Dispatcher.InvokeAsync(() =>
            {
                cancellationToken.ThrowIfCancellationRequested();
                var window = new LiveOutputWindow(_environment, _settings);
                window.ConnectionStateChanged += Window_ConnectionStateChanged;
                window.PlacementChanged += Window_PlacementChanged;
                window.Closed += Window_Closed;
                _window = window;
                CurrentStatus = new LiveOutputStatus(
                    true,
                    LiveOutputConnectionState.Connecting,
                    _settings);
                window.Show();
                window.ActivateOutput();
            });
            return CurrentStatus;
        }
        finally
        {
            _operationLock.Release();
        }
    }

    public async Task<LiveOutputStatus> CloseAsync(CancellationToken cancellationToken = default)
    {
        ObjectDisposedException.ThrowIf(_disposed, this);
        await _operationLock.WaitAsync(cancellationToken);
        try
        {
            if (_window is not null)
            {
                await _owner.Dispatcher.InvokeAsync(() => _window?.Close());
            }
            return CurrentStatus;
        }
        finally
        {
            _operationLock.Release();
        }
    }

    public async Task<LiveOutputStatus> UpdateSettingsAsync(
        LiveOutputSettings settings,
        CancellationToken cancellationToken = default)
    {
        ObjectDisposedException.ThrowIf(_disposed, this);
        await _operationLock.WaitAsync(cancellationToken);
        try
        {
            _settings = settings.Normalize();
            SaveSettings(_settings);
            if (_window is not null)
            {
                await _owner.Dispatcher.InvokeAsync(() => _window?.ApplySettings(_settings));
            }
            CurrentStatus = CurrentStatus with { Settings = _settings };
            return CurrentStatus;
        }
        finally
        {
            _operationLock.Release();
        }
    }

    public async Task ShutdownAsync(CancellationToken cancellationToken = default)
    {
        _shuttingDown = true;
        if (_window is not null)
        {
            await _owner.Dispatcher.InvokeAsync(() => _window?.Close());
        }
    }

    private void Window_ConnectionStateChanged(object? sender, LiveOutputConnectionState state)
    {
        CurrentStatus = new LiveOutputStatus(true, state, _settings);
    }

    private void Window_PlacementChanged(object? sender, LiveOutputSettings settings)
    {
        _settings = settings.Normalize();
        CurrentStatus = CurrentStatus with { Settings = _settings };
        SaveSettings(_settings);
    }

    private void Window_Closed(object? sender, EventArgs e)
    {
        if (sender is LiveOutputWindow closedWindow)
        {
            _settings = closedWindow.CaptureSettings().Normalize();
            SaveSettings(_settings);
        }
        if (_window is not null)
        {
            _window.ConnectionStateChanged -= Window_ConnectionStateChanged;
            _window.PlacementChanged -= Window_PlacementChanged;
            _window.Closed -= Window_Closed;
        }
        _window = null;
        CurrentStatus = new LiveOutputStatus(
            false,
            LiveOutputConnectionState.Closed,
            _settings);
        if (!_shuttingDown)
        {
            DesktopLogger.Info("Live output window closed.");
        }
    }

    private static LiveOutputSettings LoadSettings()
    {
        try
        {
            if (File.Exists(AppPaths.LiveOutputSettingsFile))
            {
                return (JsonSerializer.Deserialize<LiveOutputSettings>(
                            File.ReadAllText(AppPaths.LiveOutputSettingsFile))
                        ?? LiveOutputSettings.Default).Normalize();
            }
        }
        catch (Exception ex)
        {
            DesktopLogger.Error("Failed to load live output settings.", ex);
        }
        return LiveOutputSettings.Default;
    }

    private static void SaveSettings(LiveOutputSettings settings)
    {
        try
        {
            Directory.CreateDirectory(Path.GetDirectoryName(AppPaths.LiveOutputSettingsFile)!);
            File.WriteAllText(
                AppPaths.LiveOutputSettingsFile,
                JsonSerializer.Serialize(settings, new JsonSerializerOptions { WriteIndented = true }));
        }
        catch (Exception ex)
        {
            DesktopLogger.Error("Failed to save live output settings.", ex);
        }
    }

    public void Dispose()
    {
        if (_disposed)
        {
            return;
        }
        _disposed = true;
        _operationLock.Dispose();
    }
}
