package cn.ai.live.edgeagent.audio;

import java.util.ArrayList;
import java.util.List;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.TargetDataLine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class AudioDeviceService {

    public static final AudioFormat PCM_16K_MONO = new AudioFormat(16000.0f, 16, 1, true, false);

    public List<String> listMicrophoneDevices() {
        List<String> devices = new ArrayList<>();
        DataLine.Info lineInfo = new DataLine.Info(TargetDataLine.class, PCM_16K_MONO);
        for (Mixer.Info mixerInfo : AudioSystem.getMixerInfo()) {
            Mixer mixer = AudioSystem.getMixer(mixerInfo);
            if (mixer.isLineSupported(lineInfo)) {
                devices.add(mixerInfo.getName());
            }
        }
        return devices;
    }

    public TargetDataLine openTargetDataLine(String configuredName) throws Exception {
        DataLine.Info lineInfo = new DataLine.Info(TargetDataLine.class, PCM_16K_MONO);
        if (configuredName != null && !configuredName.isBlank()) {
            for (Mixer.Info mixerInfo : AudioSystem.getMixerInfo()) {
                if (mixerInfo.getName().contains(configuredName)) {
                    Mixer mixer = AudioSystem.getMixer(mixerInfo);
                    if (mixer.isLineSupported(lineInfo)) {
                        TargetDataLine line = (TargetDataLine) mixer.getLine(lineInfo);
                        line.open(PCM_16K_MONO);
                        log.info("使用配置麦克风: {}", mixerInfo.getName());
                        return line;
                    }
                }
            }
            log.warn("未找到配置麦克风关键字: {}，尝试使用默认麦克风", configuredName);
        }
        TargetDataLine line = (TargetDataLine) AudioSystem.getLine(lineInfo);
        line.open(PCM_16K_MONO);
        log.info("使用系统默认麦克风");
        return line;
    }
}
