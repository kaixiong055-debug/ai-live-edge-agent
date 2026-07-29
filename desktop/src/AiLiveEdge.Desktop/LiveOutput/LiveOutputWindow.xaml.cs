using System.Drawing;
using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Interop;
using Microsoft.Web.WebView2.Core;

namespace AiLiveEdge.Desktop.LiveOutput;

public partial class LiveOutputWindow : Window
{
    private const int DwmwaNcRenderingPolicy = 2;
    private const int DwmncrpDisabled = 1;
    private const int DwmwaWindowCornerPreference = 33;
    private const int DwmwcpDoNotRound = 1;
    private readonly CoreWebView2Environment _environment;
    private LiveOutputSettings _settings;
    private bool _initialized;
    private bool _applyingSize;

    public LiveOutputWindow(CoreWebView2Environment environment, LiveOutputSettings settings)
    {
        _environment = environment;
        _settings = settings.Normalize();
        InitializeComponent();
        ApplySettings(_settings, navigate: false);
    }

    public event EventHandler<LiveOutputConnectionState>? ConnectionStateChanged;

    public event EventHandler<LiveOutputSettings>? PlacementChanged;

    public void ApplySettings(LiveOutputSettings settings, bool navigate = true)
    {
        _settings = settings.Normalize();
        _applyingSize = true;
        try
        {
            Width = _settings.PreviewWindowWidth;
            Height = _settings.PreviewWindowHeight;
            if (_settings.LastWindowLeft is { } left)
            {
                Left = left;
            }
            if (_settings.LastWindowTop is { } top)
            {
                Top = top;
            }

            var color = (System.Windows.Media.Color)System.Windows.Media.ColorConverter.ConvertFromString(
                _settings.ChromaKeyColor);
            OutputRoot.Background = new System.Windows.Media.SolidColorBrush(color);
            Background = OutputRoot.Background;
            OutputWebView.DefaultBackgroundColor = Color.FromArgb(color.A, color.R, color.G, color.B);
        }
        finally
        {
            _applyingSize = false;
        }

        if (navigate && _initialized)
        {
            NavigateRenderer();
        }
    }

    public void ActivateOutput()
    {
        if (WindowState == WindowState.Minimized)
        {
            WindowState = WindowState.Normal;
        }
        Activate();
        Topmost = true;
        Topmost = false;
        Focus();
    }

    public LiveOutputSettings CaptureSettings() => _settings with
    {
        PreviewWindowWidth = ActualWidth,
        PreviewWindowHeight = ActualHeight,
        LastWindowLeft = Left,
        LastWindowTop = Top
    };

    private void Window_SourceInitialized(object? sender, EventArgs e)
    {
        var handle = new WindowInteropHelper(this).Handle;
        var nonClientRendering = DwmncrpDisabled;
        DwmSetWindowAttribute(
            handle,
            DwmwaNcRenderingPolicy,
            ref nonClientRendering,
            Marshal.SizeOf<int>());
        var cornerPreference = DwmwcpDoNotRound;
        DwmSetWindowAttribute(
            handle,
            DwmwaWindowCornerPreference,
            ref cornerPreference,
            Marshal.SizeOf<int>());
    }

    private async void Window_Loaded(object sender, RoutedEventArgs e)
    {
        try
        {
            ConnectionStateChanged?.Invoke(this, LiveOutputConnectionState.Connecting);
            await OutputWebView.EnsureCoreWebView2Async(_environment);
            var webSettings = OutputWebView.CoreWebView2.Settings;
            webSettings.AreDevToolsEnabled = false;
            webSettings.AreDefaultContextMenusEnabled = false;
            webSettings.AreBrowserAcceleratorKeysEnabled = false;
            webSettings.IsStatusBarEnabled = false;
            OutputWebView.AllowExternalDrop = false;
            OutputWebView.NavigationCompleted += OutputWebView_NavigationCompleted;
            OutputWebView.CoreWebView2.NewWindowRequested += CoreWebView2_NewWindowRequested;
            OutputWebView.CoreWebView2.ProcessFailed += CoreWebView2_ProcessFailed;
            _initialized = true;
            NavigateRenderer();
        }
        catch (Exception ex)
        {
            DesktopLogger.Error("Live output WebView2 initialization failed.", ex);
            ConnectionStateChanged?.Invoke(this, LiveOutputConnectionState.Failed);
        }
    }

    private void NavigateRenderer()
    {
        var query = string.Join("&", new[]
        {
            "client=live-output",
            "clientType=LIVE_OUTPUT_WINDOW",
            $"width={_settings.CanvasWidth}",
            $"height={_settings.CanvasHeight}",
            $"background={Uri.EscapeDataString(_settings.ChromaKeyColor)}"
        });
        OutputWebView.Source = new Uri($"http://127.0.0.1:18081/renderer/index.html?{query}");
    }

    private void OutputWebView_NavigationCompleted(
        object? sender,
        CoreWebView2NavigationCompletedEventArgs e) =>
        ConnectionStateChanged?.Invoke(
            this,
            e.IsSuccess ? LiveOutputConnectionState.Connected : LiveOutputConnectionState.Failed);

    private static void CoreWebView2_NewWindowRequested(
        object? sender,
        CoreWebView2NewWindowRequestedEventArgs e) => e.Handled = true;

    private void CoreWebView2_ProcessFailed(object? sender, CoreWebView2ProcessFailedEventArgs e)
    {
        DesktopLogger.Error($"Live output WebView2 process failed: {e.ProcessFailedKind}");
        ConnectionStateChanged?.Invoke(this, LiveOutputConnectionState.Failed);
    }

    private void Window_SizeChanged(object sender, SizeChangedEventArgs e)
    {
        if (_applyingSize || WindowState != WindowState.Normal)
        {
            return;
        }

        var ratio = (double)_settings.CanvasWidth / _settings.CanvasHeight;
        if (ratio <= 0)
        {
            return;
        }

        _applyingSize = true;
        try
        {
            if (Math.Abs(e.NewSize.Width - e.PreviousSize.Width)
                >= Math.Abs(e.NewSize.Height - e.PreviousSize.Height))
            {
                Height = Width / ratio;
            }
            else
            {
                Width = Height * ratio;
            }
        }
        finally
        {
            _applyingSize = false;
        }
        NotifyPlacementChanged();
    }

    private void Window_LocationChanged(object? sender, EventArgs e) => NotifyPlacementChanged();

    private void NotifyPlacementChanged()
    {
        if (!IsLoaded || WindowState != WindowState.Normal)
        {
            return;
        }
        PlacementChanged?.Invoke(this, _settings with
        {
            PreviewWindowWidth = ActualWidth,
            PreviewWindowHeight = ActualHeight,
            LastWindowLeft = Left,
            LastWindowTop = Top
        });
    }

    private void Window_Closed(object? sender, EventArgs e)
    {
        if (_initialized)
        {
            OutputWebView.NavigationCompleted -= OutputWebView_NavigationCompleted;
            OutputWebView.CoreWebView2.NewWindowRequested -= CoreWebView2_NewWindowRequested;
            OutputWebView.CoreWebView2.ProcessFailed -= CoreWebView2_ProcessFailed;
            OutputWebView.CoreWebView2.Navigate("about:blank");
            OutputWebView.Dispose();
        }
    }

    [DllImport("dwmapi.dll")]
    private static extern int DwmSetWindowAttribute(
        IntPtr windowHandle,
        int attribute,
        ref int attributeValue,
        int attributeSize);
}
