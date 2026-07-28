package cn.ai.live.edgeagent.action;

import java.util.Locale;

public final class ActionTargets {
    public static final String MEDIA = "MEDIA";
    public static final String VTUBE_STUDIO = "VTUBE_STUDIO";

    private ActionTargets() {
    }

    public static String normalizeOrDefault(String target) {
        if (target == null || target.isBlank()) {
            return MEDIA;
        }
        return target.trim().toUpperCase(Locale.ROOT);
    }
}
