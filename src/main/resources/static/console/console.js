(function () {
  const refreshMs = 1000;
  const $ = (id) => document.getElementById(id);
  let previousRuntime = null;

  async function api(path, options) {
    const res = await fetch(path, options);
    const text = await res.text();
    const body = text ? JSON.parse(text) : {};
    if (!res.ok) throw new Error(body.error || body.message || res.statusText);
    return body;
  }

  function kv(target, pairs) {
    target.innerHTML = pairs.map(([k, v, cls]) => `<div><span class="muted">${k}</span><span class="${cls || ""}">${v ?? ""}</span></div>`).join("");
  }

  function rows(target, items, columns, actions) {
    if (!items || items.length === 0) {
      target.innerHTML = '<p class="muted">暂无数据</p>';
      return;
    }
    target.innerHTML = items.map((item) => {
      const cells = columns.map(([label, key]) => `<span><span class="muted">${label}</span> ${item[key] ?? ""}</span>`).join("");
      return `<div class="table-row"><span>${cells}</span><span></span><span>${actions ? actions(item) : ""}</span></div>`;
    }).join("");
  }

  async function refresh() {
    try {
      const runtime = await api("/local-api/runtime");
      const elapsedSeconds = previousRuntime ? Math.max(1, (Date.now() - previousRuntime.readAt) / 1000) : refreshMs / 1000;
      const micBytesPerSecond = previousRuntime ? Math.max(0, Math.round((runtime.microphoneBytesRead - previousRuntime.microphoneBytesRead) / elapsedSeconds)) : 0;
      const asrBytesPerSecond = previousRuntime ? Math.max(0, Math.round((runtime.asrBytesSent - previousRuntime.asrBytesSent) / elapsedSeconds)) : 0;
      previousRuntime = { readAt: Date.now(), microphoneBytesRead: runtime.microphoneBytesRead || 0, asrBytesSent: runtime.asrBytesSent || 0 };

      kv($("overview"), [
        ["服务", runtime.serviceStatus, "ok"],
        ["版本", runtime.applicationVersion],
        ["地址", `${runtime.serverAddress}:${runtime.serverPort}`],
        ["运行时长", Math.floor(runtime.uptimeMs / 1000) + "s"],
        ["命令数", runtime.commandCount],
        ["素材数", runtime.assetCount]
      ]);

      kv($("asrMic"), [
        ["当前 Provider", providerLabel(runtime.asrProvider), runtime.asrProvider === "TENCENT" ? "ok" : "warn"],
        ["启动方式", runtime.asrManualControlEnabled ? "手动" : "自动"],
        ["是否希望连接", runtime.asrConnectionDesired ? "是" : "否", runtime.asrConnectionDesired ? "ok" : "warn"],
        ["ASR 状态", runtime.asrStatus],
        ["最近连接时间", runtime.lastAsrConnectedAt || ""],
        ["最近断开时间", runtime.lastAsrDisconnectedAt || ""],
        ["最近错误", runtime.asrLastError || "无"],
        ["Recognizer", runtime.asrCurrentRecognizerId || ""],
        ["最近临时结果", runtime.asrLastPartialText || ""],
        ["最近最终结果", runtime.asrLastFinalText || ""],
        ["最近结果时间", runtime.asrLastResultAt || ""],
        ["麦克风状态", runtime.microphoneStatus],
        ["采集线程", runtime.microphoneCaptureThreadAlive ? "运行中" : "未运行", runtime.microphoneCaptureThreadAlive ? "ok" : "warn"],
        ["当前设备", runtime.microphoneDeviceName || "自动选择中"],
        ["采集格式", runtime.microphoneActualFormat || ""],
        ["读取字节/秒", micBytesPerSecond],
        ["ASR 字节/秒", asrBytesPerSecond],
        ["microphoneBytesRead", runtime.microphoneBytesRead || 0],
        ["convertedPcmBytes", runtime.convertedPcmBytes || 0],
        ["asrBytesSent", runtime.asrBytesSent || 0],
        ["asrPacketsSent", runtime.asrPacketsSent || 0],
        ["audioLevel", Number(runtime.audioLevel || 0).toFixed(4)],
        ["原始音量", bar(runtime.rawAudioLevel), runtime.rawSilenceDetected ? "warn" : "ok"],
        ["转换后音量", bar(runtime.convertedAudioLevel), runtime.convertedSilenceDetected ? "warn" : "ok"],
        ["最近音频读取", runtime.lastAudioReadAt || ""],
        ["最近 ASR 发送", runtime.lastAsrWriteAt || ""]
      ]);

      // ASR 按钮状态管理
      updateAsrButtons(runtime.asrStatus, runtime.asrConnectionDesired);

      kv($("localAsr"), [
        ["状态", runtime.asrProvider === "SHERPA_ONNX" ? runtime.localAsrStatus : "DISABLED"],
        ["说明", runtime.asrProvider === "SHERPA_ONNX" ? "本地离线识别当前启用。" : "本地离线识别已安装，但当前未启用。"],
        ["模型状态", runtime.localAsrModelStatus],
        ["模型", runtime.localAsrModelName],
        ["队列", `${runtime.localAsrQueueSize || 0}/${runtime.localAsrQueueCapacity || 0}`],
        ["最近错误", runtime.localAsrLastError || ""]
      ]);

      kv($("renderer"), [
        ["连接数", runtime.rendererConnectionCount, runtime.rendererConnectionCount > 0 ? "ok" : "warn"],
        ["提示", runtime.rendererConnectionCount > 0 ? "Renderer 已连接" : "Renderer 未连接"]
      ]);

      kv($("adapters"), [
        ["已注册 target", (runtime.registeredActionTargets || []).join(", ")],
        ["MEDIA 状态", runtime.mediaExecutorStatus],
        ["MEDIA Renderer 连接数", runtime.rendererConnectionCount],
        ["MEDIA 最近动作", runtime.mediaLastActionAt || ""],
        ["VTS 启用", runtime.vTubeStudioEnabled ? "是" : "否"],
        ["VTS WebSocket", runtime.vTubeStudioConnectionStatus],
        ["VTS 已授权", runtime.vTubeStudioAuthenticated ? "是" : "否"],
        ["VTS 当前模型", runtime.vTubeStudioModelName || ""],
        ["VTS Hotkey 数量", runtime.vTubeStudioHotkeyCount || 0],
        ["VTS 最近触发", runtime.vTubeStudioLastActionAt || ""],
        ["VTS 最近错误", runtime.vTubeStudioLastError || ""],
        ["最近执行 target", runtime.lastActionTarget || ""],
        ["最近执行类型", runtime.lastActionType || ""],
        ["最近执行状态", runtime.lastActionExecutionStatus || ""],
        ["最近执行耗时", runtime.lastActionExecutionLatencyMs ?? ""],
        ["最近执行错误", runtime.lastActionExecutionError || ""]
      ]);

      const action = runtime.currentAction || {};
      kv($("currentAction"), [
        ["动作", action.actionCode || "无"],
        ["类型", action.actionType || ""],
        ["结果", action.result || ""],
        ["来源", action.source || ""]
      ]);
      $("lastRefresh").textContent = "最后刷新 " + new Date().toLocaleTimeString();
    } catch (err) {
      $("lastRefresh").textContent = "刷新失败: " + err.message;
    }
    refreshLists();
    refreshAudioTest();
  }

  function providerLabel(provider) {
    if (provider === "TENCENT") return "腾讯云实时 ASR";
    if (provider === "SHERPA_ONNX") return "Sherpa-ONNX 本地离线 ASR";
    if (provider === "FUNASR") return "FunASR";
    return provider || "未知";
  }

  function bar(value) {
    const level = Number(value || 0);
    const width = Math.min(100, Math.round(level * 400));
    return `<span class="meter"><i style="width:${width}%"></i></span> ${level.toFixed(4)}`;
  }

  function updateAsrButtons(asrStatus, connectionDesired) {
    var connectBtn = $("asrConnectBtn");
    var disconnectBtn = $("asrDisconnectBtn");
    if (!connectBtn || !disconnectBtn) return;

    // 恢复按钮文案
    connectBtn.textContent = "连接 ASR";
    disconnectBtn.textContent = "断开 ASR";

    var isConnecting = asrStatus === "CONNECTING";
    var isConnected = asrStatus === "CONNECTED";
    var isDisconnecting = asrStatus === "DISCONNECTING";

    if (isDisconnecting) {
      connectBtn.disabled = true;
      disconnectBtn.disabled = true;
    } else if (isConnected || isConnecting) {
      connectBtn.disabled = true;
      disconnectBtn.disabled = false;
    } else {
      // DISCONNECTED / STOPPED / ERROR / DISABLED 等
      connectBtn.disabled = false;
      disconnectBtn.disabled = true;
    }
  }

  async function refreshAudioTest() {
    try {
      const test = await api("/local-api/audio/test");
      $("audioTestHint").textContent = test.status === "RUNNING"
        ? `请对着麦克风说话，剩余 ${test.remainingSeconds}s`
        : (test.message || "");
    } catch (err) {
      $("audioTestHint").textContent = "";
    }
  }

  async function refreshLists() {
    try {
      rows($("recentActions"), await api("/local-api/runtime/actions/recent"), [["动作", "actionCode"], ["类型", "actionType"], ["结果", "result"], ["来源", "source"]]);
      rows($("recentErrors"), await api("/local-api/runtime/errors/recent"), [["组件", "component"], ["错误", "errorCode"], ["消息", "message"]]);
      rows($("assets"), await api("/local-api/assets"), [["文件", "fileName"], ["类型", "assetType"], ["大小", "size"], ["使用中", "inUse"]],
        (item) => `<a href="${item.assetUrl}" target="_blank"><button class="secondary">预览</button></a> <button class="danger" data-delete="${item.fileName}">删除</button>`);
      const commandBody = await api("/local-api/commands");
      rows($("commands"), commandBody.commands, [["动作", "code"], ["名称", "name"], ["目标", "target"], ["类型", "actionType"], ["优先级", "priority"]],
        (item) => `<button data-test="${item.code}">测试</button>`);
      rows($("hotkeys"), await api("/local-api/integrations/vtube-studio/hotkeys"), [["名称", "name"], ["ID", "hotkeyId"], ["类型", "type"], ["描述", "description"], ["文件", "file"], ["重名", "duplicateName"]],
        (item) => `<button data-vts-hotkey="${item.hotkeyId}">测试</button>`);
    } catch (err) {
      $("quickHint").textContent = "列表刷新失败: " + err.message;
    }
  }

  document.addEventListener("click", async (event) => {
    const testCode = event.target.getAttribute("data-test");
    const deleteName = event.target.getAttribute("data-delete");
    const hotkeyId = event.target.getAttribute("data-vts-hotkey");
    try {
      if (testCode) {
        const result = await api("/local-api/actions/test", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ actionCode: testCode }) });
        $("quickHint").textContent = result.success ? "测试动作已发送" : "测试被拒绝: " + result.reason;
        refresh();
      }
      if (deleteName) {
        await api("/local-api/assets/" + encodeURIComponent(deleteName), { method: "DELETE" });
        refresh();
      }
      if (hotkeyId) {
        const result = await api("/local-api/integrations/vtube-studio/hotkeys/test", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ hotkeyId }) });
        $("quickHint").textContent = result.success ? "Hotkey 测试已提交" : "Hotkey 测试失败: " + result.errorCode;
        refresh();
      }
    } catch (err) {
      $("quickHint").textContent = err.message;
    }
  });

  $("refreshBtn").onclick = refresh;
  $("clearBtn").onclick = async () => { await api("/local-api/actions/clear", { method: "POST" }); refresh(); };
  $("reloadCommandsBtn").onclick = async () => { await api("/local-api/commands/reload", { method: "POST" }); refresh(); };
  $("audioTestBtn").onclick = async () => { await api("/local-api/audio/test", { method: "POST" }); refreshAudioTest(); };
  $("vtsConnectBtn").onclick = async () => { await api("/local-api/integrations/vtube-studio/connect", { method: "POST" }); refresh(); };
  $("vtsDisconnectBtn").onclick = async () => { await api("/local-api/integrations/vtube-studio/disconnect", { method: "POST" }); refresh(); };
  $("vtsAuthorizeBtn").onclick = async () => { await api("/local-api/integrations/vtube-studio/authorize", { method: "POST" }); refresh(); };
  $("vtsRefreshBtn").onclick = async () => { await api("/local-api/integrations/vtube-studio/refresh", { method: "POST" }); refresh(); };
  $("asrConnectBtn").onclick = async () => {
    $("asrConnectBtn").disabled = true;
    $("asrConnectBtn").textContent = "正在连接...";
    try {
      await api("/local-api/asr/connect", { method: "POST" });
    } catch (err) {
      $("audioTestHint").textContent = "ASR 连接失败: " + err.message;
    }
    refresh();
  };
  $("asrDisconnectBtn").onclick = async () => {
    $("asrDisconnectBtn").disabled = true;
    $("asrDisconnectBtn").textContent = "正在断开...";
    try {
      await api("/local-api/asr/disconnect", { method: "POST" });
    } catch (err) {
      $("audioTestHint").textContent = "ASR 断开失败: " + err.message;
    }
    refresh();
  };
  $("uploadBtn").onclick = async () => {
    const file = $("assetFile").files[0];
    if (!file) return;
    const form = new FormData();
    form.append("file", file);
    await api("/local-api/assets/upload", { method: "POST", body: form });
    $("assetFile").value = "";
    refresh();
  };
  refresh();
  setInterval(refresh, refreshMs);
})();
