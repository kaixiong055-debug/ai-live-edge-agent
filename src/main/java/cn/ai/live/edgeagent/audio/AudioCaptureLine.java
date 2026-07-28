package cn.ai.live.edgeagent.audio;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.TargetDataLine;

public record AudioCaptureLine(TargetDataLine line, String deviceName, AudioFormat sourceFormat) {
}
