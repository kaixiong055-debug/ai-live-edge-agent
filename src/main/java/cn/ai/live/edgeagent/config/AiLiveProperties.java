package cn.ai.live.edgeagent.config;

import java.time.Duration;
import java.util.List;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "ai-live")
public class AiLiveProperties {

    private Asr asr = new Asr();
    private Command command = new Command();
    private Renderer renderer = new Renderer();
    private Assets assets = new Assets();
    private LocalApi localApi = new LocalApi();

    @Data
    public static class Asr {
        /** 是否启动后自动打开麦克风并连接实时识别。 */
        private boolean autoStart = true;
        /** 麦克风名称关键字。为空时使用系统默认麦克风。 */
        private String microphoneName = "";
        /** 腾讯云实时识别引擎。 */
        private String engineModelType = "16k_zh";
        /** 每帧 PCM 时长，接近实时发送。 */
        private int audioFrameMillis = 40;
        /** 连接异常后的退避重连间隔。 */
        private List<Duration> reconnectDelays = List.of(Duration.ofSeconds(1), Duration.ofSeconds(3), Duration.ofSeconds(5));
    }

    @Data
    public static class Command {
        /** 口令配置位置，支持 classpath: 或文件路径。 */
        private String configPath = "classpath:commands.json";
    }

    @Data
    public static class Renderer {
        private boolean enabled = true;
        private int defaultWidth = 1920;
        private int defaultHeight = 1080;
    }

    @Data
    public static class Assets {
        /** 本地素材根目录，动作配置只能引用该目录下文件。 */
        private String rootPath = "data/assets";
    }

    @Data
    public static class LocalApi {
        private boolean enabled = true;
    }
}
