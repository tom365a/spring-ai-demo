/* 真人坐席控制台。坐席在这里认领转人工的会话并亲自回复——不经过任何模型。 */
(function (global) {
  // AdminApi.api 不加前缀，调用方要给完整路径（与 monitor-app.js 一致）
  const api = (path, options) => global.AdminApi.api("/api/v1" + path, options);
  const STATUS = { WAITING: "等待接入", ACTIVE: "接管中", CLOSED: "已结束" };
  let generation = 0;
  let timer = null;
  let openSession = null;

  function operator() {
    let id = null;
    try { id = localStorage.getItem("supportOperator"); } catch { id = null; }
    if (!id) {
      id = "seat_" + Math.random().toString(36).slice(2, 6);
      try { localStorage.setItem("supportOperator", id); } catch { /* 隐私模式下用临时标识 */ }
    }
    return id;
  }

  /* 坐席标识是内部用的（认领归属、并发判定），客户看到的应该是人名。留空就退回标识。 */
  function storedName() {
    let name = null;
    try { name = localStorage.getItem("supportOperatorName"); } catch { name = null; }
    return (name || "").trim();
  }

  function operatorName() {
    return storedName() || operator();
  }

  function setOperatorName(value) {
    const name = (value || "").trim();
    try {
      if (name) localStorage.setItem("supportOperatorName", name);
      else localStorage.removeItem("supportOperatorName");
    } catch { /* 隐私模式下本次会话内有效 */ }
  }

  function typingName() {
    const el = document.querySelector("#supportName");
    return !!el && document.activeElement === el;
  }

  function canAct() {
    return global.AdminApi.can("editor");
  }

  function roleNotice() {
    return canAct()
      ? ""
      : '<div class="issue warn" style="margin-bottom:12px">当前角色是 <strong>' + esc(global.AdminApi.getRole()) +
        '</strong>，只能查看。认领会话、回复、结束接管都需要把右上角「角色」切到 <strong>editor</strong> 或更高。</div>';
  }

  function esc(text) {
    const d = document.createElement("div");
    d.textContent = text == null ? "" : String(text);
    return d.innerHTML;
  }

  function time(value) {
    if (!value) return "-";
    const d = new Date(value);
    return isNaN(d) ? "-" : d.toLocaleTimeString("zh-CN", { hour: "2-digit", minute: "2-digit" });
  }

  function stopPolling() {
    if (timer) { clearInterval(timer); timer = null; }
  }

  async function render(sessionId) {
    const host = document.querySelector("#viewSupport");
    if (!host) return;
    const ticket = ++generation;
    stopPolling();
    openSession = sessionId || null;
    host.innerHTML = '<div class="hint">加载中…</div>';
    try {
      if (openSession) await renderConversation(host, ticket);
      else await renderQueue(host, ticket);
    } catch (error) {
      if (ticket !== generation) return;
      host.innerHTML = '<div class="issue error">加载失败：' + esc(error.message) + "</div>";
    }
  }

  async function renderQueue(host, ticket) {
    const data = await api("/admin/support/queue");
    if (ticket !== generation) return;
    const rows = data.items || [];
    host.innerHTML =
      '<h2>人工坐席</h2>' +
      roleNotice() +
      '<p class="hint">转人工的会话在这里排队。认领后由你本人回复，回复直接进入客户的对话窗口，不经过模型。</p>' +
      '<div class="toolbar"><label class="hint" style="display:flex;align-items:center;gap:6px;margin:0">坐席显示名' +
      '<input id="supportName" type="text" maxlength="20" style="width:160px" placeholder="' + esc(operator()) + '" value="' +
      esc(storedName()) + '" /></label>' +
      '<span class="hint">客户在对话里看到的就是这个名字；留空则显示坐席标识 <strong>' + esc(operator()) + "</strong>。</span></div>" +
      '<div class="toolbar"><button type="button" id="supportRefresh" class="secondary">刷新</button>' +
      '<span class="hint">等待接入 ' + (data.waiting || 0) + " 个</span></div>" +
      (rows.length === 0
        ? '<p class="hint">暂无转人工会话。在客服页点「转人工」后，会话会出现在这里。</p>'
        : '<table><thead><tr><th>会话</th><th>用户</th><th>状态</th><th>坐席</th><th>最后一句</th><th>等待回复</th><th>操作</th></tr></thead><tbody>' +
          rows.map((r) =>
            "<tr>" +
            '<td style="font-family:var(--font-mono);font-size:13px">' + esc(r.sessionId) + "</td>" +
            "<td>" + esc(r.userId) + "</td>" +
            '<td><span class="badge ' + (r.status === "WAITING" ? "write" : "read") + '">' + esc(STATUS[r.status] || r.status) + "</span></td>" +
            "<td>" + esc(r.operatorName || "-") + "</td>" +
            '<td style="max-width:280px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">' + esc(r.lastCustomerText || "-") + "</td>" +
            "<td>" + (r.waitingReply ? '<span class="badge write">是</span>' : "否") + "</td>" +
            '<td><button type="button" class="secondary" data-open="' + esc(r.sessionId) + '">进入</button></td>' +
            "</tr>"
          ).join("") +
          "</tbody></table>");

    const nameInput = host.querySelector("#supportName");
    if (nameInput) nameInput.oninput = () => setOperatorName(nameInput.value);

    host.querySelector("#supportRefresh").onclick = () => render(null);
    host.querySelectorAll("[data-open]").forEach((b) => {
      b.onclick = () => { location.hash = "#/support/" + encodeURIComponent(b.dataset.open); };
    });
    timer = setInterval(() => {
      if (generation === ticket && !openSession && !typingName()) renderQueue(host, ticket).catch(() => {});
    }, 5000);
  }

  async function renderConversation(host, ticket) {
    const data = await api("/admin/support/" + encodeURIComponent(openSession));
    if (ticket !== generation) return;
    const mine = data.operatorName && data.status === "ACTIVE";
    const canReply = data.status === "ACTIVE";

    host.innerHTML =
      '<div class="toolbar"><a href="#/support">← 坐席队列</a>' +
      '<h2 style="margin:0;font-size:16px;flex:1">' + esc(data.userId) + " · " + esc(STATUS[data.status] || data.status) + "</h2>" +
      (data.status === "WAITING"
        ? '<span class="hint">将以「<strong>' + esc(operatorName()) + '</strong>」身份认领</span>' +
          '<button type="button" id="supportClaim"' + (canAct() ? "" : " disabled") + ">认领会话</button>"
        : "") +
      (canReply ? '<button type="button" class="secondary" id="supportClose"' + (canAct() ? "" : " disabled") + ">结束接管</button>" : "") +
      "</div>" +
      roleNotice() +
      '<p class="hint">会话 <span style="font-family:var(--font-mono)">' + esc(data.sessionId) + "</span>" +
      (data.operatorName ? " · 坐席 " + esc(data.operatorName) : "") + " · 模式 " + esc(data.mode) + "</p>" +
      '<div id="supportThread" style="max-height:420px;overflow:auto;border:1px solid var(--line);border-radius:var(--radius);padding:14px;background:var(--surface-2);display:flex;flex-direction:column;gap:10px"></div>' +
      (canReply
        ? '<div style="margin-top:12px;display:grid;gap:8px"><textarea id="supportText" rows="3" placeholder="' +
          (canAct() ? "输入回复，回车发送，Shift+回车换行" : "切到 editor 角色后才能回复") + '"' + (canAct() ? "" : " disabled") + "></textarea>" +
          '<div class="toolbar" style="margin:0"><span class="hint" id="supportFeedback"></span><span class="spacer"></span>' +
          '<button type="button" id="supportSend"' + (canAct() ? "" : " disabled") + ">发送</button></div></div>"
        : '<p class="hint" style="margin-top:12px">认领会话后即可回复。</p>');

    paint(data.messages || []);

    const claim = host.querySelector("#supportClaim");
    if (claim) claim.onclick = async () => {
      claim.disabled = true;
      try {
        await api("/admin/support/" + encodeURIComponent(openSession) + "/claim", {
          method: "POST", body: { operatorId: operator(), operatorName: operatorName() },
        });
        await render(openSession);
      } catch (e) { claim.disabled = false; alert("认领失败：" + e.message); }
    };

    const closeBtn = host.querySelector("#supportClose");
    if (closeBtn) closeBtn.onclick = async () => {
      closeBtn.disabled = true;
      try {
        await api("/admin/support/" + encodeURIComponent(openSession) + "/close", {
          method: "POST", body: { operatorId: operator(), note: "" },
        });
        location.hash = "#/support";
      } catch (e) { closeBtn.disabled = false; alert("结束失败：" + e.message); }
    };

    const box = host.querySelector("#supportText");
    const send = host.querySelector("#supportSend");
    if (send) {
      const submit = async () => {
        const text = box.value.trim();
        if (!text) return;
        send.disabled = true;
        const feedback = host.querySelector("#supportFeedback");
        feedback.textContent = "发送中…";
        try {
          const next = await api("/admin/support/" + encodeURIComponent(openSession) + "/reply", {
            method: "POST", body: { operatorId: operator(), text },
          });
          box.value = "";
          feedback.textContent = "已发送";
          paint(next.messages || []);
        } catch (e) { feedback.textContent = "发送失败：" + e.message; }
        send.disabled = false;
      };
      send.onclick = submit;
      box.onkeydown = (e) => { if (e.key === "Enter" && !e.shiftKey) { e.preventDefault(); submit(); } };
    }

    timer = setInterval(async () => {
      if (generation !== ticket || !openSession) return;
      try {
        const next = await api("/admin/support/" + encodeURIComponent(openSession));
        if (generation === ticket) paint(next.messages || []);
      } catch { /* 轮询失败保持上次画面 */ }
    }, 4000);

    function paint(messages) {
      const thread = host.querySelector("#supportThread");
      if (!thread) return;
      const atBottom = thread.scrollHeight - thread.scrollTop - thread.clientHeight < 40;
      thread.innerHTML = messages.map((m) => {
        const fromUser = m.role === "user";
        return '<div style="align-self:' + (fromUser ? "flex-start" : "flex-end") +
          ";max-width:78%;padding:9px 12px;border-radius:" + (fromUser ? "2px 8px 8px 8px" : "8px 2px 8px 8px") +
          ";background:" + (fromUser ? "var(--surface)" : "var(--brand)") +
          ";color:" + (fromUser ? "var(--text-1)" : "var(--text-inverse)") +
          ";border:1px solid " + (fromUser ? "var(--line)" : "transparent") + ';font-size:14px;line-height:1.65">' +
          '<div style="font-size:12px;opacity:.75;margin-bottom:3px">' + esc(fromUser ? "客户" : (m.agentName || "客服")) + " · " + time(m.createdAt) + "</div>" +
          esc(m.content) + "</div>";
      }).join("");
      if (atBottom) thread.scrollTop = thread.scrollHeight;
    }
  }

  global.SupportUi = { render, stop: stopPolling };
})(window);
