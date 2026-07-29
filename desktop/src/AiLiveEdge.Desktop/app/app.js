(() => {
  "use strict";

  const $ = (id) => document.getElementById(id);
  const state = {
    runtime: null,
    commands: [],
    assets: [],
    recentActions: [],
    recognitionCount: 0,
    lastFinalText: "",
    assetFilter: "ALL",
    bindingAsset: null,
    connected: false,
    connection: null,
    agentRuntime: null,
    liveOutput: null,
    lastAsrError: "",
    auth: null,
    appSettings: null,
    pollersStarted: false,
    pollers: []
  };
  const bridgeRequests = new Map();

  const pageMeta = {
    home: ["OVERVIEW", "首页"],
    assistant: ["AI COPILOT", "AI 伴播"],
    actions: ["ACTION LIBRARY", "动作库"],
    assets: ["ASSET CENTER", "素材中心"],
    "live-output": ["LIVE OUTPUT", "直播输出"],
    settings: ["PREFERENCES", "设置"]
  };

  const escapeHtml = (value) => String(value ?? "")
    .replaceAll("&", "&amp;").replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;").replaceAll('"', "&quot;")
    .replaceAll("'", "&#039;");

  function agent(operation, payload = {}) {
    if (!window.chrome?.webview) {
      return Promise.reject(new Error("Desktop Agent 连接桥不可用。"));
    }
    const id = `${Date.now()}-${Math.random().toString(16).slice(2)}`;
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        bridgeRequests.delete(id);
        reject(new Error("Agent 请求超时。"));
      }, 20000);
      bridgeRequests.set(id, { resolve, reject, timer });
      window.chrome.webview.postMessage({ id, operation, payload });
    });
  }

  window.chrome?.webview?.addEventListener("message", (event) => {
    const message = event.data;
    if (message.type === "authStateChanged") {
      applyAuthState(message.data || {});
      return;
    }
    const request = bridgeRequests.get(message.id);
    if (!request) return;
    clearTimeout(request.timer);
    bridgeRequests.delete(message.id);
    if (message.success) request.resolve(message.data);
    else request.reject(new Error(message.error || "Agent 请求失败。"));
  });

  function navigate(page) {
    document.querySelectorAll(".page").forEach((node) => node.classList.remove("active"));
    document.querySelectorAll(".nav-item").forEach((node) => node.classList.toggle("active", node.dataset.page === page));
    $(`page-${page}`).classList.add("active");
    $("pageEyebrow").textContent = pageMeta[page][0];
    $("pageTitle").textContent = pageMeta[page][1];
    document.querySelector(".workspace").scrollTo({ top: 0, behavior: "smooth" });
  }

  function toast(title, message = "", error = false) {
    $("toastTitle").textContent = title;
    $("toastMessage").textContent = message;
    $("toastIcon").textContent = error ? "!" : "✓";
    $("toastIcon").style.color = error ? "var(--red)" : "var(--green)";
    $("toast").classList.add("visible");
    clearTimeout(toast.timer);
    toast.timer = setTimeout(() => $("toast").classList.remove("visible"), 2800);
  }

  function showLogin(message = "") {
    $("loginScreen").classList.remove("hidden");
    document.querySelector(".app-shell").classList.add("auth-locked");
    if (message) {
      $("loginAlert").textContent = message;
      $("loginAlert").classList.add("visible");
    }
  }

  function showApp() {
    $("loginScreen").classList.add("hidden");
    document.querySelector(".app-shell").classList.remove("auth-locked");
  }

  function applyAuthState(auth) {
    state.auth = auth;
    if (!auth.authenticated) {
      stopProtectedPolling();
      showLogin(auth.message || "");
      $("loginNetwork").textContent = auth.message || "等待登录";
      if ($("cloudServiceStatus")) $("cloudServiceStatus").textContent = auth.message || "未登录";
      return;
    }

    showApp();
    const user = auth.user || {};
    const tenant = auth.tenant || {};
    const license = auth.license || {};
    const cloudLabel = {
      CONNECTED: "云端在线",
      CONNECTING: "云端连接中",
      SESSION_EXPIRED: "会话失效",
      ERROR: "网络异常",
      DISCONNECTED: "云端离线"
    }[auth.cloudStatus] || "云端状态未知";
    $("identityName").textContent = user.nickname || user.username || "AI 伴播用户";
    $("authStatus").textContent = `${tenant.name || "当前租户"} · ${license.statusLabel || "授权状态未知"} · ${cloudLabel}`;
    if ($("cloudServiceStatus")) $("cloudServiceStatus").textContent = `${cloudLabel} · ${license.statusLabel || "授权状态未知"}`;
    $("loginPassword").value = "";
    $("loginAlert").classList.remove("visible");
    startProtectedPolling();
  }

  async function initializeAuth() {
    document.querySelector(".app-shell").classList.add("auth-locked");
    try {
      const settings = await agent("getCloudApiSettings");
      updateCloudLoginStatus(settings);
      await initializeAppSettings();
      const auth = await agent("getAuthState");
      applyAuthState(auth);
      if (auth.authenticated) {
        await initializeConnection();
      }
    } catch (error) {
      showLogin(error.message);
      $("loginNetwork").textContent = error.message?.includes("未配置")
        ? "云服务未配置"
        : "云服务暂时无法连接，请检查网络后重试";
    }
  }

  async function submitLogin() {
    const username = $("loginUsername").value.trim();
    const password = $("loginPassword").value;
    if (!username) return showLogin("账号不能为空。");
    if (!password) return showLogin("密码不能为空。");

    $("loginSubmit").disabled = true;
    $("loginSubmit").textContent = "登录中...";
    $("loginNetwork").textContent = "正在连接云端服务";
    $("loginAlert").classList.remove("visible");
    try {
      const auth = await agent("login", {
        username,
        password,
        rememberLogin: $("rememberLogin").checked
      });
      applyAuthState(auth);
      await initializeConnection();
      toast("登录成功", auth.tenant?.name || "");
    } catch (error) {
      showLogin(error.message || "登录失败，请稍后重试。");
      $("loginNetwork").textContent = error.message?.includes("超时")
        ? "连接云端服务超时，请检查网络或服务器地址。"
        : "登录失败";
    } finally {
      $("loginSubmit").disabled = false;
      $("loginSubmit").textContent = "登录";
    }
  }

  async function initializeAppSettings() {
    const settings = await agent("getAppSettings");
    applyAppSettings(settings);
    await Promise.allSettled([refreshAppInfo(), refreshRecentErrors()]);
  }

  function applyAppSettings(settings) {
    state.appSettings = settings;
    if ($("cloudApiBaseUrlInput")) $("cloudApiBaseUrlInput").value = settings.cloudApiBaseUrl || "";
    if ($("serverEditingPanel")) $("serverEditingPanel").style.display = settings.allowServerEditing ? "" : "none";
    if ($("settingStartWithWindows")) $("settingStartWithWindows").checked = Boolean(settings.startWithWindows);
    if ($("settingStartMinimized")) $("settingStartMinimized").checked = Boolean(settings.startMinimized);
    if ($("settingCloseBehavior")) $("settingCloseBehavior").value = settings.closeBehavior || "MINIMIZE_TO_TRAY";
    if ($("settingShowTrayNotification")) $("settingShowTrayNotification").checked = Boolean(settings.showTrayNotification);
    if ($("settingRendererAutoStart")) $("settingRendererAutoStart").checked = Boolean(settings.rendererAutoStart);
    if ($("settingLogRetentionDays")) $("settingLogRetentionDays").value = settings.logRetentionDays || 14;
  }

  async function saveAppSettings(patch) {
    try {
      const settings = await agent("updateAppSettings", patch);
      applyAppSettings(settings);
      toast("设置已保存");
    } catch (error) {
      toast("设置保存失败", error.message, true);
    }
  }

  async function saveCloudApiBaseUrl() {
    try {
      const settings = await agent("saveCloudApiSettings", { baseUrl: $("cloudApiBaseUrlInput").value.trim() });
      updateCloudLoginStatus(settings);
      $("cloudApiBaseUrlInput").value = settings.baseUrl || "";
      await initializeAppSettings();
      toast("云端地址已更新", settings.baseUrl || "");
    } catch (error) {
      toast("云端地址保存失败", error.message, true);
    }
  }

  async function refreshAppInfo() {
    try {
      const info = await agent("getAppInfo");
      $("appInfoName").textContent = info.productName || "AI Live Edge";
      $("appInfoVersion").textContent = `版本 ${info.version || "—"}`;
      $("appInfoInstall").textContent = info.installDirectory || "—";
      $("appInfoData").textContent = info.dataDirectory || "—";
      $("logsDirectoryInfo").textContent = info.logsDirectory || "—";
      $("appInfoDevice").textContent = info.deviceCode || "—";
      $("appInfoAccount").textContent = [info.account, info.tenant].filter(Boolean).join(" · ") || "未登录";
      $("desktopVersion").textContent = info.displayVersion || "V0.2.4";
    } catch {
      // App info is supportive; keep the settings page usable if it is unavailable.
    }
  }

  async function refreshRecentErrors() {
    try {
      const result = await agent("getRecentErrors");
      const errors = result.errors || [];
      $("recentErrorsList").textContent = errors.length ? errors.join("  |  ") : "暂无错误摘要";
    } catch {
      $("recentErrorsList").textContent = "错误摘要读取失败";
    }
  }

  function updateCloudLoginStatus(settings) {
    const status = !settings?.baseUrl
      ? "云服务未配置"
      : settings.deploymentMode === "SAAS"
        ? "云服务已就绪"
        : "云服务已配置";
    $("loginNetwork").textContent = status;
    if ($("cloudServiceStatus")) $("cloudServiceStatus").textContent = status;
  }

  function startProtectedPolling() {
    if (state.pollersStarted) return;
    state.pollersStarted = true;
    state.pollers = [
      setInterval(refreshAuthState, 10000),
      setInterval(refreshRuntime, 2000),
      setInterval(refreshAsrStatus, 1000),
      setInterval(refreshRendererStatus, 2000),
      setInterval(refreshLiveOutputStatus, 2000),
      setInterval(refreshCollections, 12000)
    ];
  }

  function stopProtectedPolling() {
    state.pollers.forEach((id) => clearInterval(id));
    state.pollers = [];
    state.pollersStarted = false;
  }

  async function refreshAuthState() {
    try {
      applyAuthState(await agent("getAuthState"));
    } catch {
      // Keep the existing auth banner until the desktop bridge reports a session change.
    }
  }

  function friendlyAsr(status) {
    return {
      CONNECTED: "监听中", RUNNING: "监听中", CONNECTING: "启动中", DISCONNECTING: "正在停止",
      DISCONNECTED: "未连接", STOPPED: "已停止", ERROR: "需要检查", DISABLED: "未启用"
    }[status] || "等待启动";
  }

  function isAsrListening(status) {
    return status === "CONNECTED" || status === "RUNNING";
  }

  function renderAsrPhase(status, errorMessage = "") {
    const listening = isAsrListening(status);
    const connecting = status === "CONNECTING";
    $("asrStatusPill").textContent = friendlyAsr(status);
    $("assistantAsr").textContent = friendlyAsr(status);
    $("waveOrb").classList.toggle("active", listening);
    $("asrState").className = `card-state ${listening ? "good" : connecting ? "busy" : "bad"}`;
    $("listeningTitle").textContent = listening
      ? "正在聆听直播声音"
      : connecting
        ? "正在启动语音识别"
        : status === "ERROR"
          ? "语音识别连接失败"
          : "AI 伴播待命中";
    const visibleError = errorMessage || (status === "ERROR" ? state.lastAsrError : "");
    $("listeningHint").textContent = visibleError
      || (listening
        ? "说出已配置的指令，AI 会自动识别并响应。"
        : connecting
          ? "正在连接麦克风和语音服务，请稍候。"
          : "点击“开始伴播”，让 AI 开始聆听直播指令。");
    $("liveNavDot").classList.toggle("active", listening);
  }

  function providerName(provider) {
    return {
      TENCENT: "云端实时语音", SHERPA_ONNX: "本地离线语音", FUNASR: "FunASR 语音"
    }[provider] || provider || "自动选择";
  }

  function micConnected(runtime) {
    return runtime.microphoneCaptureThreadAlive ||
      ["OPEN", "CAPTURING", "RUNNING", "CONNECTED"].includes(runtime.microphoneStatus);
  }

  function formatDuration(milliseconds) {
    const total = Math.max(0, Math.floor(Number(milliseconds || 0) / 1000));
    const hours = String(Math.floor(total / 3600)).padStart(2, "0");
    const minutes = String(Math.floor((total % 3600) / 60)).padStart(2, "0");
    const seconds = String(total % 60).padStart(2, "0");
    return `${hours}:${minutes}:${seconds}`;
  }

  function updateRuntime(runtime) {
    state.runtime = runtime;
    state.connected = runtime.serviceStatus === "UP";
    state.lastAsrError = runtime.asrLastError || "";
    if (isAsrListening(runtime.asrStatus)) state.lastAsrError = "";
    const asrConnected = isAsrListening(runtime.asrStatus);
    const micReady = micConnected(runtime);
    const currentAction = runtime.currentAction || {};
    const finalText = runtime.asrLastFinalText || "";

    if (finalText && finalText !== state.lastFinalText) {
      if (state.lastFinalText) state.recognitionCount += 1;
      else state.recognitionCount = 1;
      state.lastFinalText = finalText;
    }

    $("offlineBanner").classList.toggle("visible", !state.connected);
    const isCloud = state.connection?.mode === "CLOUD";
    $("sidebarStatus").textContent = state.connected
      ? (isCloud ? "云端服务已连接" : "本地 Agent 已连接")
      : "连接中";
    $("appVersion").textContent = `Agent ${runtime.applicationVersion || "—"}`;
    $("settingsVersion").textContent = `版本 ${runtime.applicationVersion || "—"}`;
    $("heroStatus").textContent = state.connected
      ? (isCloud ? "云端服务已连接" : "本地 Agent 已连接")
      : (isCloud ? "正在连接云端服务" : "正在连接本地 Agent");
    $("aiStatus").textContent = asrConnected ? "正在聆听" : "等待指令";
    $("aiSubstatus").textContent = finalText ? `最近：${finalText}` : "随时准备响应";
    $("micStatus").textContent = micReady ? "已连接" : "未连接";
    $("micDevice").textContent = runtime.microphoneDeviceName || "自动选择设备";
    $("micState").className = `card-state ${micReady ? "good" : "bad"}`;
    $("engineDetail").textContent = currentAction.actionCode ? `正在执行 ${currentAction.actionCode}` : "等待直播动作";
    $("uptime").textContent = formatDuration(runtime.uptimeMs);
    $("recognitionCount").textContent = String(state.recognitionCount);
    $("actionCount").textContent = String(state.recentActions.length);
    $("lastUpdated").textContent = `刚刚同步 · ${new Date().toLocaleTimeString("zh-CN", { hour12: false })}`;

    $("asrProvider").textContent = providerName(runtime.asrProvider);
    $("assistantMic").textContent = runtime.microphoneDeviceName || "自动选择";
    $("assistantProvider").textContent = providerName(runtime.asrProvider);
    renderAsrPhase(runtime.asrStatus, runtime.asrLastError || "");
    $("lastTranscript").textContent = finalText || runtime.asrLastPartialText || "尚未收到语音内容";
    $("lastResponse").textContent = currentAction.actionCode || runtime.lastActionType || "等待指令";
    $("settingsMic").textContent = runtime.microphoneDeviceName || "跟随系统默认设备";

    const startDisabled = asrConnected || runtime.asrStatus === "CONNECTING" || state.auth?.canStartBroadcast === false;
    ["heroStart", "startBroadcast", "assistantStart"].forEach((id) => $(id).disabled = startDisabled);
    ["stopBroadcast", "assistantStop"].forEach((id) => $(id).disabled = !asrConnected && runtime.asrStatus !== "CONNECTING");
  }

  async function refreshRuntime() {
    try {
      updateRuntime(await agent("getRuntimeStatus"));
    } catch (error) {
      state.connected = false;
      $("offlineBanner").classList.add("visible");
      $("sidebarStatus").textContent = state.connection?.mode === "CLOUD" ? "云端服务不可用" : "本地 Agent 不可用";
      $("offlineBanner").querySelector("strong").textContent =
        state.connection?.mode === "CLOUD" ? "正在连接云端服务" : "正在连接本地 Agent";
    }
  }

  async function refreshAsrStatus() {
    try {
      const status = await agent("getAsrStatus");
      renderAsrPhase(status.asrStatus);
      if (status.microphoneStatus) {
        $("assistantMic").dataset.status = status.microphoneStatus;
      }
    } catch (error) {
      renderAsrPhase("ERROR", error.message);
    }
  }

  function updateRendererStatus(status) {
    const connected = status.state === "CONNECTED";
    const connecting = status.state === "CONNECTING";
    $("engineStatus").textContent = connected ? "Renderer 已连接" : connecting ? "正在连接 Renderer" : "Renderer 未连接";
    $("engineState").className = `card-state ${connected ? "good" : connecting ? "busy" : "bad"}`;
    $("rendererBadge").className = `renderer-badge ${connected ? "connected" : connecting ? "connecting" : "failed"}`;
    $("rendererBadge").textContent = connected ? "已连接" : connecting ? "正在连接" : status.state === "FAILED" ? "连接失败" : "未连接";
    $("rendererConnection").textContent = connected ? "已连接" : connecting ? "连接中" : "未连接";
    if ($("rendererSettingsStatus")) {
      $("rendererSettingsStatus").textContent = connected ? "运行中" : connecting ? "启动中" : status.state === "FAILED" ? "启动失败" : "未启动";
    }
    $("rendererWebSocket").textContent = connected ? `正常 · ${status.connectionCount || 1} 个连接` : "未建立";
    $("rendererLastAction").textContent = status.lastAction || "—";
    $("rendererUpdated").textContent = status.lastUpdatedAt
      ? new Date(status.lastUpdatedAt).toLocaleTimeString("zh-CN", { hour12: false })
      : "—";
    $("rendererWarning").classList.toggle("error", !connected && !connecting);
    $("rendererWarning").textContent = connected
      ? "Desktop 正在自动维护 Renderer，OBS Browser Source 地址保持不变。"
      : connecting
        ? "Desktop 正在自动准备 Renderer，无需手动打开浏览器。"
        : "动作服务未连接，请检查 Renderer 状态。";
  }

  async function refreshRendererStatus() {
    try {
      updateRendererStatus(await agent("getRendererStatus"));
    } catch (error) {
      updateRendererStatus({
        state: "FAILED",
        connectionCount: 0,
        lastAction: null,
        lastUpdatedAt: new Date().toISOString(),
        errorMessage: error.message
      });
    }
  }

  function actionIcon(command, index) {
    const name = `${command.name || ""}${command.code || ""}`.toLowerCase();
    if (name.includes("wave") || name.includes("挥手") || name.includes("欢迎")) return "♬";
    if (name.includes("like") || name.includes("点赞")) return "♥";
    if (name.includes("dance") || name.includes("跳舞")) return "♫";
    return ["✦", "◉", "◇", "♬", "★"][index % 5];
  }

  function renderActions() {
    $("actionSummary").textContent = `共 ${state.commands.length} 个动作`;
    if (!state.commands.length) {
      $("actionGrid").innerHTML = '<div class="empty-state"><i>✦</i><strong>还没有可用动作</strong><small>配置动作后，它们会自动出现在这里。</small></div>';
      return;
    }
    $("actionGrid").innerHTML = state.commands.map((command, index) => `
      <article class="action-card">
        <div class="action-visual"><span>${actionIcon(command, index)}</span>${command.enabled !== false ? '<b class="enabled-badge">已启用</b>' : ""}</div>
        <div class="action-body">
          <h3>${escapeHtml(command.name || command.code || "未命名动作")}</h3>
          <p>${escapeHtml((command.keywords || []).slice(0, 3).join(" · ") || "直播快捷动作")}</p>
          <button class="card-button" data-test-action="${escapeHtml(command.code)}">▶ 测试动作</button>
        </div>
      </article>`).join("");
  }

  function assetCategory(asset) {
    const type = String(asset.assetType || "").toUpperCase();
    const name = String(asset.fileName || "").toLowerCase();
    if (type.includes("GIF") || name.endsWith(".gif")) return "GIF";
    if (type.includes("WEBM") || name.endsWith(".webm")) return "WEBM";
    if (type.includes("SCENE") || name.endsWith(".json")) return "SCENE";
    return "IMAGE";
  }

  function resolveAssetUrl(value) {
    const url = String(value || "");
    if (/^(https?:|file:|data:|blob:)/i.test(url)) return url;
    const baseAddress = state.connection?.mode === "CLOUD"
      ? state.connection?.cloudAddress
      : state.connection?.localAddress;
    if (!baseAddress) return url;
    try { return new URL(url, baseAddress).href; }
    catch { return url; }
  }

  function assetPreview(asset) {
    const category = assetCategory(asset);
    const url = escapeHtml(resolveAssetUrl(asset.assetUrl));
    if (category === "WEBM") return `<video src="${url}" muted preload="metadata"></video>`;
    if (category === "IMAGE" || category === "GIF") return `<img src="${url}" alt="">`;
    return '<span class="file-art">▧</span>';
  }

  function renderAssets() {
    const visible = state.assets.filter((asset) => state.assetFilter === "ALL" || assetCategory(asset) === state.assetFilter);
    $("assetSummary").textContent = `共 ${state.assets.length} 项素材`;
    if (!visible.length) {
      $("assetGrid").innerHTML = '<div class="empty-state"><i>▧</i><strong>这里还是空的</strong><small>添加图片、GIF 或 WebM，开始搭建你的直播素材库。</small></div>';
      return;
    }
    const bindings = readBindings();
    $("assetGrid").innerHTML = visible.map((asset) => {
      const name = escapeHtml(asset.fileName);
      const previewUrl = escapeHtml(resolveAssetUrl(asset.assetUrl));
      const category = assetCategory(asset);
      const binding = bindings[asset.fileName];
      return `<article class="asset-card">
        <div class="asset-preview">${assetPreview(asset)}<span class="asset-type">${category}</span></div>
        <div class="asset-body">
          <h3>${name}</h3><p>${binding ? `已绑定：${escapeHtml(binding.name)}` : `${formatSize(asset.size)} · 尚未绑定动作`}</p>
          <div class="asset-actions"><button class="card-button" data-preview="${previewUrl}">预览</button><button class="card-button" data-bind="${name}">绑定动作</button></div>
        </div>
      </article>`;
    }).join("");
  }

  function formatSize(bytes) {
    const size = Number(bytes || 0);
    if (size < 1024) return `${size} B`;
    if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)} KB`;
    return `${(size / 1024 / 1024).toFixed(1)} MB`;
  }

  async function refreshCollections() {
    try {
      const [commandsBody, assets, recentActions] = await Promise.all([
        agent("getCommands"), agent("getAssets"), agent("getActionStatus")
      ]);
      state.commands = commandsBody.commands || [];
      state.assets = assets || [];
      state.recentActions = recentActions || [];
      renderActions();
      renderAssets();
      $("actionCount").textContent = String(state.recentActions.length);
    } catch (error) {
      toast("内容同步失败", error.message, true);
    }
  }

  async function setBroadcast(start) {
    if (start) {
      const gate = await agent("canStartBroadcast");
      if (!gate.allowed) {
        toast("暂时不能开始伴播", gate.reason || "授权状态不可用", true);
        return;
      }
    }
    const hint = $("assistantHint");
    hint.textContent = start ? "正在启动语音识别..." : "正在安全停止...";
    renderAsrPhase(start ? "CONNECTING" : "DISCONNECTING");
    try {
      const response = await agent(start ? "startAsr" : "stopAsr");
      renderAsrPhase(response.asrStatus || (start ? "CONNECTING" : "STOPPED"));
      toast(start ? "伴播正在启动" : "伴播已停止", start ? "正在连接麦克风与语音服务" : "本次语音会话已结束");
      setTimeout(() => {
        refreshAsrStatus();
        refreshRuntime();
      }, 500);
    } catch (error) {
      renderAsrPhase("ERROR", error.message);
      toast(start ? "启动失败" : "停止失败", error.message, true);
    } finally {
      setTimeout(() => hint.textContent = "", 2500);
    }
  }

  async function testAction(code) {
    if (!code) return toast("暂无可测试动作", "请先配置至少一个动作", true);
    try {
      const result = await agent("sendCommand", { actionCode: code });
      if (result.success) {
        const outputOpen = Boolean(state.liveOutput?.isOpen);
        toast(
          outputOpen ? "动作执行成功" : "动作预览成功",
          outputOpen ? "请在 AI Live Renderer 窗口中查看" : "直播输出窗口尚未打开");
      } else {
        const failure = `${result.errorCode || ""} ${result.reason || ""} ${result.message || ""}`;
        if (failure.includes("RENDERER_NOT_CONNECTED") || failure.includes("Renderer")) {
          toast("动作服务未连接", "请检查 Renderer 状态", true);
        } else {
          toast("动作未执行", result.reason || result.message || "动作被拒绝", true);
        }
      }
      setTimeout(() => {
        refreshCollections();
        refreshRendererStatus();
      }, 400);
    } catch (error) {
      const rendererUnavailable = error.message.includes("RENDERER_NOT_CONNECTED")
        || error.message.includes("Renderer");
      toast(
        rendererUnavailable ? "动作服务未连接" : "动作测试失败",
        rendererUnavailable ? "请检查 Renderer 状态" : error.message,
        true);
    }
  }

  function updateLiveOutputUi(status) {
    if (!status) return;
    state.liveOutput = status;
    const settings = status.settings || {};
    const stateLabel = {
      CONNECTED: "已打开 · Renderer 已连接",
      CONNECTING: "连接中",
      FAILED: "启动失败",
      CLOSED: "未打开"
    }[status.state] || "未打开";
    $("liveOutputState").textContent = stateLabel;
    if ($("liveOutputSettingsStatus")) $("liveOutputSettingsStatus").textContent = stateLabel;
    $("liveOutputState").className = `live-output-state ${String(status.state || "CLOSED").toLowerCase()}`;
    $("liveCanvasMode").value = settings.canvasMode || "PORTRAIT";
    $("liveCanvasWidth").value = settings.canvasWidth || 1080;
    $("liveCanvasHeight").value = settings.canvasHeight || 1920;
    $("livePreviewWidth").value = Math.round(settings.previewWindowWidth || 405);
    $("livePreviewHeight").value = Math.round(settings.previewWindowHeight || 720);
    $("liveChromaColor").value = settings.chromaKeyColor || "#00FF00";
    $("liveAutoOpen").checked = Boolean(settings.autoOpenLiveOutput);
    $("liveOutputPreview").style.background = settings.chromaKeyColor || "#00FF00";
    $("liveOutputPreview").classList.toggle("landscape", settings.canvasWidth > settings.canvasHeight);
    $("liveOutputPreviewSize").textContent = `${settings.canvasWidth || 1080} × ${settings.canvasHeight || 1920}`;
    $("openLiveOutput").textContent = status.isOpen ? "查看直播输出窗口" : "打开直播输出窗口";
    $("closeLiveOutput").disabled = !status.isOpen;
    $("liveOutputHint").textContent = status.errorMessage
      || (status.isOpen
        ? "直播伴侣中请选择 AI Live Renderer，不要选择 AI Live Edge。"
        : "打开后，请在直播伴侣的“窗口”素材中选择 AI Live Renderer。");
  }

  async function refreshLiveOutputStatus() {
    try {
      updateLiveOutputUi(await agent("getLiveOutputStatus"));
    } catch (error) {
      $("liveOutputHint").textContent = error.message;
    }
  }

  function liveOutputForm() {
    return {
      canvasMode: $("liveCanvasMode").value,
      canvasWidth: Number($("liveCanvasWidth").value),
      canvasHeight: Number($("liveCanvasHeight").value),
      previewWindowWidth: Number($("livePreviewWidth").value),
      previewWindowHeight: Number($("livePreviewHeight").value),
      chromaKeyColor: $("liveChromaColor").value,
      autoOpenLiveOutput: $("liveAutoOpen").checked
    };
  }

  async function saveLiveOutputSettings(showToast = true) {
    try {
      const status = await agent("updateLiveOutputSettings", liveOutputForm());
      updateLiveOutputUi(status);
      if (showToast) toast("直播输出设置已保存", `${status.settings.canvasWidth} × ${status.settings.canvasHeight}`);
      return status;
    } catch (error) {
      toast("保存直播输出设置失败", error.message, true);
      throw error;
    }
  }

  async function openLiveOutput() {
    try {
      await saveLiveOutputSettings(false);
      updateLiveOutputUi(await agent("openLiveOutput"));
      toast("直播输出窗口已打开", "请在直播伴侣中选择 AI Live Renderer");
      setTimeout(refreshLiveOutputStatus, 1000);
    } catch (error) {
      toast("直播输出窗口启动失败", error.message, true);
    }
  }

  async function closeLiveOutput() {
    try {
      updateLiveOutputUi(await agent("closeLiveOutput"));
      toast("直播输出窗口已关闭");
    } catch (error) {
      toast("关闭直播输出窗口失败", error.message, true);
    }
  }

  async function testMicrophone() {
    try {
      await agent("testAudio");
      $("assistantHint").textContent = "请对着麦克风说话，正在检测声音...";
      toast("麦克风测试已开始", "请正常说话以检查输入");
    } catch (error) {
      toast("麦克风测试失败", error.message, true);
    }
  }

  async function uploadAsset(file) {
    if (!file) return;
    try {
      toast("正在添加素材", file.name);
      const base64 = await readFileAsBase64(file);
      await agent("uploadAsset", {
        fileName: file.name,
        contentType: file.type || "application/octet-stream",
        base64
      });
      toast("素材已添加", file.name);
      await refreshCollections();
    } catch (error) {
      toast("素材添加失败", error.message, true);
    } finally {
      $("assetUpload").value = "";
    }
  }

  function readFileAsBase64(file) {
    return new Promise((resolve, reject) => {
      const reader = new FileReader();
      reader.onload = () => resolve(String(reader.result).split(",", 2)[1] || "");
      reader.onerror = () => reject(reader.error || new Error("无法读取素材文件。"));
      reader.readAsDataURL(file);
    });
  }

  function readBindings() {
    try { return JSON.parse(localStorage.getItem("ai-live-edge.asset-bindings") || "{}"); }
    catch { return {}; }
  }

  function openBinding(assetName) {
    state.bindingAsset = assetName;
    $("bindAssetName").textContent = assetName;
    const current = readBindings()[assetName]?.code;
    $("bindOptions").innerHTML = state.commands.length ? state.commands.map((command) => `
      <label class="bind-option"><input type="radio" name="binding" value="${escapeHtml(command.code)}" ${current === command.code ? "checked" : ""}>
      <span>${escapeHtml(command.name || command.code)}</span></label>`).join("") :
      '<div class="empty-state"><strong>暂无动作</strong><small>请先在动作配置中添加动作。</small></div>';
    $("bindDialog").showModal();
  }

  function openPreview(url) {
    const asset = state.assets.find((item) => resolveAssetUrl(item.assetUrl) === url);
    const category = asset ? assetCategory(asset) : "IMAGE";
    $("previewTitle").textContent = asset?.fileName || "素材预览";
    $("previewStage").replaceChildren();
    const media = document.createElement(category === "WEBM" ? "video" : "img");
    media.src = url;
    if (media instanceof HTMLVideoElement) {
      media.controls = true;
      media.autoplay = true;
      media.loop = true;
      media.muted = true;
    }
    $("previewStage").append(media);
    $("previewDialog").showModal();
  }

  function saveBinding(event) {
    const selected = document.querySelector('input[name="binding"]:checked');
    if (!selected || !state.bindingAsset) {
      event.preventDefault();
      return toast("请选择动作", "", true);
    }
    const command = state.commands.find((item) => item.code === selected.value);
    const bindings = readBindings();
    bindings[state.bindingAsset] = { code: command.code, name: command.name || command.code };
    localStorage.setItem("ai-live-edge.asset-bindings", JSON.stringify(bindings));
    toast("绑定已保存", `${state.bindingAsset} → ${command.name || command.code}`);
    setTimeout(renderAssets);
  }

  function updateConnectionUi(settings) {
    state.connection = settings;
    const isCloud = settings.mode === "CLOUD";
    $("headerLocalMode").classList.toggle("active", !isCloud);
    $("headerCloudMode").classList.toggle("active", isCloud);
    document.querySelectorAll("[data-form-mode]").forEach((button) =>
      button.classList.toggle("active", button.dataset.formMode === settings.mode));
    $("localAddressInput").value = settings.localAddress || "";
    $("cloudAddressInput").value = settings.cloudAddress || "";
  }

  function updateAgentRuntimeUi(runtime) {
    state.agentRuntime = runtime;
    document.querySelectorAll("[data-agent-runtime-mode]").forEach((button) =>
      button.classList.toggle("active", button.dataset.agentRuntimeMode === runtime.mode));
    const development = runtime.mode === "DEVELOPMENT";
    $("agentRuntimeHint").textContent = runtime.message
      || (development
        ? "连接 IDEA 中已启动的 Agent，不启动本地 JAR"
        : "由 Desktop 自动启动 agent/ai-live-edge-agent.jar");
  }

  async function refreshAgentRuntimeMode() {
    try {
      updateAgentRuntimeUi(await agent("getAgentRuntimeMode"));
    } catch (error) {
      $("agentRuntimeHint").textContent = error.message;
    }
  }

  async function setAgentRuntimeMode(mode) {
    document.querySelectorAll("[data-agent-runtime-mode]").forEach((button) => button.disabled = true);
    try {
      const runtime = await agent("setAgentRuntimeMode", { mode });
      updateAgentRuntimeUi(runtime);
      toast(
        mode === "DEVELOPMENT" ? "已切换到开发模式" : "已切换到本地 Jar 模式",
        runtime.message || "",
        runtime.connected === false);
      setTimeout(() => {
        refreshRuntime();
        refreshRendererStatus();
      }, 500);
    } catch (error) {
      toast("Agent 运行模式切换失败", error.message, true);
      await refreshAgentRuntimeMode();
    } finally {
      document.querySelectorAll("[data-agent-runtime-mode]").forEach((button) => button.disabled = false);
    }
  }

  async function configureConnection(mode, remember = true) {
    $("connectionSaveHint").textContent = "正在连接...";
    try {
      const settings = await agent("configureConnection", {
        mode,
        remember,
        localAddress: $("localAddressInput").value.trim(),
        cloudAddress: $("cloudAddressInput").value.trim()
      });
      updateConnectionUi(settings);
      $("connectionSaveHint").textContent = `${settings.mode} 模式已保存`;
      toast("运行模式已切换", settings.mode === "CLOUD" ? "正在连接 AI Live Cloud" : "正在连接本地 Agent");
      await Promise.allSettled([
        refreshRuntime(),
        refreshAsrStatus(),
        refreshCollections(),
        refreshRendererStatus(),
        refreshAgentRuntimeMode(),
        refreshLiveOutputStatus()
      ]);
      return settings;
    } catch (error) {
      $("connectionSaveHint").textContent = error.message;
      toast("连接设置失败", error.message, true);
      throw error;
    }
  }

  async function initializeConnection() {
    try {
      const settings = await agent("getConnectionSettings");
      updateConnectionUi(settings);
      if (!settings.hasSelectedMode) {
        $("modeSetupDialog").showModal();
      }
      await Promise.allSettled([refreshRuntime(), refreshAsrStatus(), refreshCollections(), refreshRendererStatus()]);
    } catch (error) {
      toast("连接层初始化失败", error.message, true);
    }
  }

  document.addEventListener("click", (event) => {
    const nav = event.target.closest("[data-page]");
    const link = event.target.closest("[data-page-link]");
    const test = event.target.closest("[data-test-action]");
    const preview = event.target.closest("[data-preview]");
    const bind = event.target.closest("[data-bind]");
    if (nav) navigate(nav.dataset.page);
    if (link) navigate(link.dataset.pageLink);
    if (test) testAction(test.dataset.testAction);
    if (preview) openPreview(preview.dataset.preview);
    if (bind) openBinding(bind.dataset.bind);
  });

  document.querySelectorAll("[data-settings]").forEach((button) => button.addEventListener("click", () => {
    document.querySelectorAll("[data-settings]").forEach((node) => node.classList.toggle("active", node === button));
    document.querySelectorAll(".settings-pane").forEach((node) => node.classList.remove("active"));
    $(`settings-${button.dataset.settings}`).classList.add("active");
  }));

  document.querySelectorAll("[data-form-mode]").forEach((button) => button.addEventListener("click", () => {
    document.querySelectorAll("[data-form-mode]").forEach((node) => node.classList.toggle("active", node === button));
    const cloud = button.dataset.formMode === "CLOUD";
    document.querySelectorAll(".cloud-field").forEach((field) => field.style.opacity = cloud ? "1" : ".48");
  }));

  document.querySelectorAll("[data-switch-mode]").forEach((button) => button.addEventListener("click", () => {
    configureConnection(button.dataset.switchMode).catch(() => {});
  }));

  document.querySelectorAll("[data-agent-runtime-mode]").forEach((button) =>
    button.addEventListener("click", () => setAgentRuntimeMode(button.dataset.agentRuntimeMode)));

  document.querySelectorAll('input[name="setupMode"]').forEach((input) => input.addEventListener("change", () => {
    document.querySelectorAll(".setup-option").forEach((option) =>
      option.classList.toggle("active", option.contains(input) && input.checked));
  }));

  document.querySelectorAll("#assetFilters .filter").forEach((button) => button.addEventListener("click", () => {
    document.querySelectorAll("#assetFilters .filter").forEach((node) => node.classList.toggle("active", node === button));
    state.assetFilter = button.dataset.filter;
    renderAssets();
  }));

  $("heroStart").onclick = () => setBroadcast(true);
  $("startBroadcast").onclick = () => setBroadcast(true);
  $("assistantStart").onclick = () => setBroadcast(true);
  $("stopBroadcast").onclick = () => setBroadcast(false);
  $("assistantStop").onclick = () => setBroadcast(false);
  $("testFirstAction").onclick = () => testAction(state.commands[0]?.code);
  $("openLiveOutput").onclick = openLiveOutput;
  $("closeLiveOutput").onclick = closeLiveOutput;
  $("testLiveOutput").onclick = () => testAction(
    state.commands.find((command) => command.code === "heart")?.code || state.commands[0]?.code);
  $("saveLiveOutput").onclick = () => saveLiveOutputSettings();
  $("liveCanvasMode").onchange = () => {
    const mode = $("liveCanvasMode").value;
    if (mode === "PORTRAIT") {
      $("liveCanvasWidth").value = 1080;
      $("liveCanvasHeight").value = 1920;
      $("livePreviewWidth").value = 405;
      $("livePreviewHeight").value = 720;
    } else if (mode === "LANDSCAPE") {
      $("liveCanvasWidth").value = 1920;
      $("liveCanvasHeight").value = 1080;
      $("livePreviewWidth").value = 960;
      $("livePreviewHeight").value = 540;
    }
  };
  $("micTest").onclick = testMicrophone;
  $("refreshActions").onclick = refreshCollections;
  $("assetUpload").onchange = (event) => uploadAsset(event.target.files[0]);
  $("confirmBinding").onclick = saveBinding;
  $("saveConnection").onclick = () => {
    const mode = document.querySelector("[data-form-mode].active")?.dataset.formMode || "LOCAL";
    configureConnection(mode).catch(() => {});
  };
  $("saveCloudApiBaseUrl").onclick = saveCloudApiBaseUrl;
  $("settingStartWithWindows").onchange = () => saveAppSettings({ startWithWindows: $("settingStartWithWindows").checked });
  $("settingStartMinimized").onchange = () => saveAppSettings({ startMinimized: $("settingStartMinimized").checked });
  $("settingCloseBehavior").onchange = () => saveAppSettings({ closeBehavior: $("settingCloseBehavior").value });
  $("settingShowTrayNotification").onchange = () => saveAppSettings({ showTrayNotification: $("settingShowTrayNotification").checked });
  $("settingRendererAutoStart").onchange = () => saveAppSettings({ rendererAutoStart: $("settingRendererAutoStart").checked });
  $("settingLogRetentionDays").onchange = () => saveAppSettings({ logRetentionDays: Number($("settingLogRetentionDays").value || 14) });
  $("openLogsButton").onclick = () => agent("openLogsDirectory").catch((error) => toast("无法打开日志目录", error.message, true));
  $("cleanupLogsButton").onclick = async () => {
    try {
      await agent("cleanupExpiredLogs");
      toast("过期日志已清理");
    } catch (error) {
      toast("日志清理失败", error.message, true);
    }
  };
  $("refreshErrorsButton").onclick = refreshRecentErrors;
  $("confirmMode").onclick = async (event) => {
    event.preventDefault();
    const mode = document.querySelector('input[name="setupMode"]:checked')?.value || "LOCAL";
    try {
      await configureConnection(mode, $("rememberMode").checked);
      $("modeSetupDialog").close();
    } catch {
      // Keep the setup dialog open so the user can correct the connection.
    }
  };
  $("enableDeveloper").onclick = () => {
    $("developerGate").style.display = "none";
    $("developerConsole").classList.add("visible");
    if (!$("developerFrame").src) {
      const localAddress = state.connection?.localAddress || "";
      $("developerFrame").src = `${localAddress.replace(/\/?$/, "/")}console/index.html`;
    }
  };
  $("exitDeveloper").onclick = () => {
    $("developerConsole").classList.remove("visible");
    $("developerGate").style.display = "";
    $("developerFrame").src = "";
  };
  $("loginSubmit").onclick = submitLogin;
  $("retryCloudStatus").onclick = async () => {
    $("loginNetwork").textContent = "连接中";
    try {
      updateCloudLoginStatus(await agent("getCloudApiSettings"));
      await refreshAppInfo();
    } catch {
      $("loginNetwork").textContent = "云服务暂时无法连接，请检查网络后重试";
    }
  };
  $("loginPassword").addEventListener("keydown", (event) => {
    if (event.key === "Enter") submitLogin();
  });
  $("loginUsername").addEventListener("keydown", (event) => {
    if (event.key === "Enter") submitLogin();
  });
  $("togglePassword").onclick = () => {
    const visible = $("loginPassword").type === "text";
    $("loginPassword").type = visible ? "password" : "text";
    $("togglePassword").textContent = visible ? "显示" : "隐藏";
  };
  $("identityMenuButton").onclick = () => {
    document.querySelector(".identity-menu").classList.toggle("open");
  };
  $("accountInfo").onclick = () => {
    const user = state.auth?.user || {};
    const tenant = state.auth?.tenant || {};
    toast(user.nickname || user.username || "账号信息", tenant.name || "");
  };
  $("logoutButton").onclick = async () => {
    try {
      const auth = await agent("logout");
      applyAuthState(auth);
    } catch (error) {
      toast("退出登录失败", error.message, true);
    }
  };

  initializeAuth();
})();
