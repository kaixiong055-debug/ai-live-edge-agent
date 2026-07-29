package cn.ai.live.edgeagent.integrations.vtubestudio;

import cn.ai.live.edgeagent.config.AiLivePathResolver;
import cn.ai.live.edgeagent.config.AiLiveProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class VTubeStudioTokenStore {
    private final Path tokenFile;

    public VTubeStudioTokenStore(AiLiveProperties properties, AiLivePathResolver pathResolver) {
        this.tokenFile = pathResolver.resolve(properties.getIntegrations().getVtubeStudio().getTokenPath());
    }

    public Optional<String> load() {
        try {
            if (!Files.isRegularFile(tokenFile)) {
                return Optional.empty();
            }
            String token = Files.readString(tokenFile, StandardCharsets.UTF_8).trim();
            return token.isBlank() ? Optional.empty() : Optional.of(token);
        } catch (IOException ex) {
            return Optional.empty();
        }
    }

    public boolean tokenPresent() {
        return load().isPresent();
    }

    public void save(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("VTube Studio token 不能为空");
        }
        try {
            Files.createDirectories(tokenFile.getParent());
            Path tmp = tokenFile.resolveSibling(tokenFile.getFileName() + ".tmp");
            Files.writeString(tmp, token, StandardCharsets.UTF_8);
            tmp.toFile().setReadable(false, false);
            tmp.toFile().setReadable(true, true);
            tmp.toFile().setWritable(false, false);
            tmp.toFile().setWritable(true, true);
            Files.move(tmp, tokenFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ex) {
            throw new VTubeStudioApiException(VTubeStudioErrorCodes.VTS_API_ERROR, "保存 VTube Studio Token 失败");
        }
    }

    public void delete() {
        try {
            Files.deleteIfExists(tokenFile);
        } catch (IOException ignored) {
            // 删除失败时不暴露 token 路径或内容。
        }
    }
}
