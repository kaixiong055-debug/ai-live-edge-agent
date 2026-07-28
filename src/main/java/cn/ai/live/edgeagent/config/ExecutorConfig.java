package cn.ai.live.edgeagent.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ExecutorConfig {
    @Bean(destroyMethod = "shutdownNow")
    public ExecutorService rendererSendExecutor() {
        return Executors.newFixedThreadPool(2, r -> new Thread(r, "renderer-ws-send"));
    }

    @Bean(destroyMethod = "shutdownNow")
    public ScheduledExecutorService actionScheduler() {
        return Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "action-scheduler"));
    }
}
