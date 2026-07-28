package cn.ai.live.edgeagent.integrations.vtubestudio;

import java.time.Instant;
import java.util.List;

public record VTubeStudioStatusSnapshot(
        boolean enabled,
        VTubeStudioConnectionStatus connectionStatus,
        String endpointHost,
        int endpointPort,
        boolean apiActive,
        String vTubeStudioVersion,
        boolean websocketConnected,
        boolean tokenPresent,
        boolean authenticated,
        boolean modelLoaded,
        String modelName,
        String modelId,
        int hotkeyCount,
        List<String> duplicateHotkeyNames,
        Instant lastConnectedAt,
        Instant lastAuthenticatedAt,
        Instant lastHotkeyRefreshAt,
        Instant lastActionAt,
        String lastActionName,
        String lastErrorCode,
        String lastErrorMessage
) {
}
