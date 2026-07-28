package cn.ai.live.edgeagent.audio;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/local-api/audio")
@ConditionalOnProperty(prefix = "ai-live.local-api", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AudioController {
    private final AudioTestService audioTestService;

    public AudioController(AudioTestService audioTestService) {
        this.audioTestService = audioTestService;
    }

    @PostMapping("/test")
    public AudioTestSnapshot startTest() {
        return audioTestService.start();
    }

    @GetMapping("/test")
    public AudioTestSnapshot testStatus() {
        return audioTestService.snapshot();
    }
}
