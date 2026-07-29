using System.Threading;

namespace AiLiveEdge.Desktop;

public sealed class SingleInstanceGuard : IDisposable
{
    private const string GlobalMutexName = @"Global\AI-Live-Edge-Desktop";
    private const string GlobalActivationEventName = @"Global\AI-Live-Edge-Desktop-Activate";
    private const string LocalMutexName = @"Local\AI-Live-Edge-Desktop";
    private const string LocalActivationEventName = @"Local\AI-Live-Edge-Desktop-Activate";

    private readonly Mutex _mutex;
    private readonly EventWaitHandle _activationEvent;
    private readonly CancellationTokenSource _listenerCancellation = new();
    private Task? _listenerTask;

    private SingleInstanceGuard(Mutex mutex, EventWaitHandle activationEvent, bool isPrimaryInstance)
    {
        _mutex = mutex;
        _activationEvent = activationEvent;
        IsPrimaryInstance = isPrimaryInstance;
    }

    public bool IsPrimaryInstance { get; }

    public static SingleInstanceGuard Acquire()
    {
        try
        {
            return Create(GlobalMutexName, GlobalActivationEventName);
        }
        catch (UnauthorizedAccessException)
        {
            DesktopLogger.Info("Global single-instance object unavailable; using current-session fallback.");
            return Create(LocalMutexName, LocalActivationEventName);
        }
    }

    private static SingleInstanceGuard Create(string mutexName, string eventName)
    {
        var mutex = new Mutex(initiallyOwned: true, mutexName, out var createdNew);
        var activationEvent = new EventWaitHandle(false, EventResetMode.AutoReset, eventName);
        return new SingleInstanceGuard(mutex, activationEvent, createdNew);
    }

    public void SignalPrimaryInstance()
    {
        try
        {
            _activationEvent.Set();
        }
        catch (Exception ex)
        {
            DesktopLogger.Error("Failed to signal the existing Desktop instance.", ex);
        }
    }

    public void StartActivationListener(Action activationAction)
    {
        if (!IsPrimaryInstance || _listenerTask is not null)
        {
            return;
        }

        _listenerTask = Task.Run(() =>
        {
            var handles = new WaitHandle[] { _activationEvent, _listenerCancellation.Token.WaitHandle };
            while (!_listenerCancellation.IsCancellationRequested)
            {
                var signaled = WaitHandle.WaitAny(handles);
                if (signaled == 0)
                {
                    activationAction();
                }
                else
                {
                    break;
                }
            }
        });
    }

    public void Dispose()
    {
        _listenerCancellation.Cancel();
        _activationEvent.Dispose();
        if (IsPrimaryInstance)
        {
            try
            {
                _mutex.ReleaseMutex();
            }
            catch (ApplicationException)
            {
                // The mutex may already be released during process shutdown.
            }
        }
        _mutex.Dispose();
        _listenerCancellation.Dispose();
    }
}
