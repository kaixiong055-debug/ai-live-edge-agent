package cn.ai.live.edgeagent.assets;

import java.nio.file.Path;

public record AssetResolveResult(boolean success, String assetUrl, Path filePath, String error) {
    public static AssetResolveResult success(String assetUrl, Path filePath) {
        return new AssetResolveResult(true, assetUrl, filePath, null);
    }

    public static AssetResolveResult failure(String error) {
        return new AssetResolveResult(false, null, null, error);
    }
}
