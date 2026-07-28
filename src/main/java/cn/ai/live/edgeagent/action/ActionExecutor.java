package cn.ai.live.edgeagent.action;

import java.util.concurrent.CompletionStage;

public interface ActionExecutor {
    String targetId();

    boolean supports(ActionExecutionRequest request);

    CompletionStage<ActionExecutionResult> execute(ActionExecutionRequest request, ActionExecutionContext context);

    ActionExecutorStatus status();

    default ActionExecutionResult execute(ActionCommand command) {
        return execute(ActionExecutionRequest.from(command), ActionExecutionContext.of(cn.ai.live.edgeagent.runtime.ActionSource.SYSTEM))
                .toCompletableFuture()
                .join();
    }
}
