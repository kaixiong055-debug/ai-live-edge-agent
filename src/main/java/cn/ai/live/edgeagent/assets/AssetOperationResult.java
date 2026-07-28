package cn.ai.live.edgeagent.assets;

public record AssetOperationResult(boolean success, String error, AssetMetadata asset, int statusCode) {
    public static AssetOperationResult ok(AssetMetadata asset) {
        return new AssetOperationResult(true, null, asset, 200);
    }

    public static AssetOperationResult created(AssetMetadata asset) {
        return new AssetOperationResult(true, null, asset, 201);
    }

    public static AssetOperationResult fail(String error, int statusCode) {
        return new AssetOperationResult(false, error, null, statusCode);
    }
}
