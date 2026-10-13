/* [pigeon-fork] 远程控制页（lamda WebUI，经 /d/<domain>/ 代理）AI 设置浏览器端持久化
 *
 * 目的：把 AI 设置（API 地址 / API Key / 模型 / 系统提示词等）与
 *       Vision Mode、Auto Observe、推理强度、Temperature 等会话开关
 *       保存在**浏览器 localStorage**——同一浏览器打开任意设备自动回填，
 *       不经过服务器、不落库，卸载浏览器数据即清除。
 *
 * 注入方式：pigeon nginx 在代理的 lamda WebUI HTML 的 </head> 前插入
 *           <script src="/__pigeon/ai-persist.js"></script>（同源，满足 CSP script-src 'self'）。
 *
 * 行为约定：
 *  - 设置表单字段：仅回填**空白**字段（不覆盖设备侧已有的专属配置）；
 *  - 会话开关：始终按浏览器偏好回填（复选框/下拉/数字）；
 *  - 用户一旦手工修改某字段（本页会话内），不再自动回填该字段，避免"抢输入"。
 */
(function () {
  "use strict";

  var KEY = "pigeon.aiSettings.v1";
  var FORM_FIELDS = [
    "api_base",
    "api_key",
    "provider",
    "model",
    "system_prompt",
    "max_completion_tokens",
    "context_window"
  ];
  var CHAT_FIELDS = [
    { id: "chat-vision", prop: "checked" },
    { id: "chat-auto-observation", prop: "checked" },
    { id: "chat-reasoning-effort", prop: "value" },
    { id: "chat-temperature", prop: "value" }
  ];
  var touched = {};
  var pending = { value: "", checked: false };

  window.__pigeonAiPersist = true;

  function load() {
    try {
      return JSON.parse(window.localStorage.getItem(KEY) || "{}") || {};
    } catch (e) {
      return {};
    }
  }

  function persist(key, value) {
    try {
      var all = load();
      all[key] = value;
      window.localStorage.setItem(KEY, JSON.stringify(all));
    } catch (e) {
      /* 隐私模式 / 存储受限：静默失败 */
    }
  }

  function fire(node) {
    try {
      node.dispatchEvent(new Event("input", { bubbles: true }));
      node.dispatchEvent(new Event("change", { bubbles: true }));
    } catch (e) {
      /* ignore */
    }
  }

  function restoreForm() {
    var form = document.getElementById("settings-form");
    if (!form || !form.elements) return;
    var stored = load();
    for (var i = 0; i < FORM_FIELDS.length; i++) {
      var name = FORM_FIELDS[i];
      if (touched["f." + name]) continue;
      var node = form.elements[name];
      if (!node) continue;
      var want = stored["f." + name];
      if (want === undefined || want === null || want === "") continue;
      if (String(node.value || "").trim() !== "") continue; /* 设备侧已有配置不覆盖 */
      node.value = want;
      fire(node);
    }
  }

  function restoreChat() {
    var stored = load();
    for (var i = 0; i < CHAT_FIELDS.length; i++) {
      var f = CHAT_FIELDS[i];
      if (touched["c." + f.id]) continue;
      var node = document.getElementById(f.id);
      if (!node) continue;
      var v = stored["c." + f.id];
      if (v === undefined) continue;
      if (f.prop === "checked") {
        if (!!node.checked !== !!v) {
          node.checked = !!v;
          fire(node);
        }
      } else if (String(node.value) !== String(v)) {
        node.value = v;
        fire(node);
      }
    }
  }

  function applyAll() {
    try {
      restoreForm();
    } catch (e) { /* ignore */ }
    try {
      restoreChat();
    } catch (e) { /* ignore */ }
  }

  /* —— 记录用户修改（事件委托：控件被重绘也不丢） —— */
  function onEdit(e) {
    var t = e.target;
    if (!t) return;
    if (t.form && t.form.id === "settings-form" && t.name &&
        FORM_FIELDS.indexOf(t.name) >= 0) {
      touched["f." + t.name] = true;
      persist("f." + t.name, t.value);
      return;
    }
    for (var i = 0; i < CHAT_FIELDS.length; i++) {
      var f = CHAT_FIELDS[i];
      if (t.id === f.id) {
        touched["c." + f.id] = true;
        persist("c." + f.id, f.prop === "checked" ? !!t.checked : t.value);
        return;
      }
    }
  }

  document.addEventListener("input", onEdit, true);
  document.addEventListener("change", onEdit, true);

  /* —— 打开 AI 面板 / 切换 Tab 时补一次（设备侧 loadSettings 是异步的） —— */
  document.addEventListener(
    "click",
    function (e) {
      var t = e.target;
      if (!t || !t.closest) return;
      if (
        t.closest("#button-agent") ||
        t.closest("#agent-tab-settings") ||
        t.closest("#agent-tab-chat")
      ) {
        window.setTimeout(applyAll, 150);
        window.setTimeout(applyAll, 600);
      }
    },
    true
  );

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", applyAll);
  } else {
    applyAll();
  }
  window.addEventListener("load", function () {
    applyAll();
    window.setTimeout(applyAll, 400);
    window.setTimeout(applyAll, 1200);
  });

  /* 兜底：页面初始化后 20 秒内每秒尝试（幂等：仅空白回填 + 未触碰字段） */
  var tries = 0;
  var timer = window.setInterval(function () {
    applyAll();
    if (++tries >= 20) window.clearInterval(timer);
  }, 1000);
})();
