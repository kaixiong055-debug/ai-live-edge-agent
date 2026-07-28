package cn.ai.live.edgeagent.assets;

import cn.ai.live.edgeagent.action.ActionExecutionCoordinator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class AssetManagementService {
    private final AssetService assetService;
    private final AssetValidationService validationService;
    private final ActionExecutionCoordinator coordinator;

    public AssetManagementService(AssetService assetService, AssetValidationService validationService, ActionExecutionCoordinator coordinator) {
        this.assetService = assetService;
        this.validationService = validationService;
        this.coordinator = coordinator;
    }

    public List<AssetMetadata> listAssets() {
        try {
            Files.createDirectories(assetService.rootPath());
            try (var stream = Files.list(assetService.rootPath())) {
                return stream.filter(Files::isRegularFile)
                        .map(this::metadata)
                        .sorted(Comparator.comparing(AssetMetadata::fileName))
                        .toList();
            }
        } catch (Exception ex) {
            return List.of();
        }
    }

    public AssetOperationResult upload(MultipartFile file) {
        Path temp = null;
        try {
            Files.createDirectories(assetService.rootPath());
            String name = validationService.safeFileName(file.getOriginalFilename());
            Path target = assetService.rootPath().resolve(name).normalize();
            if (!target.startsWith(assetService.rootPath())) {
                return AssetOperationResult.fail("素材路径越界", 400);
            }
            if (Files.exists(target)) {
                return AssetOperationResult.fail("同名素材已存在", 409);
            }
            validationService.validateUpload(file, name);
            temp = Files.createTempFile(assetService.rootPath(), ".upload-", ".tmp");
            file.transferTo(temp);
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
            return AssetOperationResult.created(metadata(target));
        } catch (IllegalArgumentException ex) {
            return AssetOperationResult.fail(ex.getMessage(), 400);
        } catch (Exception ex) {
            return AssetOperationResult.fail("素材上传失败", 500);
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (Exception ignored) {
                }
            }
        }
    }

    public AssetOperationResult delete(String fileName) {
        try {
            String name = validationService.safeFileName(fileName);
            Path target = assetService.rootPath().resolve(name).normalize();
            if (!target.startsWith(assetService.rootPath())) {
                return AssetOperationResult.fail("素材路径越界", 400);
            }
            if (!Files.exists(target)) {
                return AssetOperationResult.fail("素材不存在", 404);
            }
            if (!Files.isRegularFile(target)) {
                return AssetOperationResult.fail("只能删除普通素材文件", 400);
            }
            if (coordinator.activeAction().map(active -> name.equals(active.assetPath())).orElse(false)) {
                return AssetOperationResult.fail("素材正在被当前动作使用", 409);
            }
            Files.delete(target);
            return AssetOperationResult.ok(null);
        } catch (IllegalArgumentException ex) {
            return AssetOperationResult.fail(ex.getMessage(), 400);
        } catch (Exception ex) {
            return AssetOperationResult.fail("素材删除失败", 500);
        }
    }

    public long countAssets() {
        return listAssets().size();
    }

    private AssetMetadata metadata(Path path) {
        String name = path.getFileName().toString();
        try {
            return new AssetMetadata(name, "/local-assets/" + name, validationService.assetType(name), Files.size(path),
                    Instant.ofEpochMilli(Files.getLastModifiedTime(path).toMillis()),
                    validationService.supported(name),
                    coordinator.activeAction().map(active -> name.equals(active.assetPath())).orElse(false));
        } catch (Exception ex) {
            return new AssetMetadata(name, "/local-assets/" + name, AssetType.UNSUPPORTED, 0, null, false, false);
        }
    }
}
