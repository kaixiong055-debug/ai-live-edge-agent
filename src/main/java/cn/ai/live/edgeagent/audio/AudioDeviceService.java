package cn.ai.live.edgeagent.audio;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.TargetDataLine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class AudioDeviceService {

    public static final AudioFormat TARGET_ASR_FORMAT = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED,
            16000.0f, 16, 1, 2, 16000.0f, false);

    private static final float[] SAMPLE_RATES = {48000.0f, 44100.0f, 32000.0f, 16000.0f};
    private static final int[] CHANNELS = {1, 2};

    public List<String> listMicrophoneDevices() {
        List<String> devices = new ArrayList<>();
        for (Mixer.Info mixerInfo : AudioSystem.getMixerInfo()) {
            Mixer mixer = AudioSystem.getMixer(mixerInfo);
            for (AudioFormat format : candidateFormats()) {
                if (mixer.isLineSupported(new DataLine.Info(TargetDataLine.class, format))) {
                    devices.add(mixerInfo.getName());
                    break;
                }
            }
        }
        return devices;
    }

    public AudioCaptureLine openCaptureLine(String configuredName) throws AudioOpenException {
        if (configuredName != null && !configuredName.isBlank()) {
            return openConfiguredDevice(configuredName);
        }
        try {
            return openFirstCompatibleMixer(null);
        } catch (AudioOpenException ex) {
            if (ex.status() != MicrophoneStatus.NO_DEVICE) {
                throw ex;
            }
            log.info("未找到可直接打开的真实输入设备，尝试 Windows 默认录音设备: {}", ex.getMessage());
        }
        try {
            return openDefaultDevice();
        } catch (AudioOpenException ex) {
            throw ex;
        }
    }

    private AudioCaptureLine openConfiguredDevice(String configuredName) throws AudioOpenException {
        return openFirstCompatibleMixer(configuredName);
    }

    private AudioCaptureLine openDefaultDevice() throws AudioOpenException {
        AudioOpenException last = null;
        boolean supportedAny = false;
        for (AudioFormat format : candidateFormats()) {
            DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);
            if (!AudioSystem.isLineSupported(info)) {
                continue;
            }
            supportedAny = true;
            try {
                TargetDataLine line = (TargetDataLine) AudioSystem.getLine(info);
                line.open(format);
                line.start();
                return new AudioCaptureLine(line, "Windows 默认录音设备", format);
            } catch (LineUnavailableException ex) {
                last = classifyOpenFailure(ex);
            } catch (SecurityException ex) {
                throw new AudioOpenException(MicrophoneStatus.PERMISSION_DENIED, "Windows 麦克风权限不可用", ex);
            } catch (Exception ex) {
                last = new AudioOpenException(MicrophoneStatus.FAILED, ex.getMessage(), ex);
            }
        }
        if (last != null) {
            throw last;
        }
        throw new AudioOpenException(supportedAny ? MicrophoneStatus.FAILED : MicrophoneStatus.NO_DEVICE,
                "未检测到 Windows 默认录音设备", null);
    }

    private AudioCaptureLine openFirstCompatibleMixer(String nameFilter) throws AudioOpenException {
        AudioOpenException last = null;
        boolean sawCandidate = false;
        for (Mixer.Info mixerInfo : sortedMixerInfos()) {
            if (nameFilter != null && !mixerInfo.getName().contains(nameFilter)) {
                continue;
            }
            Mixer mixer = AudioSystem.getMixer(mixerInfo);
            for (AudioFormat format : candidateFormats()) {
                DataLine.Info lineInfo = new DataLine.Info(TargetDataLine.class, format);
                if (!mixer.isLineSupported(lineInfo)) {
                    continue;
                }
                sawCandidate = true;
                try {
                    TargetDataLine line = (TargetDataLine) mixer.getLine(lineInfo);
                    line.open(format);
                    line.start();
                    return new AudioCaptureLine(line, mixerInfo.getName(), format);
                } catch (LineUnavailableException ex) {
                    last = classifyOpenFailure(ex);
                } catch (SecurityException ex) {
                    throw new AudioOpenException(MicrophoneStatus.PERMISSION_DENIED, "Windows 麦克风权限不可用", ex);
                } catch (Exception ex) {
                    last = new AudioOpenException(MicrophoneStatus.FAILED, ex.getMessage(), ex);
                }
            }
        }
        if (last != null) {
            throw last;
        }
        throw new AudioOpenException(sawCandidate ? MicrophoneStatus.FAILED : MicrophoneStatus.NO_DEVICE,
                nameFilter == null ? "未检测到麦克风，请连接麦克风。" : "未找到指定麦克风: " + nameFilter, null);
    }

    private AudioOpenException classifyOpenFailure(LineUnavailableException ex) {
        String message = ex.getMessage() == null ? "" : ex.getMessage().toLowerCase();
        if (message.contains("permission") || message.contains("access denied") || message.contains("denied")) {
            return new AudioOpenException(MicrophoneStatus.PERMISSION_DENIED, "Windows 麦克风权限不可用", ex);
        }
        return new AudioOpenException(MicrophoneStatus.DEVICE_BUSY, "麦克风可能被其他程序占用", ex);
    }

    private List<Mixer.Info> sortedMixerInfos() {
        List<Mixer.Info> infos = new ArrayList<>(List.of(AudioSystem.getMixerInfo()));
        infos.sort(Comparator.comparingInt(info -> devicePriority(info.getName())));
        return infos;
    }

    int devicePriority(String name) {
        String lower = name == null ? "" : name.toLowerCase();
        int score = 100;
        if (lower.contains("麦克风") || lower.contains("microphone") || lower.contains("mic")) {
            score -= 80;
        }
        if (lower.contains("主声音捕获驱动程序")
                || lower.contains("primary sound capture driver")
                || lower.contains("java sound audio engine")
                || lower.contains("port mixer")) {
            score += 80;
        }
        return score;
    }

    int signalAwareDeviceScore(String name, double rawPeak) {
        int score = 200 - devicePriority(name);
        if (rawPeak > 0.02d) {
            score += 200;
        }
        return score;
    }

    private List<AudioFormat> candidateFormats() {
        List<AudioFormat> formats = new ArrayList<>();
        for (float sampleRate : SAMPLE_RATES) {
            for (int channels : CHANNELS) {
                formats.add(new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, sampleRate, 16, channels,
                        channels * 2, sampleRate, false));
            }
        }
        return formats;
    }
}
