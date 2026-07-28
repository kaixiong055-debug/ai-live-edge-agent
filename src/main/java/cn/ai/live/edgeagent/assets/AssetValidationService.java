package cn.ai.live.edgeagent.assets;

import cn.ai.live.edgeagent.config.AiLiveProperties;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class AssetValidationService {
    private static final Set<String> ALLOWED = Set.of("png", "jpg", "jpeg", "webp", "gif", "webm");
    private final long maxUploadBytes;

    public AssetValidationService(AiLiveProperties properties) {
        this.maxUploadBytes = properties.getAssets().getMaxUploadSize().toBytes();
    }

    public String safeFileName(String originalName) {
        if (originalName == null || originalName.isBlank()) {
            throw new IllegalArgumentException("文件名不能为空");
        }
        String name = originalName.trim();
        if (name.startsWith(".") || name.contains("/") || name.contains("\\") || name.contains("..")
                || java.nio.file.Path.of(name).isAbsolute()) {
            throw new IllegalArgumentException("文件名不安全");
        }
        if (!ALLOWED.contains(extension(name))) {
            throw new IllegalArgumentException("不支持的素材格式");
        }
        return name;
    }

    public void validateUpload(MultipartFile file, String safeFileName) throws IOException {
        if (file.isEmpty() || file.getSize() <= 0) {
            throw new IllegalArgumentException("素材文件不能为空");
        }
        if (file.getSize() > maxUploadBytes) {
            throw new IllegalArgumentException("素材文件超过大小限制");
        }
        String ext = extension(safeFileName);
        byte[] header = readHeader(file, 16);
        if (!headerMatches(ext, header)) {
            throw new IllegalArgumentException("素材文件头与扩展名不匹配");
        }
    }

    public AssetType assetType(String fileName) {
        return switch (extension(fileName)) {
            case "gif" -> AssetType.GIF;
            case "webm" -> AssetType.WEBM;
            case "png", "jpg", "jpeg", "webp" -> AssetType.IMAGE;
            default -> AssetType.UNSUPPORTED;
        };
    }

    public boolean supported(String fileName) {
        return ALLOWED.contains(extension(fileName));
    }

    private byte[] readHeader(MultipartFile file, int size) throws IOException {
        try (InputStream inputStream = file.getInputStream()) {
            return inputStream.readNBytes(size);
        }
    }

    private boolean headerMatches(String ext, byte[] h) {
        return switch (ext) {
            case "png" -> h.length >= 8 && h[0] == (byte) 0x89 && h[1] == 0x50 && h[2] == 0x4E && h[3] == 0x47;
            case "jpg", "jpeg" -> h.length >= 2 && h[0] == (byte) 0xFF && h[1] == (byte) 0xD8;
            case "gif" -> h.length >= 6 && h[0] == 0x47 && h[1] == 0x49 && h[2] == 0x46;
            case "webp" -> h.length >= 12 && h[0] == 0x52 && h[1] == 0x49 && h[2] == 0x46 && h[3] == 0x46
                    && h[8] == 0x57 && h[9] == 0x45 && h[10] == 0x42 && h[11] == 0x50;
            case "webm" -> h.length >= 4 && h[0] == 0x1A && h[1] == 0x45 && h[2] == (byte) 0xDF && h[3] == (byte) 0xA3;
            default -> false;
        };
    }

    private String extension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
