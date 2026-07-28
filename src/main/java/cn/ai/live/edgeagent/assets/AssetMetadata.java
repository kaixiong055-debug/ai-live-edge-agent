package cn.ai.live.edgeagent.assets;

import java.time.Instant;

public record AssetMetadata(
        String fileName,
        String assetUrl,
        AssetType assetType,
        long size,
        Instant lastModifiedAt,
        boolean supported,
        boolean inUse
) {
}
