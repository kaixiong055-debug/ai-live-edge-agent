package cn.ai.live.edgeagent;

import cn.ai.live.edgeagent.config.AiLiveProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(AiLiveProperties.class)
public class AiLiveEdgeAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiLiveEdgeAgentApplication.class, args);
    }
}
