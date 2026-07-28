package cn.ai.live.edgeagent.assets;

import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class LocalAssetController {
    private final AssetService assetService;

    public LocalAssetController(AssetService assetService) {
        this.assetService = assetService;
    }

    @GetMapping("/local-assets/{*assetPath}")
    public ResponseEntity<FileSystemResource> getAsset(@PathVariable String assetPath) throws Exception {
        String cleanPath = assetPath.startsWith("/") ? assetPath.substring(1) : assetPath;
        Path root = assetService.rootPath();
        Path resolved = root.resolve(cleanPath).normalize();
        if (!resolved.startsWith(root) || !Files.isRegularFile(resolved)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(Files.probeContentType(resolved) == null
                        ? "application/octet-stream"
                        : Files.probeContentType(resolved)))
                .body(new FileSystemResource(resolved));
    }
}
