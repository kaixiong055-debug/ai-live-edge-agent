package cn.ai.live.edgeagent.integrations.vtubestudio;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class JdkVTubeStudioWebSocketTransport implements VTubeStudioTransport, WebSocket.Listener {
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ScheduledExecutorService timeoutScheduler =
            Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "vts-request-timeout"));
    private final Map<String, CompletableFuture<String>> pending = new ConcurrentHashMap<>();
    private final AtomicBoolean connecting = new AtomicBoolean(false);
    private volatile WebSocket webSocket;

    @Override
    public CompletionStage<Void> connect(URI endpoint) {
        WebSocket current = webSocket;
        if (current != null && !current.isInputClosed() && !current.isOutputClosed()) {
            return CompletableFuture.completedFuture(null);
        }
        if (!connecting.compareAndSet(false, true)) {
            return CompletableFuture.completedFuture(null);
        }
        return httpClient.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .buildAsync(endpoint, this)
                .thenAccept(socket -> this.webSocket = socket)
                .whenComplete((ignored, ex) -> connecting.set(false));
    }

    @Override
    public CompletionStage<String> send(String requestId, String payload, long timeoutMs) {
        WebSocket socket = webSocket;
        if (socket == null || socket.isInputClosed() || socket.isOutputClosed()) {
            return CompletableFuture.failedFuture(new VTubeStudioApiException(
                    VTubeStudioErrorCodes.VTS_CONNECTION_CLOSED, "VTube Studio WebSocket 未连接"));
        }
        CompletableFuture<String> future = new CompletableFuture<>();
        pending.put(requestId, future);
        timeoutScheduler.schedule(() -> {
            CompletableFuture<String> removed = pending.remove(requestId);
            if (removed != null) {
                removed.completeExceptionally(new VTubeStudioApiException(
                        VTubeStudioErrorCodes.VTS_REQUEST_TIMEOUT, "VTube Studio 请求超时"));
            }
        }, timeoutMs, TimeUnit.MILLISECONDS);
        socket.sendText(payload, true).exceptionally(ex -> {
            CompletableFuture<String> removed = pending.remove(requestId);
            if (removed != null) {
                removed.completeExceptionally(ex);
            }
            return null;
        });
        return future;
    }

    @Override
    public boolean connected() {
        WebSocket socket = webSocket;
        return socket != null && !socket.isInputClosed() && !socket.isOutputClosed();
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        webSocket.request(1);
        String text = data.toString();
        String requestId = VTubeStudioJsonSupport.requestIdFrom(text);
        if (requestId == null || requestId.isBlank()) {
            log.debug("收到未关联 VTS 响应，已忽略");
            return null;
        }
        CompletableFuture<String> future = pending.remove(requestId);
        if (future != null) {
            future.complete(text);
        }
        return null;
    }

    @Override
    public void onOpen(WebSocket webSocket) {
        WebSocket.Listener.super.onOpen(webSocket);
        webSocket.request(1);
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
        failPending(VTubeStudioErrorCodes.VTS_CONNECTION_CLOSED, "VTube Studio 连接已关闭");
        return WebSocket.Listener.super.onClose(webSocket, statusCode, reason);
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        failPending(VTubeStudioErrorCodes.VTS_CONNECTION_CLOSED, error.getMessage());
    }

    @Override
    public void close() {
        WebSocket socket = webSocket;
        webSocket = null;
        failPending(VTubeStudioErrorCodes.VTS_CONNECTION_CLOSED, "VTube Studio Transport 已关闭");
        timeoutScheduler.shutdownNow();
        if (socket != null) {
            socket.abort();
        }
    }

    int pendingCount() {
        return pending.size();
    }

    private void failPending(String errorCode, String message) {
        pending.forEach((id, future) -> future.completeExceptionally(new VTubeStudioApiException(errorCode, message)));
        pending.clear();
    }
}
