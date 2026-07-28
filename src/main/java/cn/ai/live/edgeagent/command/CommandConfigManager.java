package cn.ai.live.edgeagent.command;

import cn.ai.live.edgeagent.config.AiLiveProperties;
import cn.ai.live.edgeagent.runtime.RuntimeEventRecorder;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class CommandConfigManager {
    private final ObjectMapper objectMapper;
    private final AiLiveProperties properties;
    private final CommandConfigValidator validator;
    private final RuntimeEventRecorder recorder;
    private final DefaultResourceLoader resourceLoader = new DefaultResourceLoader();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ScheduledExecutorService debounceExecutor = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "command-reload-debounce"));
    private ExecutorService watchExecutor;
    private java.nio.file.WatchService watchService;
    private ScheduledFuture<?> pendingReload;
    private volatile CommandConfigSnapshot snapshot = new CommandConfigSnapshot(new CommandConfig(), "NOT_LOADED", null, null, 0);

    public CommandConfigManager(ObjectMapper objectMapper, AiLiveProperties properties,
                                CommandConfigValidator validator, RuntimeEventRecorder recorder) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.validator = validator;
        this.recorder = recorder;
    }

    @PostConstruct
    public void start() {
        reload();
        if (properties.getCommand().isWatchEnabled()) {
            startWatch();
        }
    }

    public synchronized CommandConfigSnapshot reload() {
        try {
            Path path = ensureConfigFile();
            CommandConfig config = objectMapper.readValue(path.toFile(), CommandConfig.class);
            defaultConfig(config);
            validator.validate(config);
            snapshot = new CommandConfigSnapshot(config, "LOADED", Instant.now(), null, snapshot.version() + 1);
            log.info("commands.json 已加载: commands={}, version={}", config.getCommands().size(), snapshot.version());
        } catch (Exception ex) {
            String message = ex.getMessage();
            snapshot = new CommandConfigSnapshot(snapshot.config(), "ERROR", snapshot.lastLoadedAt(), message, snapshot.version());
            recorder.recordError("COMMAND_CONFIG_LOAD_FAILED", message, "CommandConfigManager");
            log.warn("commands.json 加载失败，保留上一份有效配置: {}", message);
        }
        return snapshot;
    }

    public CommandConfig currentConfig() {
        return snapshot.config();
    }

    public CommandConfigSnapshot snapshot() {
        return snapshot;
    }

    private void startWatch() {
        Path path;
        try {
            path = ensureConfigFile();
            Path parent = path.toAbsolutePath().normalize().getParent();
            watchService = parent.getFileSystem().newWatchService();
            parent.register(watchService, java.nio.file.StandardWatchEventKinds.ENTRY_CREATE,
                    java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY,
                    java.nio.file.StandardWatchEventKinds.ENTRY_DELETE);
            running.set(true);
            watchExecutor = Executors.newSingleThreadExecutor(r -> new Thread(r, "command-config-watch"));
            watchExecutor.submit(() -> watchLoop(path.getFileName()));
            log.info("commands.json 热加载监听已启动: {}", path);
        } catch (Exception ex) {
            recorder.recordError("COMMAND_WATCH_FAILED", ex.getMessage(), "CommandConfigManager");
            log.warn("commands.json 热加载监听启动失败", ex);
        }
    }

    private void watchLoop(Path watchedFileName) {
        while (running.get()) {
            try {
                java.nio.file.WatchKey key = watchService.poll(500, TimeUnit.MILLISECONDS);
                if (key == null) {
                    continue;
                }
                for (java.nio.file.WatchEvent<?> event : key.pollEvents()) {
                    if (watchedFileName.equals((Path) event.context())) {
                        debounceReload();
                    }
                }
                key.reset();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception ex) {
                recorder.recordError("COMMAND_WATCH_ERROR", ex.getMessage(), "CommandConfigManager");
            }
        }
    }

    private synchronized void debounceReload() {
        if (pendingReload != null) {
            pendingReload.cancel(false);
        }
        pendingReload = debounceExecutor.schedule(this::reload, properties.getCommand().getReloadDebounceMs(), TimeUnit.MILLISECONDS);
    }

    private Path ensureConfigFile() throws Exception {
        String value = properties.getCommand().getConfigPath();
        if (value.startsWith("classpath:")) {
            throw new IllegalStateException("commands.json 热加载需要文件系统路径，请使用 commands.json 或绝对/相对路径");
        }
        Path path = Path.of(value).toAbsolutePath().normalize();
        if (!Files.exists(path)) {
            Resource resource = resourceLoader.getResource("classpath:commands.json");
            try (InputStream inputStream = resource.getInputStream()) {
                Files.copy(inputStream, path, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        return path;
    }

    private void defaultConfig(CommandConfig config) {
        for (CommandDefinition command : config.getCommands()) {
            if (command.getName() == null || command.getName().isBlank()) {
                command.setName(command.getCode());
            }
        }
    }

    public List<CommandDefinition> commands() {
        return currentConfig().getCommands();
    }

    @PreDestroy
    public void stop() {
        running.set(false);
        if (pendingReload != null) {
            pendingReload.cancel(false);
        }
        debounceExecutor.shutdownNow();
        if (watchExecutor != null) {
            watchExecutor.shutdownNow();
        }
        if (watchService != null) {
            try {
                watchService.close();
            } catch (Exception ignored) {
                // 关闭阶段不需要向用户暴露堆栈。
            }
        }
    }
}
