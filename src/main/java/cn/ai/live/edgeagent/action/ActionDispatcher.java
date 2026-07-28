package cn.ai.live.edgeagent.action;

import cn.ai.live.edgeagent.runtime.ActionRecordResult;
import cn.ai.live.edgeagent.runtime.ActionSource;
import cn.ai.live.edgeagent.runtime.RuntimeEventRecorder;
import java.time.Instant;
import java.util.concurrent.CompletionStage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class ActionDispatcher {
    private final ActionExecutorRegistry registry;
    private final RuntimeEventRecorder recorder;

    public ActionDispatcher(ActionExecutorRegistry registry, RuntimeEventRecorder recorder) {
        this.registry = registry;
        this.recorder = recorder;
    }

    public ActionExecutionResult dispatch(ActionCommand command) {
        return dispatch(command, ActionSource.SYSTEM);
    }

    public ActionExecutionResult dispatch(ActionCommand command, ActionSource source) {
        System.out.println("[ACTION] actionCode=%s, actionName=%s".formatted(command.actionCode(), command.actionName()));
        ActionExecutionRequest request = ActionExecutionRequest.from(command);
        ActionExecutionContext context = ActionExecutionContext.of(source);
        ActionExecutor executor = registry.getExecutor(request.target()).orElse(null);
        if (executor == null) {
            ActionExecutionResult result = ActionExecutionResult.failure(request, ActionExecutionStatus.FAILED,
                    "UNSUPPORTED_TARGET", "未注册动作适配器: " + request.target(), Instant.now());
            record(command, result, source);
            return result;
        }
        if (!executor.supports(request)) {
            ActionExecutionResult result = ActionExecutionResult.failure(request, ActionExecutionStatus.INVALID_ACTION,
                    "INVALID_ACTION", "适配器不支持动作类型: " + request.actionType(), Instant.now());
            record(command, result, source);
            return result;
        }
        CompletionStage<ActionExecutionResult> stage;
        try {
            stage = executor.execute(request, context);
        } catch (Exception ex) {
            ActionExecutionResult result = ActionExecutionResult.failure(request, ActionExecutionStatus.FAILED,
                    "ACTION_EXECUTOR_ERROR", ex.getMessage(), Instant.now());
            record(command, result, source);
            return result;
        }
        stage.whenComplete((result, ex) -> {
            ActionExecutionResult finalResult = result;
            if (ex != null) {
                finalResult = ActionExecutionResult.failure(request, ActionExecutionStatus.FAILED,
                        "ACTION_EXECUTOR_ERROR", ex.getMessage(), request.createdAt());
            }
            record(command, finalResult, source);
        });
        if (stage.toCompletableFuture().isDone()) {
            return stage.toCompletableFuture().getNow(ActionExecutionResult.accepted(request, "accepted"));
        }
        return ActionExecutionResult.accepted(request, "动作已提交");
    }

    private void record(ActionCommand command, ActionExecutionResult result, ActionSource source) {
        if (!result.success()) {
            log.info("动作执行失败: target={}, actionType={}, actionCode={}, requestId={}, errorCode={}, message={}",
                    result.target(), result.actionType(), result.actionCode(), result.requestId(), result.errorCode(), result.message());
        }
        recorder.recordActionExecution(result);
        recorder.recordAction(command, result.accepted() ? ActionRecordResult.ACCEPTED : ActionRecordResult.REJECTED,
                result.accepted() ? null : result.reason(), null, source);
    }
}
