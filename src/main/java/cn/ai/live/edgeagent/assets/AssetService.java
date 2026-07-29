package cn.ai.live.edgeagent.assets;

import cn.ai.live.edgeagent.action.ActionType;
import cn.ai.live.edgeagent.config.AiLivePathResolver;
import cn.ai.live.edgeagent.config.AiLiveProperties;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Slf4j
@Service
public class AssetService {
    private static final Set<String> IMAGE_EXT = Set.of("png", "jpg", "jpeg", "webp");
    private final Path rootPath;

    public AssetService(AiLiveProperties properties, AiLivePathResolver pathResolver) {
        this.rootPath = pathResolver.resolve(properties.getAssets().getRootPath());
    }

    public AssetResolveResult resolve(String assetPath, ActionType actionType) {
        if (actionType == ActionType.HIDE || actionType == ActionType.CLEAR) {
            return AssetResolveResult.success(null, null);
        }
        if (!StringUtils.hasText(assetPath)) {
            return AssetResolveResult.failure("assetPath is required");
        }
        String normalizedInput = assetPath.replace('\\', '/');
        if (normalizedInput.startsWith("/") || normalizedInput.contains("../") || normalizedInput.equals("..")) {
            return AssetResolveResult.failure("asset path traversal rejected");
        }
        Path resolved = rootPath.resolve(normalizedInput).normalize();
        if (!resolved.startsWith(rootPath)) {
            return AssetResolveResult.failure("asset path traversal rejected");
        }
        String extension = extension(resolved);
        if (!isSupported(extension, actionType)) {
            return AssetResolveResult.failure("unsupported asset format: " + extension);
        }
        if (!Files.exists(resolved) || !Files.isRegularFile(resolved)) {
            return AssetResolveResult.failure("asset not found: " + assetPath);
        }
        String assetUrl = "/local-assets/" + URLEncoder.encode(normalizedInput, StandardCharsets.UTF_8).replace("+", "%20").replace("%2F", "/");
        return AssetResolveResult.success(assetUrl, resolved);
    }

    public Path rootPath() {
        return rootPath;
    }

    public boolean rootExists() {
        return Files.isDirectory(rootPath);
    }

    private boolean isSupported(String extension, ActionType actionType) {
        return switch (actionType) {
            case SHOW_IMAGE -> IMAGE_EXT.contains(extension);
            case PLAY_GIF -> "gif".equals(extension);
            case PLAY_WEBM -> "webm".equals(extension);
            case HIDE, CLEAR -> true;
            case TRIGGER_HOTKEY -> false;
        };
    }

    private String extension(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
