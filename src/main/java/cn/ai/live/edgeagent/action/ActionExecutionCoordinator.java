package cn.ai.live.edgeagent.action;

import java.time.Clock;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class ActionExecutionCoordinator {
    private final Clock clock;
    private final ScheduledExecutorService scheduler;
    private ScheduledFuture<?> clearFuture;
    @Getter
    private ActiveAction currentAction;

    @Autowired
    public ActionExecutionCoordinator(ScheduledExecutorService actionScheduler) {
        this(Clock.systemDefaultZone(), actionScheduler);
    }

    public ActionExecutionCoordinator(Clock clock, ScheduledExecutorService scheduler) {
        this.clock = clock;
        this.scheduler = scheduler;
    }

    public synchronized ActionExecutionResult accept(ActionCommand command, Runnable timeoutCallback) {
        if (command.actionType() == ActionType.CLEAR || command.actionType() == ActionType.HIDE) {
            cancelTimeout();
            currentAction = null;
            return ActionExecutionResult.ok();
        }
        if (currentAction != null && command.priority() < currentAction.priority()) {
            return ActionExecutionResult.rejected("lower-priority");
        }
        cancelTimeout();
        currentAction = new ActiveAction(command.actionCode(), command.priority(), clock.millis(), command.durationMs());
        if (command.durationMs() > 0) {
            clearFuture = scheduler.schedule(() -> {
                synchronized (ActionExecutionCoordinator.this) {
                    if (currentAction != null && currentAction.actionCode().equals(command.actionCode())) {
                        currentAction = null;
                    }
                }
                timeoutCallback.run();
            }, command.durationMs(), TimeUnit.MILLISECONDS);
        }
        return ActionExecutionResult.ok();
    }

    public synchronized Optional<ActiveAction> activeAction() {
        return Optional.ofNullable(currentAction);
    }

    private void cancelTimeout() {
        if (clearFuture != null) {
            clearFuture.cancel(false);
            clearFuture = null;
        }
    }

    public record ActiveAction(String actionCode, int priority, long startedAt, long durationMs) {
    }
}
