# 动作适配器架构

V0.5-A 将动作执行层拆为可插拔适配器：

```text
CommandMatcher
-> ActionDispatcher
-> ActionExecutorRegistry
   -> MEDIA / RendererActionExecutor
   -> VTUBE_STUDIO / VTubeStudioActionExecutor
```

## 当前实现

- `MEDIA`
  - `SHOW_IMAGE`
  - `PLAY_GIF`
  - `PLAY_WEBM`
  - `HIDE`
  - `CLEAR`
- `VTUBE_STUDIO`
  - `TRIGGER_HOTKEY`

## 扩展规则

- 新增目标通过新增 `ActionExecutor` Bean 注册。
- `ActionDispatcher` 只依赖 `ActionExecutorRegistry`。
- 不修改 ASR。
- 不修改 `CommandMatcher`。
- 不在 `ActionDispatcher` 中添加 target switch 或 if/else 分发。
- 未实现目标返回 `UNSUPPORTED_TARGET`。
- 不创建空实现或假成功 Executor。

## 后续计划

- WARUDO
- OBS
- HTTP_API
- WEBHOOK
- WEBSOCKET
- COMPOSITE
- 其他虚拟形象或直播软件

`COMPOSITE` 会在后续阶段单独设计，不在 V0.5-A 中实现。

## 命令配置

旧命令没有 `target` 时等价于：

```json
{
  "target": "MEDIA"
}
```

第三方适配器使用通用 `parameters` 对象传递参数，由对应 Executor 自行解析和校验。
