package cn.ai.live.edgeagent.integrations.vtubestudio;

import cn.ai.live.edgeagent.config.AiLiveProperties;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class VTubeStudioApiClient {
    private static final String API_NAME = "VTubeStudioPublicAPI";

    private final AiLiveProperties properties;
    private final VTubeStudioTransport transport;
    private final VTubeStudioTokenStore tokenStore;
    private final ObjectMapper objectMapper;
    private final AtomicReference<VTubeStudioConnectionStatus> status =
            new AtomicReference<>(VTubeStudioConnectionStatus.DISCONNECTED);
    private volatile boolean apiActive;
    private volatile boolean authenticated;
    private volatile String version;
    private volatile boolean modelLoaded;
    private volatile String modelName;
    private volatile String modelId;
    private volatile List<VTubeStudioHotkey> hotkeys = List.of();
    private volatile List<String> duplicateHotkeyNames = List.of();
    private volatile Instant lastConnectedAt;
    private volatile Instant lastAuthenticatedAt;
    private volatile Instant lastHotkeyRefreshAt;
    private volatile Instant lastActionAt;
    private volatile String lastActionName;
    private volatile String lastErrorCode;
    private volatile String lastErrorMessage;

    public VTubeStudioApiClient(AiLiveProperties properties, VTubeStudioTransport transport,
                                VTubeStudioTokenStore tokenStore, ObjectMapper objectMapper) {
        this.properties = properties;
        this.transport = transport;
        this.tokenStore = tokenStore;
        this.objectMapper = objectMapper;
    }

    public CompletionStage<VTubeStudioStatusSnapshot> connect() {
        if (!enabled()) {
            setStatus(VTubeStudioConnectionStatus.DISABLED);
            return CompletableFuture.completedFuture(status());
        }
        try {
            validateLoopback();
        } catch (VTubeStudioApiException ex) {
            recordError(ex.errorCode(), ex.getMessage());
            setStatus(VTubeStudioConnectionStatus.DISCONNECTED);
            return CompletableFuture.completedFuture(status());
        }
        setStatus(VTubeStudioConnectionStatus.CONNECTING);
        URI endpoint = URI.create("ws://" + config().getHost() + ":" + config().getPort());
        return transport.connect(endpoint)
                .thenCompose(ignored -> apiState())
                .thenCompose(ignored -> authenticateWithStoredTokenIfPresent())
                .thenApply(ignored -> status())
                .exceptionally(ex -> {
                    recordError(VTubeStudioErrorCodes.VTS_CONNECTION_FAILED, safeMessage(ex));
                    setStatus(VTubeStudioConnectionStatus.DISCONNECTED);
                    return status();
                });
    }

    public CompletionStage<VTubeStudioStatusSnapshot> authorize() {
        if (!transport.connected()) {
            return connect().thenCompose(ignored -> authorize());
        }
        validatePluginIdentity();
        setStatus(VTubeStudioConnectionStatus.AUTHORIZING);
        return request("AuthenticationTokenRequest", Map.of(
                        "pluginName", config().getPluginName(),
                        "pluginDeveloper", config().getPluginDeveloper()))
                .thenCompose(root -> {
                    String token = root.path("data").path("authenticationToken").asText("");
                    if (token.isBlank()) {
                        throw new VTubeStudioApiException(VTubeStudioErrorCodes.VTS_AUTHORIZATION_DENIED,
                                "用户未完成 VTube Studio 授权");
                    }
                    tokenStore.save(token);
                    return authenticate(token);
                })
                .thenCompose(ignored -> refreshModelAndHotkeys())
                .thenApply(ignored -> status())
                .exceptionally(ex -> {
                    recordError(errorCode(ex), safeMessage(ex));
                    if (VTubeStudioErrorCodes.VTS_TOKEN_INVALID.equals(errorCode(ex))) {
                        tokenStore.delete();
                        setStatus(VTubeStudioConnectionStatus.AUTHORIZATION_REQUIRED);
                    }
                    return status();
                });
    }

    public CompletionStage<VTubeStudioStatusSnapshot> refreshModelAndHotkeys() {
        if (!authenticated) {
            setStatus(VTubeStudioConnectionStatus.AUTHORIZATION_REQUIRED);
            return CompletableFuture.completedFuture(status());
        }
        return request("CurrentModelRequest", Map.of())
                .thenCompose(root -> {
                    JsonNode data = root.path("data");
                    modelLoaded = data.path("modelLoaded").asBoolean(false);
                    modelName = data.path("modelName").asText(null);
                    modelId = data.path("modelID").asText(null);
                    if (!modelLoaded) {
                        hotkeys = List.of();
                        duplicateHotkeyNames = List.of();
                        setStatus(VTubeStudioConnectionStatus.NO_MODEL);
                        return CompletableFuture.completedFuture(root);
                    }
                    return request("HotkeysInCurrentModelRequest", Map.of())
                            .thenApply(this::parseHotkeys);
                })
                .thenApply(ignored -> {
                    if (modelLoaded) {
                        setStatus(VTubeStudioConnectionStatus.READY);
                    }
                    return status();
                });
    }

    public CompletionStage<Void> triggerHotkey(String hotkeyId) {
        if (status.get() != VTubeStudioConnectionStatus.READY) {
            return CompletableFuture.failedFuture(new VTubeStudioApiException(
                    authenticated ? VTubeStudioErrorCodes.VTS_NO_MODEL : VTubeStudioErrorCodes.VTS_NOT_AUTHENTICATED,
                    "VTube Studio 尚未就绪"));
        }
        return request("HotkeyTriggerRequest", Map.of("hotkeyID", hotkeyId))
                .thenAccept(root -> {
                    lastActionAt = Instant.now();
                    lastActionName = hotkeyId;
                });
    }

    public void disconnect() {
        transport.close();
        authenticated = false;
        setStatus(VTubeStudioConnectionStatus.STOPPED);
    }

    public VTubeStudioStatusSnapshot status() {
        return new VTubeStudioStatusSnapshot(enabled(), status.get(), config().getHost(), config().getPort(), apiActive,
                version, transport.connected(), tokenStore.tokenPresent(), authenticated, modelLoaded, modelName, modelId,
                hotkeys.size(), duplicateHotkeyNames, lastConnectedAt, lastAuthenticatedAt, lastHotkeyRefreshAt,
                lastActionAt, lastActionName, lastErrorCode, lastErrorMessage);
    }

    public List<VTubeStudioHotkey> hotkeys() {
        return hotkeys;
    }

    public VTubeStudioHotkey findHotkey(String hotkeyId, String hotkeyName) {
        if (hotkeyId != null && !hotkeyId.isBlank()) {
            return hotkeys.stream()
                    .filter(hotkey -> hotkeyId.equals(hotkey.hotkeyId()))
                    .findFirst()
                    .orElse(null);
        }
        if (hotkeyName == null || hotkeyName.isBlank()) {
            return null;
        }
        String expected = hotkeyName.trim().toLowerCase(Locale.ROOT);
        return hotkeys.stream()
                .filter(hotkey -> hotkey.name() != null && hotkey.name().trim().toLowerCase(Locale.ROOT).equals(expected))
                .findFirst()
                .orElse(null);
    }

    private CompletionStage<JsonNode> apiState() {
        return request("APIStateRequest", Map.of()).thenApply(root -> {
            JsonNode data = root.path("data");
            apiActive = data.path("active").asBoolean(false);
            authenticated = data.path("currentSessionAuthenticated").asBoolean(false);
            version = data.path("vTubeStudioVersion").asText(null);
            lastConnectedAt = Instant.now();
            setStatus(VTubeStudioConnectionStatus.CONNECTED);
            return root;
        });
    }

    private CompletionStage<Void> authenticateWithStoredTokenIfPresent() {
        return tokenStore.load()
                .map(this::authenticate)
                .orElseGet(() -> {
                    setStatus(VTubeStudioConnectionStatus.AUTHORIZATION_REQUIRED);
                    return CompletableFuture.completedFuture(null);
                });
    }

    private CompletionStage<Void> authenticate(String token) {
        validatePluginIdentity();
        setStatus(VTubeStudioConnectionStatus.AUTHENTICATING);
        return request("AuthenticationRequest", Map.of(
                        "pluginName", config().getPluginName(),
                        "pluginDeveloper", config().getPluginDeveloper(),
                        "authenticationToken", token))
                .thenAccept(root -> {
                    boolean ok = root.path("data").path("authenticated").asBoolean(false);
                    if (!ok) {
                        tokenStore.delete();
                        throw new VTubeStudioApiException(VTubeStudioErrorCodes.VTS_TOKEN_INVALID,
                                root.path("data").path("reason").asText("VTube Studio Token 无效"));
                    }
                    authenticated = true;
                    lastAuthenticatedAt = Instant.now();
                });
    }

    private CompletionStage<JsonNode> request(String messageType, Map<String, Object> data) {
        String requestId = newRequestId();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("apiName", API_NAME);
        body.put("apiVersion", config().getApiVersion());
        body.put("requestID", requestId);
        body.put("messageType", messageType);
        body.put("data", data);
        try {
            String payload = objectMapper.writeValueAsString(body);
            return transport.send(requestId, payload, config().getRequestTimeoutMs())
                    .thenApply(parseResponse(messageType));
        } catch (Exception ex) {
            return CompletableFuture.failedFuture(ex);
        }
    }

    private Function<String, JsonNode> parseResponse(String requestType) {
        return json -> {
            try {
                JsonNode root = objectMapper.readTree(json);
                if ("APIError".equals(root.path("messageType").asText())) {
                    JsonNode data = root.path("data");
                    String errorId = data.path("errorID").asText(VTubeStudioErrorCodes.VTS_API_ERROR);
                    String message = data.path("message").asText("VTube Studio API Error");
                    throw new VTubeStudioApiException(errorId, message);
                }
                return root;
            } catch (VTubeStudioApiException ex) {
                throw ex;
            } catch (Exception ex) {
                throw new VTubeStudioApiException(VTubeStudioErrorCodes.VTS_UNKNOWN_RESPONSE,
                        "无法解析 VTube Studio 响应: " + requestType);
            }
        };
    }

    private JsonNode parseHotkeys(JsonNode root) {
        List<VTubeStudioHotkey> parsed = new ArrayList<>();
        root.path("data").path("availableHotkeys").forEach(node -> parsed.add(new VTubeStudioHotkey(
                node.path("hotkeyID").asText(null),
                node.path("name").asText(null),
                node.path("type").asText(null),
                node.path("description").asText(null),
                node.path("file").asText(null),
                node.path("onScreenButtonID").asText(null),
                false)));
        Map<String, Integer> counts = new HashMap<>();
        for (VTubeStudioHotkey hotkey : parsed) {
            if (hotkey.name() != null) {
                counts.merge(hotkey.name().toLowerCase(Locale.ROOT), 1, Integer::sum);
            }
        }
        duplicateHotkeyNames = counts.entrySet().stream()
                .filter(entry -> entry.getValue() > 1)
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
        hotkeys = parsed.stream()
                .map(hotkey -> hotkey.name() != null
                        && counts.getOrDefault(hotkey.name().toLowerCase(Locale.ROOT), 0) > 1
                        ? hotkey.withDuplicateName(true) : hotkey)
                .toList();
        lastHotkeyRefreshAt = Instant.now();
        return root;
    }

    private void validateLoopback() {
        String host = config().getHost();
        if (!("127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host) || "::1".equals(host))) {
            throw new VTubeStudioApiException(VTubeStudioErrorCodes.VTS_CONNECTION_FAILED,
                    "VTube Studio V1 只允许连接本机 loopback 地址");
        }
    }

    private void validatePluginIdentity() {
        validateLength(config().getPluginName(), "pluginName");
        validateLength(config().getPluginDeveloper(), "pluginDeveloper");
    }

    private void validateLength(String value, String field) {
        int length = value == null ? 0 : value.trim().length();
        if (length < 3 || length > 32) {
            throw new VTubeStudioApiException(VTubeStudioErrorCodes.VTS_API_ERROR, field + " 长度必须为 3-32");
        }
    }

    private String newRequestId() {
        return "al-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    private void setStatus(VTubeStudioConnectionStatus next) {
        VTubeStudioConnectionStatus previous = status.getAndSet(next);
        if (previous != next) {
            log.info("VTube Studio 状态变化: {} -> {}", previous, next);
        }
    }

    private void recordError(String code, String message) {
        lastErrorCode = code;
        lastErrorMessage = message;
        log.warn("VTube Studio 错误: errorCode={}, message={}", code, message);
    }

    private String errorCode(Throwable ex) {
        Throwable root = unwrap(ex);
        return root instanceof VTubeStudioApiException api ? api.errorCode() : VTubeStudioErrorCodes.VTS_API_ERROR;
    }

    private String safeMessage(Throwable ex) {
        String message = unwrap(ex).getMessage();
        return message == null ? "" : message.replaceAll("(?i)authenticationToken|token", "[redacted]");
    }

    private Throwable unwrap(Throwable ex) {
        return ex.getCause() == null ? ex : ex.getCause();
    }

    private boolean enabled() {
        return config().isEnabled();
    }

    private AiLiveProperties.VTubeStudio config() {
        return properties.getIntegrations().getVtubeStudio();
    }
}
