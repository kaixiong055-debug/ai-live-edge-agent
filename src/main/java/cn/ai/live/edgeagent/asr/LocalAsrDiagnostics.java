package cn.ai.live.edgeagent.asr;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

@Component
public class LocalAsrDiagnostics {
    private final AtomicLong bytesAccepted = new AtomicLong();
    private final AtomicLong bytesDropped = new AtomicLong();
    private final AtomicLong decodeCount = new AtomicLong();
    private final AtomicLong partialCount = new AtomicLong();
    private final AtomicLong finalCount = new AtomicLong();
    private volatile String modelStatus = SpeechRecognitionStatus.STOPPED.name();
    private volatile String modelName = "Sherpa-ONNX Streaming Paraformer";
    private volatile String modelVersion = "streaming-paraformer-zh-en-int8";
    private volatile String nativeVersion = "sherpa-onnx-1.12.10";
    private volatile int queueSize;
    private volatile int queueCapacity;
    private volatile String lastPartialText;
    private volatile String lastFinalText;
    private volatile Instant lastResultAt;
    private volatile String lastError;

    public void accepted(int bytes, int size, int capacity) {
        bytesAccepted.addAndGet(bytes);
        queueSize = size;
        queueCapacity = capacity;
    }

    public void dropped(int bytes, int size, int capacity) {
        bytesDropped.addAndGet(bytes);
        queueSize = size;
        queueCapacity = capacity;
    }

    public void decoded() {
        decodeCount.incrementAndGet();
    }

    public void partial(String text) {
        partialCount.incrementAndGet();
        lastPartialText = text;
        lastResultAt = Instant.now();
    }

    public void finalResult(String text) {
        finalCount.incrementAndGet();
        lastFinalText = text;
        lastResultAt = Instant.now();
    }

    public void modelStatus(String status) {
        modelStatus = status;
    }

    public void error(String error) {
        lastError = error;
    }

    public long bytesAccepted() { return bytesAccepted.get(); }
    public long bytesDropped() { return bytesDropped.get(); }
    public long decodeCount() { return decodeCount.get(); }
    public long partialCount() { return partialCount.get(); }
    public long finalCount() { return finalCount.get(); }
    public String modelStatus() { return modelStatus; }
    public String modelName() { return modelName; }
    public String modelVersion() { return modelVersion; }
    public String nativeVersion() { return nativeVersion; }
    public int queueSize() { return queueSize; }
    public int queueCapacity() { return queueCapacity; }
    public String lastPartialText() { return lastPartialText; }
    public String lastFinalText() { return lastFinalText; }
    public Instant lastResultAt() { return lastResultAt; }
    public String lastError() { return lastError; }
}
