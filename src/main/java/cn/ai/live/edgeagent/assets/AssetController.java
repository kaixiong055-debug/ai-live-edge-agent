package cn.ai.live.edgeagent.assets;

import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/local-api/assets")
@ConditionalOnProperty(prefix = "ai-live.local-api", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AssetController {
    private final AssetManagementService assetManagementService;

    public AssetController(AssetManagementService assetManagementService) {
        this.assetManagementService = assetManagementService;
    }

    @GetMapping
    public List<AssetMetadata> list() {
        return assetManagementService.listAssets();
    }

    @PostMapping("/upload")
    public ResponseEntity<Object> upload(@RequestParam("file") MultipartFile file) {
        AssetOperationResult result = assetManagementService.upload(file);
        if (result.success()) {
            return ResponseEntity.status(result.statusCode()).body(result.asset());
        }
        return ResponseEntity.status(result.statusCode()).body(Map.of("success", false, "error", result.error()));
    }

    @DeleteMapping("/{fileName}")
    public ResponseEntity<Object> delete(@PathVariable String fileName) {
        AssetOperationResult result = assetManagementService.delete(fileName);
        if (result.success()) {
            return ResponseEntity.ok(Map.of("success", true));
        }
        return ResponseEntity.status(result.statusCode()).body(Map.of("success", false, "error", result.error()));
    }
}
