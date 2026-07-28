package cn.ai.live.edgeagent.action;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class ActionExecutorRegistry {
    private final Map<String, ActionExecutor> executors;

    public ActionExecutorRegistry(List<ActionExecutor> actionExecutors) {
        Map<String, ActionExecutor> map = new LinkedHashMap<>();
        for (ActionExecutor executor : actionExecutors) {
            String target = ActionTargets.normalizeOrDefault(executor.targetId());
            ActionExecutor previous = map.putIfAbsent(target, executor);
            if (previous != null) {
                throw new IllegalStateException("Duplicate ActionExecutor targetId: " + target);
            }
        }
        this.executors = Map.copyOf(map);
    }

    public Optional<ActionExecutor> getExecutor(String targetId) {
        return Optional.ofNullable(executors.get(ActionTargets.normalizeOrDefault(targetId)));
    }

    public List<String> registeredTargets() {
        return executors.keySet().stream().sorted().toList();
    }
}
