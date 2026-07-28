package cn.ai.live.edgeagent.integrations.vtubestudio;

import java.net.URI;
import java.util.concurrent.CompletionStage;

public interface VTubeStudioTransport extends AutoCloseable {
    CompletionStage<Void> connect(URI endpoint);

    CompletionStage<String> send(String requestId, String payload, long timeoutMs);

    boolean connected();

    @Override
    void close();
}
