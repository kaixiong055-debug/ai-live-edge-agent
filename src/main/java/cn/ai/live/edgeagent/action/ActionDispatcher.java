package cn.ai.live.edgeagent.action;

import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class ActionDispatcher {
    private final List<ActionExecutor> executors;

    public ActionDispatcher(List<ActionExecutor> executors) {
        this.executors = executors;
    }

    public ActionExecutionResult dispatch(ActionCommand command) {
        System.out.println("[ACTION] actionCode=%s, actionName=%s".formatted(command.actionCode(), command.actionName()));
        ActionExecutionResult last = ActionExecutionResult.ok();
        for (ActionExecutor executor : executors) {
            last = executor.execute(command);
            if (!last.accepted()) {
                log.info("动作未执行: code={}, reason={}", command.actionCode(), last.reason());
            }
        }
        return last;
    }
}
