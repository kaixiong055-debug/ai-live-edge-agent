package cn.ai.live.edgeagent.asr;

import cn.ai.live.edgeagent.audio.PcmFormatConverter;
import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.time.Instant;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/local-api/asr/test")
@ConditionalOnProperty(prefix = "ai-live.local-api", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AsrTestController {
    private static final long MAX_WAV_BYTES = 20L * 1024L * 1024L;

    private final SpeechRecognitionGateway gateway;
    private final ObjectProvider<SherpaOnnxSpeechRecognitionProvider> sherpaProvider;

    public AsrTestController(SpeechRecognitionGateway gateway, ObjectProvider<SherpaOnnxSpeechRecognitionProvider> sherpaProvider) {
        this.gateway = gateway;
        this.sherpaProvider = sherpaProvider;
    }

    @PostMapping("/file")
    public ResponseEntity<AsrFileTestResult> testFile(@RequestParam("file") MultipartFile file) {
        Instant start = Instant.now();
        if (file.isEmpty() || file.getSize() > MAX_WAV_BYTES) {
            return ResponseEntity.badRequest().body(fail("INVALID_FILE", start));
        }
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        if (name.contains("..") || !name.toLowerCase().endsWith(".wav")) {
            return ResponseEntity.badRequest().body(fail("INVALID_WAV", start));
        }
        try {
            byte[] wav = file.getBytes();
            if (!isRiffWave(wav)) {
                return ResponseEntity.badRequest().body(fail("INVALID_WAV", start));
            }
            if (!gateway.isReady() || gateway.getProviderType() != SpeechRecognitionProviderType.SHERPA_ONNX) {
                return ResponseEntity.ok(new AsrFileTestResult(providerName(), "",
                        0, elapsed(start), false, gateway.getStatus()));
            }
            WavPcm wavPcm = readAs16kPcm(wav);
            Instant inferenceStart = Instant.now();
            String text = sherpaProvider.getObject().recognizePcm16k(wavPcm.pcm());
            long inferenceMs = Duration.between(inferenceStart, Instant.now()).toMillis();
            return ResponseEntity.ok(new AsrFileTestResult(providerName(), text, wavPcm.durationMs(),
                    inferenceMs, !text.isBlank(), text.isBlank() ? "NO_RECOGNITION_RESULT" : null));
        } catch (Exception ex) {
            return ResponseEntity.internalServerError().body(fail("WAV_TEST_FAILED", start));
        }
    }

    private WavPcm readAs16kPcm(byte[] wav) throws Exception {
        try (AudioInputStream original = AudioSystem.getAudioInputStream(new ByteArrayInputStream(wav));
             AudioInputStream normalized = normalize(original)) {
            byte[] source = normalized.readAllBytes();
            AudioFormat format = normalized.getFormat();
            PcmFormatConverter converter = new PcmFormatConverter();
            byte[] pcm16k = converter.toAsrPcm16kMono(source, source.length, format);
            long durationMs = format.getFrameRate() <= 0 ? 0
                    : Math.round((source.length / (double) format.getFrameSize()) * 1000d / format.getFrameRate());
            return new WavPcm(pcm16k, durationMs);
        }
    }

    private AudioInputStream normalize(AudioInputStream input) {
        AudioFormat source = input.getFormat();
        boolean alreadySupported = source.getEncoding().equals(AudioFormat.Encoding.PCM_SIGNED)
                && source.getSampleSizeInBits() == 16
                && !source.isBigEndian();
        if (alreadySupported) {
            return input;
        }
        AudioFormat target = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, source.getSampleRate(), 16,
                source.getChannels(), source.getChannels() * 2, source.getSampleRate(), false);
        return AudioSystem.getAudioInputStream(target, input);
    }

    private boolean isRiffWave(byte[] wav) {
        return wav.length >= 44
                && wav[0] == 'R' && wav[1] == 'I' && wav[2] == 'F' && wav[3] == 'F'
                && wav[8] == 'W' && wav[9] == 'A' && wav[10] == 'V' && wav[11] == 'E';
    }

    private AsrFileTestResult fail(String code, Instant start) {
        return new AsrFileTestResult(providerName(), "", 0, elapsed(start), false, code);
    }

    private String providerName() {
        SpeechRecognitionProviderType type = gateway.getProviderType();
        return type == null ? "UNKNOWN" : type.name();
    }

    private long elapsed(Instant start) {
        return Duration.between(start, Instant.now()).toMillis();
    }

    private record WavPcm(byte[] pcm, long durationMs) {
    }
}
