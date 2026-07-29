package cn.ai.live.edgeagent.config;

import cn.ai.live.edgeagent.asr.SpeechRecognitionProviderType;
import cn.ai.live.edgeagent.command.WakeWordMode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

@Data
@Validated
@ConfigurationProperties(prefix = "ai-live")
public class AiLiveProperties {

    /** 可写用户数据根目录。为空时保持开发模式，以当前工作目录为基准。 */
    private String dataDir = "";

    @Valid
    private Audio audio = new Audio();
    @Valid
    private Console console = new Console();
    @Valid
    private Asr asr = new Asr();
    @Valid
    private Command command = new Command();
    @Valid
    private Renderer renderer = new Renderer();
    @Valid
    private Assets assets = new Assets();
    @Valid
    private LocalApi localApi = new LocalApi();
    @Valid
    private TencentCloud tencentCloud = new TencentCloud();
    @Valid
    private Runtime runtime = new Runtime();
    @Valid
    private Integrations integrations = new Integrations();

    @Data
    public static class Console {
        private boolean enabled = true;
        @Min(1000)
        private long refreshIntervalMs = 3000;
    }

    @Data
    public static class Audio {
        /** 高级配置：指定麦克风名称。为空时自动选择 Windows 默认录音设备，失败后选择其他兼容输入设备。 */
        private String deviceName = "";
        @Min(1000)
        private long autoScanIntervalMs = 5000;
    }

    @Data
    public static class Asr {
        private boolean enabled = true;
        private SpeechRecognitionProviderType provider = SpeechRecognitionProviderType.TENCENT;
        /** 是否启动后自动打开麦克风并启动语音识别。 */
        private boolean autoStart = true;
        /** 是否 Agent 启动后自动连接 ASR 和麦克风。默认 false，需要用户手动点击连接。 */
        private boolean autoConnect = false;
        /** 旧版兼容字段；普通用户无需填写麦克风名称。 */
        private String microphoneName = "";
        /** 腾讯云实时识别引擎，只有 provider=TENCENT 时使用。 */
        private String engineModelType = "16k_zh";
        /** 每帧 PCM 时长，接近实时发送。 */
        @Min(10)
        @Max(1000)
        private int audioFrameMillis = 40;
        /** 腾讯云连接异常后的退避重连间隔。 */
        private List<Duration> reconnectDelays = List.of(Duration.ofSeconds(1), Duration.ofSeconds(3), Duration.ofSeconds(5));
        @Valid
        private Sherpa sherpa = new Sherpa();
        @Valid
        private Tencent tencent = new Tencent();
        @Valid
        private Funasr funasr = new Funasr();
    }

    @Data
    public static class Sherpa {
        private boolean enabled = false;
        private String modelRoot = "data/models/sherpa-onnx/streaming-paraformer-zh-en";
        private String nativeRoot = "runtime/native/windows-x86_64";
        private String jarPath = "runtime/native/windows-x86_64/sherpa-onnx-v1.12.10.jar";
        private String encoder = "encoder.int8.onnx";
        private String decoder = "decoder.int8.onnx";
        private String tokens = "tokens.txt";
        private int numThreads = 2;
        private int sampleRate = 16000;
        private int featureDim = 80;
        private String decodingMethod = "greedy_search";
        private boolean enableEndpoint = true;
        private double rule1MinTrailingSilence = 2.4;
        private double rule2MinTrailingSilence = 1.2;
        private double rule3MinUtteranceLength = 20;
        private int maxActivePaths = 4;
        private boolean debug = false;
        private boolean kwsEnabled = false;
        private int queueCapacity = 50;
    }

    @Data
    public static class Tencent {
        private boolean enabled = true;
    }

    @Data
    public static class Funasr {
        private boolean enabled = false;
    }

    @Data
    public static class Command {
        /** 口令配置位置，支持 classpath: 或项目内文件路径。 */
        @NotBlank
        private String configPath = "commands.json";
        private boolean watchEnabled = true;
        @Min(100)
        private long reloadDebounceMs = 500;
        /** 唤醒词模式：REQUIRED / OPTIONAL / DISABLED。默认 OPTIONAL。 */
        private String wakeWordMode = "OPTIONAL";
        /** 全局唤醒词列表。commands.json 中的 wakeWords 也会参与匹配。 */
        private List<String> wakeWords = new ArrayList<>();
        /** 旧版兼容：wake-word-enabled=true 映射为 REQUIRED，false 映射为 DISABLED。仅在 wakeWordMode 未显式设置时生效。 */
        private Boolean wakeWordEnabled;

        /**
         * 解析当前有效的 WakeWordMode。
         * 优先级：wakeWordMode 配置 > 旧版 wakeWordEnabled 兼容映射 > 默认 OPTIONAL。
         */
        public WakeWordMode effectiveWakeWordMode() {
            // 新版显式配置优先
            if (wakeWordMode != null && !wakeWordMode.isBlank()) {
                WakeWordMode parsed = WakeWordMode.fromConfig(wakeWordMode);
                // 如果配置文件的值就是 OPTIONAL 且是默认值，检查是否有旧版兼容配置
                if (parsed == WakeWordMode.OPTIONAL && wakeWordEnabled != null) {
                    return wakeWordEnabled ? WakeWordMode.REQUIRED : WakeWordMode.DISABLED;
                }
                return parsed;
            }
            // 旧版兼容
            if (wakeWordEnabled != null) {
                return wakeWordEnabled ? WakeWordMode.REQUIRED : WakeWordMode.DISABLED;
            }
            return WakeWordMode.OPTIONAL;
        }

        /**
         * 合并全局 wakeWords 与 commands.json 中的 wakeWords。
         */
        public List<String> mergedWakeWords(List<String> configWakeWords) {
            List<String> merged = new ArrayList<>(wakeWords);
            if (configWakeWords != null) {
                for (String w : configWakeWords) {
                    if (w != null && !w.isBlank() && !merged.contains(w)) {
                        merged.add(w);
                    }
                }
            }
            return merged;
        }
    }

    @Data
    public static class Renderer {
        private boolean enabled = true;
        @Min(1)
        private int defaultWidth = 1920;
        @Min(1)
        private int defaultHeight = 1080;
    }

    @Data
    public static class Assets {
        /** 本地素材根目录，动作配置只能引用该目录下的文件。 */
        @NotBlank
        private String rootPath = "data/assets";
        private DataSize maxUploadSize = DataSize.ofMegabytes(200);
    }

    @Data
    public static class LocalApi {
        private boolean enabled = true;
    }

    @Data
    public static class TencentCloud {
        /** 腾讯云 AppId */
        private String appId;
        /** 腾讯云 SecretId */
        private String secretId;
        /** 腾讯云 SecretKey */
        private String secretKey;
    }

    @Data
    public static class Runtime {
        @Min(1)
        private int actionHistorySize = 100;
        @Min(1)
        private int errorHistorySize = 50;
    }

    @Data
    public static class Integrations {
        @Valid
        private VTubeStudio vtubeStudio = new VTubeStudio();
    }

    @Data
    public static class VTubeStudio {
        private boolean enabled = true;
        private String host = "127.0.0.1";
        @Min(1)
        @Max(65535)
        private int port = 8001;
        private String apiVersion = "1.0";
        private String pluginName = "AI Live Edge Agent";
        private String pluginDeveloper = "AI Live";
        /** VTube Studio Token 文件位置；Desktop 安装模式会传入用户 tokens 目录的绝对路径。 */
        private String tokenPath = "data/tokens/vtube-studio.token";
        private boolean connectOnStartup = true;
        @Min(100)
        private long requestTimeoutMs = 5000;
        @Valid
        private VTubeStudioReconnect reconnect = new VTubeStudioReconnect();
    }

    @Data
    public static class VTubeStudioReconnect {
        private boolean enabled = true;
        @Min(100)
        private long initialDelayMs = 1000;
        @Min(100)
        private long maxDelayMs = 30000;
        private double multiplier = 2.0;
    }
}
