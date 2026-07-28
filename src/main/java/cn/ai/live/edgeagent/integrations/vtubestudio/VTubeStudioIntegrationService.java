package cn.ai.live.edgeagent.integrations.vtubestudio;

import cn.ai.live.edgeagent.config.AiLiveProperties;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class VTubeStudioIntegrationService {
    private final AiLiveProperties properties;
    private final VTubeStudioApiClient client;
    private final ScheduledExecutorService reconnectScheduler =
            Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "vts-reconnect"));
    private final AtomicBoolean stopped = new AtomicBoolean(false);
    private final AtomicBoolean reconnectScheduled = new AtomicBoolean(false);
    private volatile long nextDelayMs;
    private volatile ScheduledFuture<?> reconnectFuture;

    public VTubeStudioIntegrationService(AiLiveProperties properties, VTubeStudioApiClient client) {
        this.properties = properties;
        this.client = client;
        this.nextDelayMs = properties.getIntegrations().getVtubeStudio().getReconnect().getInitialDelayMs();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (properties.getIntegrations().getVtubeStudio().isEnabled()
                && properties.getIntegrations().getVtubeStudio().isConnectOnStartup()) {
            connect();
        }
    }

    public CompletionStage<VTubeStudioStatusSnapshot> connect() {
        stopped.set(false);
        return client.connect().thenApply(snapshot -> {
            if (snapshot.connectionStatus() == VTubeStudioConnectionStatus.READY
                    || snapshot.connectionStatus() == VTubeStudioConnectionStatus.NO_MODEL
                    || snapshot.connectionStatus() == VTubeStudioConnectionStatus.AUTHORIZATION_REQUIRED) {
                resetBackoff();
            } else {
                scheduleReconnect();
            }
            return snapshot;
        });
    }

    public CompletionStage<VTubeStudioStatusSnapshot> authorize() {
        return client.authorize();
    }

    public CompletionStage<VTubeStudioStatusSnapshot> refresh() {
        return client.refreshModelAndHotkeys();
    }

    public void disconnect() {
        stopped.set(true);
        ScheduledFuture<?> future = reconnectFuture;
        if (future != null) {
            future.cancel(false);
        }
        reconnectScheduled.set(false);
        client.disconnect();
    }

    public VTubeStudioStatusSnapshot status() {
        return client.status();
    }

    public List<VTubeStudioHotkey> hotkeys() {
        return client.hotkeys();
    }

    public CompletionStage<Void> triggerHotkey(String hotkeyId) {
        return client.triggerHotkey(hotkeyId);
    }

    public VTubeStudioHotkey findHotkey(String hotkeyId, String hotkeyName) {
        return client.findHotkey(hotkeyId, hotkeyName);
    }

    private void scheduleReconnect() {
        AiLiveProperties.VTubeStudioReconnect reconnect = properties.getIntegrations().getVtubeStudio().getReconnect();
        if (!reconnect.isEnabled() || stopped.get() || !properties.getIntegrations().getVtubeStudio().isEnabled()) {
            return;
        }
        if (!reconnectScheduled.compareAndSet(false, true)) {
            return;
        }
        long delay = nextDelayMs;
        reconnectFuture = reconnectScheduler.schedule(() -> {
            reconnectScheduled.set(false);
            if (!stopped.get()) {
                client.connect().whenComplete((snapshot, ex) -> {
                    if (ex != null || snapshot.connectionStatus() == VTubeStudioConnectionStatus.DISCONNECTED
                            || snapshot.connectionStatus() == VTubeStudioConnectionStatus.ERROR) {
                        growBackoff();
                        scheduleReconnect();
                    } else {
                        resetBackoff();
                    }
                });
            }
        }, delay, TimeUnit.MILLISECONDS);
        growBackoff();
    }

    private void growBackoff() {
        AiLiveProperties.VTubeStudioReconnect reconnect = properties.getIntegrations().getVtubeStudio().getReconnect();
        nextDelayMs = Math.min(reconnect.getMaxDelayMs(), Math.round(nextDelayMs * reconnect.getMultiplier()));
    }

    private void resetBackoff() {
        nextDelayMs = properties.getIntegrations().getVtubeStudio().getReconnect().getInitialDelayMs();
    }

    @PreDestroy
    public void stop() {
        stopped.set(true);
        disconnect();
        reconnectScheduler.shutdownNow();
    }
}
