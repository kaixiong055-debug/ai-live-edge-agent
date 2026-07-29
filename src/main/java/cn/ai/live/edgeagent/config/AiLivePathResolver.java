package cn.ai.live.edgeagent.config;

import java.nio.file.Path;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 统一解析 Agent 的可写运行路径。
 *
 * <p>开发模式未配置 ai-live.data-dir 时，继续以当前工作目录为基准，保持原有启动方式兼容。
 * 正式 Desktop 安装模式会显式传入 %LOCALAPPDATA%\AI Live Edge，避免把用户数据写入安装目录。</p>
 */
@Component
public class AiLivePathResolver {
    private final Path baseDirectory;

    public AiLivePathResolver(AiLiveProperties properties) {
        String configured = properties.getDataDir();
        this.baseDirectory = StringUtils.hasText(configured)
                ? Path.of(configured).toAbsolutePath().normalize()
                : Path.of("").toAbsolutePath().normalize();
    }

    public Path baseDirectory() {
        return baseDirectory;
    }

    public Path resolve(String configuredPath) {
        if (!StringUtils.hasText(configuredPath)) {
            throw new IllegalArgumentException("运行路径不能为空");
        }
        Path path = Path.of(configuredPath);
        if (path.isAbsolute()) {
            return path.normalize();
        }
        Path resolved = baseDirectory.resolve(path).normalize();
        if (!resolved.startsWith(baseDirectory)) {
            throw new IllegalArgumentException("运行路径越界");
        }
        return resolved;
    }
}
