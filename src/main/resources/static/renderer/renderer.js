(function () {
  const params = new URLSearchParams(window.location.search);
  const width = Number(params.get("width") || "1920");
  const height = Number(params.get("height") || "1080");
  const showStatus = params.get("status") === "1";
  const clientType = params.get("clientType")
    || (params.get("client") === "live-output" ? "LIVE_OUTPUT_WINDOW" : "DESKTOP_HIDDEN");
  const background = params.get("background") || "#00FF00";
  const stage = document.getElementById("stage");
  const status = document.getElementById("status");
  const imageLayer = document.getElementById("imageLayer");
  const videoLayer = document.getElementById("videoLayer");
  let socket;
  let hideTimer;

  stage.style.width = width + "px";
  stage.style.height = height + "px";
  if (clientType === "LIVE_OUTPUT_WINDOW") {
    document.body.classList.add("live-output");
    document.documentElement.style.setProperty("--renderer-background", background);
  }
  if (showStatus) {
    document.body.classList.add("show-status");
    status.classList.remove("hidden");
  }

  function setStatus(text) {
    status.textContent = text;
  }

  function connect() {
    const protocol = window.location.protocol === "https:" ? "wss:" : "ws:";
    socket = new WebSocket(protocol + "//" + window.location.host + "/ws/renderer");
    socket.onopen = function () {
      setStatus("connected");
      reportClient();
    };
    socket.onclose = function () {
      setStatus("disconnected");
      window.setTimeout(connect, 1000);
    };
    socket.onerror = function () { setStatus("error"); };
    socket.onmessage = function (event) {
      const message = JSON.parse(event.data);
      if (message.type === "RENDER_ACTION") {
        renderAction(message.data);
      } else if (message.type === "HIDE_CURRENT") {
        hideCurrent();
      } else if (message.type === "CLEAR_RENDERER") {
        clearRenderer();
      }
    };
  }

  function reportClient() {
    if (!socket || socket.readyState !== WebSocket.OPEN) return;
    socket.send(JSON.stringify({
      type: "RENDERER_CLIENT_INFO",
      clientType: clientType,
      viewportWidth: window.innerWidth,
      viewportHeight: window.innerHeight,
      canvasWidth: width,
      canvasHeight: height
    }));
  }

  function renderAction(data) {
    window.clearTimeout(hideTimer);
    if (data.actionType === "PLAY_WEBM") {
      imageLayer.classList.add("hidden");
      videoLayer.src = data.assetUrl;
      videoLayer.loop = Boolean(data.loop);
      videoLayer.muted = true;
      videoLayer.classList.remove("hidden");
      videoLayer.play().catch(function () { setStatus("video play failed"); });
      videoLayer.onended = function () {
        if (!videoLayer.loop) {
          hideCurrent();
        }
      };
    } else {
      videoLayer.pause();
      videoLayer.removeAttribute("src");
      videoLayer.classList.add("hidden");
      imageLayer.src = data.assetUrl;
      imageLayer.classList.remove("hidden");
    }
    if (data.durationMs > 0) {
      hideTimer = window.setTimeout(hideCurrent, data.durationMs);
    }
  }

  function hideCurrent() {
    imageLayer.classList.add("hidden");
    videoLayer.classList.add("hidden");
    videoLayer.pause();
  }

  function clearRenderer() {
    window.clearTimeout(hideTimer);
    imageLayer.removeAttribute("src");
    videoLayer.removeAttribute("src");
    hideCurrent();
  }

  connect();
  window.addEventListener("resize", reportClient);
})();
