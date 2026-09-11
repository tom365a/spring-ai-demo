(function () {
  const api = window.AdminApi;
  const PLACEHOLDERS = [
    "userId", "summary", "recentMessages", "recentMessagesFormatted",
    "childrenCatalog", "agentDescription", "slots", "slotsJson", "locale",
    "visionSummary", "text", "retrievedBlocks", "mcpBlocks",
    "sessionStatus", "confirmationPayloadSummary", "hasAttachments",
    "attachmentCount", "transcript",
  ];

  const state = {
    view: "agents",
    agents: [],
    models: [],
    skillResources: [],
    tools: [],
    skills: [],
    runtime: {items:[]},
    mcpServers: [],
    detail: null,
    draft: null,
    draftRevision: 0,
    dirty: false,
    editorTab: "basic",
    validateResult: null,
    versions: [],
    playgroundCode: "",
  };

  const $ = (sel, el = document) => el.querySelector(sel);
  const $$ = (sel, el = document) => [...el.querySelectorAll(sel)];

  function toast(msg, type = "ok") {
    const wrap = $("#toastWrap");
    const div = document.createElement("div");
    div.className = "toast " + type;
    div.textContent = msg;
    wrap.appendChild(div);
    setTimeout(() => div.remove(), 3200);
  }

  function fmtTime(v) {
    if (!v) return "-";
    try {
      return new Date(v).toLocaleString();
    } catch {
      return String(v);
    }
  }

  function defaultDraft(code, name, type, description) {
    return {
      code,
      name: name || code,
      description: description || "",
      type: type || "WORKER",
      modelConfig: { resourceId: null, inheritDefaults: true, chatModel: null, temperature: 0.2, maxTokens: 2048, enableVision: false },
      prompts: { systemPrompt: "你是助手。", userPromptTemplate: "{{text}}", outputMode: "TEXT" },
      outputSchema: null,
      tools: [],
      skills: [],
      mcp: { enabled: false, serverIds: [], toolAllowlist: [] },
      capabilities: { enableRag: false },
      children: [],
      routing: type === "SUPERVISOR" ? { confidenceThreshold: 0.55, allowNone: true, clarifyPrompt: null } : null,
      policies: {
        allowWriteTools: false,
        requireConfirmFor: [],
        maxToolRounds: 3,
        maxChildHops: type === "SUPERVISOR" ? 1 : 0,
      },
      memory: { injectSummary: true, windowSize: null, injectDescriptionToSupervisor: true },
      ui: { icon: null, tags: [], sortOrder: 100 },
    };
  }

  function normalizeDraft(d) {
    const base = defaultDraft(d.code, d.name, d.type, d.description);
    return {
      ...base,
      ...d,
      modelConfig: { ...base.modelConfig, ...(d.modelConfig || {}) },
      prompts: { ...base.prompts, ...(d.prompts || {}) },
      mcp: { ...base.mcp, ...(d.mcp || {}) },
      capabilities: { ...base.capabilities, ...(d.capabilities || {}) },
      policies: { ...base.policies, ...(d.policies || {}) },
      memory: { ...base.memory, ...(d.memory || {}) },
      ui: { ...base.ui, ...(d.ui || {}) },
      tools: d.tools || [],
      skills: d.skills || [],
      children: d.children || [],
      routing: d.type === "SUPERVISOR" ? { ...base.routing, ...(d.routing || {}) } : d.routing,
    };
  }

  function applyRoleUi() {
    const role = api.getRole();
    $("#roleSelect").value = role;
    $$("[data-need]").forEach((el) => {
      const need = el.getAttribute("data-need");
      el.classList.toggle("hidden", !api.can(need));
      if (el.tagName === "BUTTON" || el.tagName === "INPUT" || el.tagName === "SELECT") {
        if (el.dataset.keepDisabled !== "1") el.disabled = !api.can(need);
      }
    });
  }

  async function refreshRuntimeBadge() {
    try {
      const cfg = await api.debugConfig();
      const on = !!cfg.agentConfigEnabled;
      const chip = $("#runtimeChip");
      chip.className = "chip " + (on ? "ok" : "warn");
      chip.innerHTML = on
        ? '对话 Runtime <strong>Config</strong>'
        : '对话 Runtime <strong>Legacy</strong>';
      $("#runtimeHint").textContent = on
        ? "APP_AGENT_CONFIG_ENABLED=true：/chat 走已发布配置"
        : "flag 关闭：/chat 仍用固定 Agent；Playground single 仍可验证 draft";
    } catch {
      $("#runtimeChip").innerHTML = '对话 Runtime <strong>?</strong>';
    }
  }

  function parseHash() {
    const h = (location.hash || "#/agents").replace(/^#/, "");
    const parts = h.split("?")[0].split("/").filter(Boolean);
    if (parts[0] === "agents" && parts[1]) return { view: "editor", code: parts[1] };
    if (parts[0] === "playground") {
      const q = new URLSearchParams(h.split("?")[1] || "");
      return { view: "playground", code: q.get("code") || "" };
    }
    if (["models","tools","mcp","skills"].includes(parts[0])) return {view:parts[0]};
    if (parts[0] === "catalog") return { view: "tools" };
    if (parts[0] === "monitor") return { view: "monitor", code: parts[1] ? decodeURIComponent(parts[1]) : "" };
    if (parts[0] === "support") return { view: "support", code: parts[1] ? decodeURIComponent(parts[1]) : "" };
    return { view: "agents" };
  }

  function navigate(hash) {
    if (state.view === "editor" && state.dirty) {
      if (!confirm("有未保存修改，确定离开？")) return;
    }
    location.hash = hash;
  }

  async function route() {
    const r = parseHash();
    state.view = r.view;
    $$(".nav button").forEach((b) => b.classList.toggle("active", b.dataset.view === (r.view === "editor" ? "agents" : r.view)));
    $("#viewAgents").classList.toggle("hidden", r.view !== "agents");
    $("#viewEditor").classList.toggle("hidden", r.view !== "editor");
    $("#viewPlayground").classList.toggle("hidden", r.view !== "playground");
    $("#viewCatalog").classList.toggle("hidden", r.view !== "catalog");
    $("#viewResources").classList.toggle("hidden", !["models","tools","mcp","skills"].includes(r.view));
    applyRoleUi();
    if (!$("#viewMonitor")) { const section=document.createElement("section"); section.id="viewMonitor"; $("#viewResources").parentNode.appendChild(section); }
    $("#viewMonitor").classList.toggle("hidden",r.view!=="monitor");
    if(r.view==="monitor") await MonitorUi.render(r.code);
    if (!$("#viewSupport")) { const section=document.createElement("section"); section.id="viewSupport"; section.className="panel"; $("#viewResources").parentNode.appendChild(section); }
    $("#viewSupport").classList.toggle("hidden",r.view!=="support");
    if(r.view!=="support") SupportUi.stop();
    if(r.view==="support") await SupportUi.render(r.code);
    if (["models","tools","mcp","skills"].includes(r.view)) await ResourceUi.render(r.view);
    if (r.view === "agents") await renderList();
    if (r.view === "editor") await loadEditor(r.code);
    if (r.view === "playground") {
      state.playgroundCode = r.code || state.playgroundCode;
      await renderPlayground();
    }
    if (r.view === "catalog") await renderCatalog();
  }

  async function renderList() {
    const q = {
      type: $("#filterType").value,
      status: $("#filterStatus").value,
      enabled: $("#filterEnabled").value,
      q: $("#filterQ").value.trim(),
    };
    try {
      const data = await api.listAgents(q);
      state.runtime = await api.runtime();
      state.agents = data.items || [];
    } catch (e) {
      toast(e.message, "err");
      $("#agentTable tbody").innerHTML = '<tr><td colspan="8">加载失败，请点击刷新重试。</td></tr>';return;
    }
    const tbody = $("#agentTable tbody");
    tbody.innerHTML = "";
    if (!state.agents.length) {
      tbody.innerHTML =
        '<tr><td colspan="8" class="hint">暂无匹配 Agent，可调整筛选或创建 Agent。</td></tr>';
      return;
    }
    for (const a of state.agents) {
      const tr = document.createElement("tr");
      if (!a.enabled) tr.className = "disabled-row";
      tr.innerHTML = `
        <td><code>${esc(a.code)}</code></td>
        <td>${esc(a.name)}<small>${esc(a.description || "")}</small></td>
        <td><span class="badge ${a.type === "SUPERVISOR" ? "sup" : "worker"}">${esc(a.type)}</span></td>
        <td>${state.runtime.items.some(x => x.code === a.code) ? "已启用" : a.publishedVersion ? "已停用" : "未启用草稿"}</td>
        <td>${a.publishedVersion != null ? "v" + a.publishedVersion : "未发布"}</td>
        <td></td>
        <td>${fmtTime(a.updatedAt)}</td>
        <td class="row-actions"></td>`;
      const enCell = tr.children[5];
      const en = document.createElement("input");
      en.type = "checkbox";
      en.checked = !!a.enabled;
      en.disabled = !api.can("publisher");
      en.onchange = async () => {
        en.disabled = true;
        try {
          if (en.checked) await api.enable(a.code); else await api.disable(a.code);
          toast(en.checked ? "已启用" : "已下线");
          await renderList();
        } catch (e) {
          toast(e.message, "err");
          en.checked = !en.checked;
        } finally { en.disabled = !api.can("publisher"); }
      };
      enCell.appendChild(en);
      const actions = tr.querySelector(".row-actions");
      const edit = document.createElement("button");
      edit.className = "secondary";
      edit.textContent = "编辑";
      edit.onclick = () => navigate("#/agents/" + a.code);
      const trial = document.createElement("button");
      trial.className = "secondary";
      trial.textContent = "试运行";
      trial.onclick = () => navigate("#/playground?code=" + encodeURIComponent(a.code));
      actions.append(edit, " ", trial);
      tbody.appendChild(tr);
    }
  }

  function esc(s) {
    return String(s ?? "")
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;");
  }

  async function loadEditor(code) {
    try {
      const detail = await api.getAgent(code);
      state.detail = detail;
      state.draft = normalizeDraft(detail.draft);
      state.draftRevision = detail.draftRevision;
      state.dirty = false;
      state.validateResult = null;
      state.tools = (await api.catalogTools()).items || [];
      state.models = await publishedResources("MODEL");
      state.skillResources = await publishedResources("SKILL");
      state.skills = (await api.catalogSkills()).items || [];
      state.runtime = await api.runtime();
      state.mcpServers = (await api.catalogMcp()).items || [];
      {
        const list = await api.listAgents({});
        state.agents = list.items || [];
      }
      try {
        state.versions = (await api.versions(code)).items || [];
      } catch {
        state.versions = [];
      }
      renderEditor();
    } catch (e) {
      toast(e.message, "err");
      navigate("#/agents");
    }
  }

  function markDirty() {
    state.dirty = true;
    $("#dirtyDot").classList.toggle("hidden", false);
  }

  function collectDraftFromForm() {
    const d = state.draft;
    const isSup = d.type === "SUPERVISOR";
    d.name = $("#fName").value.trim();
    d.description = $("#fDesc").value.trim();
    d.ui = {
      icon: $("#fIcon").value.trim() || null,
      tags: $("#fTags").value.split(/[,，]/).map((x) => x.trim()).filter(Boolean).slice(0, 10),
      sortOrder: Number($("#fSort").value) || 100,
    };
    d.modelConfig = {
      resourceId: $("#fModel").value || null,
      inheritDefaults: true,
      chatModel: null,
      temperature: d.modelConfig?.temperature ?? 0.2,
      maxTokens: Number($("#fMaxTokens").value) || 2048,
      enableVision: $("#fVision").checked,
    };
    d.prompts = {
      systemPrompt: $("#fSystem").value,
      userPromptTemplate: $("#fUserTpl").value,
      outputMode: $("#fOutputMode").value,
    };
    let schema = null;
    if (d.prompts.outputMode === "JSON_SCHEMA") {
      const raw = $("#fSchema").value.trim();
      if (raw) {
        try {
          schema = JSON.parse(raw);
        } catch {
          throw new Error("outputSchema 不是合法 JSON");
        }
      }
    }
    d.outputSchema = schema;
    d.tools = $$("#toolsBox input[type=checkbox]:checked").map((c) => c.value);
    d.skills = $$("#skillsBox input:checked").map(c => c.value);
    d.skillVersions = Object.fromEntries(d.skills.map(code=>[code,d.skillVersions?.[code] || state.skillResources.find(r=>r.code===code)?.publishedVersion || 1]));
    d.mcp = {
      enabled: $("#fMcpEnabled").checked,
      serverIds: $$("#mcpServersBox input[data-server]:checked").map((c) => c.value),
      toolAllowlist: $$("#mcpToolsBox input[type=checkbox]:checked").map((c) => c.value),
    };
    d.capabilities = { enableRag: $("#fEnableRag").checked };
    d.policies = {
      allowWriteTools: isSup ? false : $("#fAllowWrite").checked,
      requireConfirmFor: $$("#confirmToolsBox input:checked").map((c) => c.value),
      maxToolRounds: Number($("#fMaxRounds").value),
      maxChildHops: isSup ? 1 : 0,
      toolTimeoutSeconds: Number($("#fToolTimeout").value),
      taskTimeoutSeconds: Number($("#fTaskTimeout").value),
    };
    d.memory = {
      injectSummary: $("#fInjectSummary").checked,
      windowSize: $("#fWindow").value === "" ? null : Number($("#fWindow").value),
      injectDescriptionToSupervisor: $("#fInjectDesc").checked,
    };
    if (isSup) {
      d.routing = {
        confidenceThreshold: Number($("#fConf").value) || 0.55,
        allowNone: $("#fAllowNone").checked,
        clarifyPrompt: $("#fClarify").value.trim() || null,
      };
      d.children = $$("#childrenBox .children-row").map((row) => ({
        agentCode: $(".c-code", row).value,
        alias: $(".c-alias", row).value.trim() || null,
        whenToUse: $(".c-when", row).value.trim(),
        priority: Number($(".c-pri", row).value) || 10,
        enabled: $(".c-en", row).checked,
      }));
    } else {
      d.children = [];
      d.routing = null;
    }
    return d;
  }

  function renderEditor() {
    const d = state.draft;
    const detail = state.detail;
    const isSup = d.type === "SUPERVISOR";
    $("#editorTitle").textContent = d.code + " · " + d.name;
    $("#sumCode").textContent = d.code;
    $("#sumType").textContent = d.type;
    $("#sumVer").textContent = detail.publishedVersion != null ? "v" + detail.publishedVersion : "未发布";
    const loaded = state.runtime.items.find(x => x.code === d.code);
    $("#sumEnabled").textContent = loaded ? "已加载 v" + loaded.loadedVersion : detail.publishedVersion ? "已停用" : "未启用草稿";
    $("#btnPublish").textContent = loaded ? "发布更新" : "启用";
    $("#dirtyDot").classList.toggle("hidden", !state.dirty && !detail.dirty);
    $("#tabChildrenBtn").classList.toggle("hidden", !isSup);
    $("#tabRoutingBlock").classList.toggle("hidden", !isSup);
    $("#fAllowWrite").disabled = isSup || !api.can("editor");
    if (isSup) $("#fAllowWrite").checked = false;

    $("#fName").value = d.name || "";
    $("#fDesc").value = d.description || "";
    $("#fIcon").value = (d.ui && d.ui.icon) || "";
    $("#fTags").value = ((d.ui && d.ui.tags) || []).join(", ");
    $("#fSort").value = (d.ui && d.ui.sortOrder) != null ? d.ui.sortOrder : 100;

    renderModelSelector("#fModel", d.modelConfig?.resourceId || "");
    $("#fTemp").value = (d.modelConfig && d.modelConfig.temperature) != null ? d.modelConfig.temperature : 0.2;
    $("#fTempVal").textContent = $("#fTemp").value;
    $("#fMaxTokens").value = (d.modelConfig && d.modelConfig.maxTokens) || 2048;
    $("#fVision").checked = !!(d.modelConfig && d.modelConfig.enableVision);

    $("#fSystem").value = (d.prompts && d.prompts.systemPrompt) || "";
    $("#fUserTpl").value = (d.prompts && d.prompts.userPromptTemplate) || "";
    $("#fOutputMode").value = (d.prompts && d.prompts.outputMode) || "TEXT";
    $("#fSchema").value = d.outputSchema ? JSON.stringify(d.outputSchema, null, 2) : "";
    $("#schemaWrap").classList.toggle("hidden", $("#fOutputMode").value !== "JSON_SCHEMA");
    renderPlaceholderChips();

    renderTools();
    renderSkills("#skillsBox", d.type, d.skills);
    renderMcp();
    renderChildren();
    renderPolicies();
    renderPublishTab();
    setEditorTab(state.editorTab);
    applyRoleUi();
    $$("#viewEditor input, #viewEditor textarea, #viewEditor select").forEach((el) => {
      if (el.id === "roleSelect") return;
      el.oninput = () => markDirty();
      const previous=el.onchange;el.onchange = (event) => {if(previous)previous(event);markDirty();if(el.id==="fModel")effectiveModel();};
    });
  }

  function renderPlaceholderChips() {
    const box = $("#phChips");
    box.innerHTML = "";
    PLACEHOLDERS.forEach((p) => {
      const b = document.createElement("button");
      b.type = "button";
      b.textContent = "{{" + p + "}}";
      b.onclick = () => {
        const ta = document.activeElement && document.activeElement.tagName === "TEXTAREA"
          ? document.activeElement
          : $("#fSystem");
        insertAtCursor(ta, "{{" + p + "}}");
        markDirty();
      };
      box.appendChild(b);
    });
  }

  function insertAtCursor(ta, text) {
    const start = ta.selectionStart || 0;
    const end = ta.selectionEnd || 0;
    const v = ta.value;
    ta.value = v.slice(0, start) + text + v.slice(end);
    ta.focus();
    ta.selectionStart = ta.selectionEnd = start + text.length;
  }

  function renderTools() {
    const box = $("#toolsBox");
    box.innerHTML = "";
    const byDom = {};
    for (const t of state.tools) {
      (byDom[t.ownerDomain] || (byDom[t.ownerDomain] = [])).push(t);
    }
    const selected = new Set(state.draft.tools || []);
    const isSup = state.draft.type === "SUPERVISOR";
    for (const [dom, list] of Object.entries(byDom)) {
      const g = document.createElement("div");
      g.className = "tool-group";
      g.innerHTML = "<h4>" + esc(dom) + "</h4>";
      for (const t of list) {
        const row = document.createElement("label");
        row.className = "tool-item";
        const write = t.sideEffect === "WRITE";
        const disabled = (isSup && write) || !api.can("editor") || t.enabled===false;
        row.innerHTML = `<input type="checkbox" value="${esc(t.code)}" ${selected.has(t.code) ? "checked" : ""} ${disabled ? "disabled" : ""}/>
          <div><strong>${esc(t.name)}</strong> <span class="badge ${write ? "write" : "read"}">${esc(t.sideEffect)}</span>
          <small>${esc(t.code)} · ${esc(t.description || "")}</small></div>`;
        const cb = row.querySelector("input");
        cb.onchange = () => {
          if (cb.checked && write) toast("WRITE 工具将进入确认策略");
          markDirty();
          renderPolicies();
        };
        g.appendChild(row);
      }
      box.appendChild(g);
    }
  }

  function renderMcp() {
    const servers = state.mcpServers || [];
    const mcp = state.draft.mcp || { enabled: false, serverIds: [], toolAllowlist: [] };
    $("#fMcpEnabled").checked = !!mcp.enabled;
    const sBox = $("#mcpServersBox");
    sBox.innerHTML = "";
    for (const s of servers) {
      const lab = document.createElement("label");
      lab.className = "check-row";
      lab.innerHTML = `<input type="checkbox" data-server value="${esc(s.id)}" ${mcp.serverIds.includes(s.id) ? "checked" : ""}/>
        <span>${esc(s.name)} <span class="badge">${esc(s.status)}</span> <small>${esc(s.endpoint)}</small></span>`;
      const serverCheckbox=lab.querySelector("input");serverCheckbox.onchange=()=>{for(const cb of $$("#mcpToolsBox input")){const selected=$$("#mcpServersBox input:checked").some(n=>n.value===cb.dataset.mcpServer);cb.disabled=!selected||!api.can("editor");if(!selected)cb.checked=false;}markDirty();};
      sBox.appendChild(lab);
    }
    const tBox = $("#mcpToolsBox");
    tBox.innerHTML = "";
    for (const s of servers) {
      for (const t of s.tools || []) {
        const name = t.code || t.name;
        const lab = document.createElement("label");
        lab.className = "check-row";
        lab.innerHTML = `<input type="checkbox" data-mcp-server="${esc(s.id)}" ${!mcp.serverIds.includes(s.id)||!api.can("editor")?"disabled":""} value="${esc(name)}" ${mcp.toolAllowlist.includes(name) ? "checked" : ""}/>
          <span>${esc(s.name)} / ${esc(t.name)} <small>${esc(t.description || "")}</small></span>`;
        tBox.appendChild(lab);
      }
    }
  }

  function renderChildren() {
    const box = $("#childrenBox");
    box.innerHTML = "";
    if (state.draft.type !== "SUPERVISOR") return;
    const workers = state.agents.filter(
      (a) => a.type === "WORKER" && a.status === "PUBLISHED" && a.enabled
    );
    const children = state.draft.children || [];
    children.forEach((c, idx) => {
      const row = document.createElement("details");
      row.className = "children-row";
      const opts = (workers.some(w => w.code === c.agentCode) ? "" : `<option value="${esc(c.agentCode)}" selected>${esc(c.agentCode)}（当前不可用）</option>`) + workers
        .map(
          (w) =>
            `<option value="${esc(w.code)}" ${w.code === c.agentCode ? "selected" : ""}>${esc(w.code)} · ${esc(w.name)}</option>`
        )
        .join("");
      row.innerHTML = `
        <summary class="child-summary">${esc(c.agentCode || "选择 agentCode")}</summary>
        <div class="child-config">
        <div class="grid-2">
          <label class="field">agentCode<select class="c-code">${opts}</select></label>
          <label class="field">alias<input class="c-alias" type="text" value="${esc(c.alias || "")}"/></label>
        </div>
        <label class="field">whenToUse<textarea class="c-when" rows="2">${esc(c.whenToUse || "")}</textarea></label>
        <div class="grid-3">
          <label class="field">priority<input class="c-pri" type="number" value="${c.priority != null ? c.priority : 10}"/></label>
          <label class="check-row" style="margin-top:18px"><input class="c-en" type="checkbox" ${c.enabled !== false ? "checked" : ""}/> enabled</label>
          <div class="toolbar" style="margin:0;align-items:end">
            <button type="button" class="secondary c-up">上移</button>
            <button type="button" class="secondary c-down">下移</button>
            <button type="button" class="danger c-del">删除</button>
          </div>
        </div></div>`;
      $(".c-code", row).addEventListener("change", (event) => {
        $(".child-summary", row).textContent = event.target.value || "选择 agentCode";
      });
      $(".c-up", row).onclick = () => {
        collectChildrenSilent();
        if (idx > 0) {
          const arr = state.draft.children;
          [arr[idx - 1], arr[idx]] = [arr[idx], arr[idx - 1]];
          markDirty();
          renderChildren();
        }
      };
      $(".c-down", row).onclick = () => {
        collectChildrenSilent();
        const arr = state.draft.children;
        if (idx < arr.length - 1) {
          [arr[idx + 1], arr[idx]] = [arr[idx], arr[idx + 1]];
          markDirty();
          renderChildren();
        }
      };
      $(".c-del", row).onclick = () => {
        collectChildrenSilent();
        state.draft.children.splice(idx, 1);
        markDirty();
        renderChildren();
      };
      box.appendChild(row);
    });
  }

  function collectChildrenSilent() {
    if (state.draft.type !== "SUPERVISOR") return;
    state.draft.children = $$("#childrenBox .children-row").map((row) => ({
      agentCode: $(".c-code", row).value,
      alias: $(".c-alias", row).value.trim() || null,
      whenToUse: $(".c-when", row).value.trim(),
      priority: Number($(".c-pri", row).value) || 10,
      enabled: $(".c-en", row).checked,
    }));
  }

  function renderPolicies() {
    const d = state.draft;
    $("#fAllowWrite").checked = !!(d.policies && d.policies.allowWriteTools) && d.type !== "SUPERVISOR";
    $("#fMaxRounds").value = d.policies?.maxToolRounds ?? 3;
    $("#fToolTimeout").value = d.policies?.toolTimeoutSeconds ?? 30;
    $("#fTaskTimeout").value = d.policies?.taskTimeoutSeconds ?? 120;
    $("#fMaxHops").value = d.type === "SUPERVISOR" ? 1 : 0;
    $("#fMaxHops").closest("label").classList.toggle("hidden",d.type!=="SUPERVISOR");
    $("#fInjectSummary").checked = d.memory?.injectSummary !== false;
    $("#fWindow").value = d.memory?.windowSize != null ? d.memory.windowSize : "";
    $("#fInjectDesc").checked = d.memory?.injectDescriptionToSupervisor !== false;
    $("#fEnableRag").checked = !!(d.capabilities && d.capabilities.enableRag);
    if (d.type === "SUPERVISOR" && d.routing) {
      $("#fConf").value = d.routing.confidenceThreshold != null ? d.routing.confidenceThreshold : 0.55;
      $("#fAllowNone").checked = d.routing.allowNone !== false;
      $("#fClarify").value = d.routing.clarifyPrompt || "";
    }
    const selectedTools = [...$$("#toolsBox input:checked").map((c) => c.value),...$$("#mcpToolsBox input:checked").map(c=>c.value)];
    const implied=(d.skills||[]).flatMap(code=>state.skillResources.find(r=>r.code===code)?.config?.tools||[]);
    const writeTools = state.tools.filter((t) => selectedTools.includes(t.code)||implied.includes(t.code));
    const conf = new Set((d.policies && d.policies.requireConfirmFor) || []);
    const box = $("#confirmToolsBox");
    box.innerHTML = "";
    for (const t of writeTools) {
      const lab = document.createElement("label");
      lab.className = "check-row";
      lab.innerHTML = `<input type="checkbox" value="${esc(t.code)}" ${conf.has(t.code) || t.sideEffect === "WRITE" ? "checked" : ""} ${t.sideEffect === "WRITE" ? "disabled" : ""}/> <span>${esc(t.code)}</span>`;
      box.appendChild(lab);
    }
  }

  function simpleDiff(a, b) {
    const sa = (a == null ? "" : typeof a === "string" ? a : JSON.stringify(a, null, 2)).split("\n");
    const sb = (b == null ? "" : typeof b === "string" ? b : JSON.stringify(b, null, 2)).split("\n");
    const max = Math.max(sa.length, sb.length);
    const lines = [];
    for (let i = 0; i < max; i++) {
      if (sa[i] === sb[i]) continue;
      if (sa[i] != null) lines.push("- " + sa[i]);
      if (sb[i] != null) lines.push("+ " + sb[i]);
    }
    return lines.length ? lines.join("\n") : "(无差异)";
  }

  function renderPublishTab() {
    const pub = state.detail.published;
    const draft = state.draft;
    const parts = [];
    parts.push("## 模型与运行规则\n" + simpleDiff({modelConfig:pub?.modelConfig,policies:pub?.policies,memory:pub?.memory},{modelConfig:draft.modelConfig,policies:draft.policies,memory:draft.memory}));
    parts.push("## 技能绑定版本\n" + simpleDiff(pub?.skillVersions,draft.skillVersions));
    parts.push("## systemPrompt\n" + simpleDiff(pub?.prompts?.systemPrompt, draft.prompts?.systemPrompt));
    parts.push("## tools\n" + simpleDiff(pub?.tools, draft.tools));
    parts.push("## skills\n" + simpleDiff(pub?.skills, draft.skills));
    parts.push("## children\n" + simpleDiff(pub?.children, draft.children));
    $("#diffBox").textContent = parts.join("\n\n");
    const issues = $("#validateBox");
    issues.innerHTML = "";
    if (state.validateResult) {
      for (const e of state.validateResult.errors || []) {
        const div = document.createElement("div");
        div.className = "issue error";
        div.innerHTML = `<button type="button" class="linkish">${esc(e.path)}</button> ${esc(e.message)}`;
        div.querySelector("button").onclick = () => jumpPath(e.path);
        issues.appendChild(div);
      }
      for (const w of state.validateResult.warnings || []) {
        const div = document.createElement("div");
        div.className = "issue warn";
        div.textContent = w.path + ": " + w.message;
        issues.appendChild(div);
      }
      if (!(state.validateResult.errors || []).length && !(state.validateResult.warnings || []).length) {
        issues.innerHTML = '<div class="issue" style="border-color:rgba(46,196,182,.4)">校验通过</div>';
      }
    }
    const vbox = $("#versionsBox");
    vbox.innerHTML = "";
    for (const v of state.versions) {
      const row = document.createElement("div");
      row.className = "toolbar";
      row.innerHTML = `<span class="chip">v${v.version} · ${fmtTime(v.publishedAt)} · ${esc(v.publishedBy || "")}</span>`;
      const view = document.createElement("button");
      view.className = "secondary";
      view.textContent = "查看";
      view.onclick = async () => {
        const snap = await api.version(state.draft.code, v.version);
        alert(JSON.stringify(snap.snapshot, null, 2).slice(0, 4000));
      };
      const rb = document.createElement("button");
      rb.className = "secondary";
      rb.textContent = "回滚到此版";
      rb.setAttribute("data-need", "publisher");
      rb.onclick = async () => {
        if (!await ResourceUi.ask("确认回滚到 v" + v.version + "？将产生新 publishedVersion")) return;
        try {
          await api.rollback(state.draft.code, v.version, "ui rollback");
          toast("已回滚");
          await loadEditor(state.draft.code);
        } catch (e) {
          toast(e.message, "err");
        }
      };
      row.append(view, rb);
      vbox.appendChild(row);
    }
    applyRoleUi();
  }

  function jumpPath(path) {
    if (!path) return;
    if (path.startsWith("prompts")) setEditorTab("prompts");
    else if (path.startsWith("tools") || path.startsWith("skills")) setEditorTab("tools");
    else if (path.startsWith("children") || path.startsWith("routing")) setEditorTab("children");
    else if (path.startsWith("policies") || path.startsWith("memory") || path.startsWith("capabilities"))
      setEditorTab("policies");
    else if (path.startsWith("mcp")) setEditorTab("mcp");
    else if (path.startsWith("model")) setEditorTab("model");
    else setEditorTab("basic");
  }

  function setEditorTab(name) {
    if (name === "children" && state.draft?.type !== "SUPERVISOR") name = "basic";
    state.editorTab = name;
    $$(".tabs [data-tab]").forEach((b) => b.classList.toggle("active", b.dataset.tab === name));
    $$(".tab-panel").forEach((p) => p.classList.toggle("active", p.dataset.panel === name));
  }

  async function saveDraft() {
    try {
      collectChildrenSilent();
      const definition = collectDraftFromForm();
      const body = {
        name: definition.name,
        description: definition.description,
        sortOrder: definition.ui.sortOrder,
        draftRevision: state.draftRevision,
        definition,
      };
      const detail = await api.updateAgent(definition.code, body);
      state.detail = detail;
      state.draft = normalizeDraft(detail.draft);
      state.draftRevision = detail.draftRevision;
      state.dirty = false;
      toast("已保存 draft");
      renderEditor();
    } catch (e) {
      if (e.code === 40901) toast("冲突：请刷新后重试 (" + e.message + ")", "err");
      else toast(e.message, "err");
    }
  }

  async function runValidate() {
    try {
      if (state.dirty || true) {
        // save first so server validates latest draft
        if (api.can("editor")) {
          collectChildrenSilent();
          const definition = collectDraftFromForm();
          const detail = await api.updateAgent(definition.code, {
            name: definition.name,
            description: definition.description,
            draftRevision: state.draftRevision,
            definition,
          });
          state.detail = detail;
          state.draftRevision = detail.draftRevision;
          state.dirty = false;
        }
      }
      state.validateResult = await api.validate(state.draft.code);
      setEditorTab("publish");
      renderPublishTab();
      toast(state.validateResult.ok ? "校验通过" : "校验失败", state.validateResult.ok ? "ok" : "err");
    } catch (e) {
      state.validateResult = {ok:false, errors:[{path:"definition",message:e.message}]};
      toast(e.message, "err");
    }
  }

  async function runPublish() {
    if ($("#btnPublish").disabled) return;
    $("#btnPublish").disabled = true;
    $("#btnPublish").dataset.keepDisabled = "1";
    try {
      await runValidate();
      if (state.validateResult && !state.validateResult.ok) return;
      const remark = $("#publishRemark").value.trim();
      if(!await ResourceUi.ask("确认发布当前草稿？新任务将使用更新后的模型、技能版本、工具权限及运行规则。\n"+simpleDiff(state.detail.published, state.draft))) return;
      const res = state.detail.enabled ? await api.publish(state.draft.code, remark) : await api.enable(state.draft.code);
      toast("已启用并加载 v" + res.publishedVersion);
      await loadEditor(state.draft.code);
    } catch (e) {
      if (e.code === 42201 && e.data) {
        state.validateResult = e.data;
        setEditorTab("publish");
        renderPublishTab();
      }
      toast(e.message, "err");
    } finally { $("#btnPublish").dataset.keepDisabled = "0"; $("#btnPublish").disabled = !api.can("publisher"); }
  }

  function localPreview() {
    const sample = {
      userId: "u_001",
      summary: "用户咨询订单",
      recentMessages: "user: 你好",
      recentMessagesFormatted: "user: 你好",
      childrenCatalog: "1. order — 查单",
      agentDescription: state.draft.description || "",
      slots: "{}",
      slotsJson: "{}",
      locale: "zh-CN",
      visionSummary: "",
      text: "帮我查订单",
      retrievedBlocks: "[1] demo",
      mcpBlocks: "",
      sessionStatus: "active",
      confirmationPayloadSummary: "无",
      hasAttachments: "false",
      attachmentCount: "0",
      transcript: "user: hi",
    };
    let sys = $("#fSystem").value;
    for (const [k, v] of Object.entries(sample)) {
      sys = sys.split("{{" + k + "}}").join(v);
    }
    $("#previewBox").textContent = sys;
  }

  async function renderPlayground() {
    if (!state.agents.length) {
      try {
        state.agents = (await api.listAgents({})).items || [];
      } catch {
        state.agents = [];
      }
    }
    const sel = $("#pgCode");
    sel.innerHTML = state.agents.map((a) => `<option value="${esc(a.code)}">${esc(a.code)}</option>`).join("");
    if (state.playgroundCode) sel.value = state.playgroundCode;
    applyRoleUi();
  }

  async function runTrial() {
    const code = $("#pgCode").value;
    try {
      const data = await api.trial(code, {
        text: $("#pgText").value.trim(),
        userId: $("#pgUser").value.trim() || "trial_u_demo",
        useDraft: $("#pgUseDraft").checked,
        mode: $("#pgMode").value,
      });
      $("#pgAnswer").textContent = data.answer || "";
      $("#pgTrace").textContent = JSON.stringify(data.routeTrace || [], null, 2);
      $("#pgTools").textContent = data.toolCalls?.length?JSON.stringify(data.toolCalls,null,2):"本轮未调用工具";
      const old=$("#pgConfirmation");if(old)old.remove();if(data.confirmRequired||data.confirmationPayload){const holder=document.createElement("div");holder.id="pgConfirmation";$("#viewPlayground").append(holder);ResourceUi.confirmation(holder,data.confirmationPayload,result=>{$("#pgAnswer").textContent=result.answer||JSON.stringify(result.result);$("#pgTools").textContent=JSON.stringify(result.toolCalls||result.diagnostics||[],null,2);holder.remove()});}
      $("#pgPrompts").textContent = (data.promptsRendered && data.promptsRendered.system) || "";
      const actual=data.diagnostics?.models?.[0];
      $("#pgMeta").textContent = 'Agent '+(data.agentCode||actual?.agentCode||code)+' · '+(actual?.agentVersion!=null?'发布 v'+actual.agentVersion:'草稿')+' · '+data.latencyMs+'ms · 实际模型 '+(actual?actual.provider+' / '+actual.model+' · 资源 v'+actual.resourceVersion:'暂无模型诊断');
      $("#pgDiagnostics").textContent=JSON.stringify(data.diagnostics||{},null,2);
      toast("试运行完成");
    } catch (e) {
      toast(e.message, "err");
    }
  }

  async function renderCatalog() {
    try {
      state.tools = (await api.catalogTools()).items || [];
      state.mcpServers = (await api.catalogMcp()).items || [];
    } catch (e) {
      toast(e.message, "err");
    }
    const tb = $("#catalogTools tbody");
    tb.innerHTML = state.tools
      .map(
        (t) =>
          `<tr><td><code>${esc(t.code)}</code></td><td>${esc(t.name)}</td><td><span class="badge ${t.sideEffect === "WRITE" ? "write" : "read"}">${esc(t.sideEffect)}</span></td><td>${esc(t.ownerDomain)}</td><td class="hint">${esc(t.description || "")}</td></tr>`
      )
      .join("");
    const mcp = $("#catalogMcp");
    mcp.innerHTML = "";
    for (const s of state.mcpServers) {
      const div = document.createElement("div");
      div.className = "panel";
      div.innerHTML = `<div class="toolbar"><strong>${esc(s.name)}</strong>
        <span class="badge">${esc(s.status)}</span>
        <span class="hint">${esc(s.endpoint)}</span>
        <span class="spacer"></span>
        <button type="button" class="secondary" data-need="publisher">Refresh</button></div>
        <pre>${esc(JSON.stringify(s.tools || [], null, 2))}</pre>`;
      div.querySelector("button").onclick = async () => {
        try {
          const item = await api.refreshMcp(s.id);
          toast("已刷新 " + item.status);
          await renderCatalog();
        } catch (e) {
          toast(e.message, "err");
        }
      };
      mcp.appendChild(div);
    }
    applyRoleUi();
  }


  function renderSkills(selector, type, selected=[]) {
    $(selector).innerHTML = state.skills.map(skill => {
      const disabled = !(skill.applicableTypes||[]).includes(type) || !api.can("editor") || skill.enabled===false;
      return '<label class="tool-item"><input type="checkbox" value="'+esc(skill.code)+'" '+(selected.includes(skill.code)&&!disabled?'checked':'')+' '+(disabled?'disabled':'')+'><span><strong>'+esc(skill.name)+'</strong><small>'+esc(skill.description)+' · 适用 '+esc(skill.applicableTypes.join('/'))+'</small></span></label>';
    }).join("");
    if(selector==="#skillsBox")for(const skill of state.skills){const resource=state.skillResources.find(r=>r.code===skill.code);const pinned=state.draft.skillVersions?.[skill.code]||resource?.publishedVersion||1;const row=$(selector).querySelector('input[value="'+CSS.escape(skill.code)+'"]')?.closest('label');if(row&&selected.includes(skill.code)){const note=document.createElement('small');note.textContent='绑定 v'+pinned+' · 最新 v'+(resource?.publishedVersion||pinned);row.append(note);if(resource?.publishedVersion>pinned){const b=document.createElement('button');b.type='button';b.className='secondary';b.textContent='查看版本差异 / 接受更新';b.onclick=async e=>{e.preventDefault();const history=await api.resourceVersions(resource.id);const versions=history.items||[];const old=versions.find(v=>v.version===pinned);if(await ResourceUi.ask('技能更新只进入 Agent 草稿，重新发布后生效。请核对新增工具权限。\n'+simpleDiff(old?.definition?.config||old?.snapshot||old,resource.published?.config||resource.config))){state.draft.skillVersions={...state.draft.skillVersions,[skill.code]:resource.publishedVersion};markDirty();renderSkills(selector,type,selected)}};row.append(b)}}}
  }
  async function openCreate() {
    try {
      [state.tools,state.skills,state.agents] = await Promise.all([api.catalogTools(),api.catalogSkills(),api.listAgents({})]).then(a=>a.map(x=>x.items||[]));
      state.models=await publishedResources("MODEL");
      state.skillResources=await publishedResources("SKILL");
      renderModelSelector("#cModel", "");
      ["cCode","cName","cDesc","cPrompt"].forEach(id=>$("#"+id).value="");
      $("#cType").value="WORKER";
      $("#cTools").innerHTML=""; $("#cSkills").innerHTML="";
      $("#createError").classList.add("hidden");
      renderCreateOptions();
      $("#createModal").classList.remove("hidden");
    } catch(e) { toast(e.message,"err"); }
  }
  function renderCreateOptions() {
    const type=$("#cType").value;
    const selected=$$("#cTools input:checked").map(x=>x.value);
    const selectedSkills=$$("#cSkills input:checked").map(x=>x.value);
    $("#cTools").innerHTML=state.tools.map(t=>'<label class="tool-item"><input type="checkbox" value="'+esc(t.code)+'" '+(selected.includes(t.code)&&!(type==="SUPERVISOR"&&t.sideEffect==="WRITE")?'checked':'')+' '+(type==="SUPERVISOR"&&t.sideEffect==="WRITE"?'disabled':'')+'><span><strong>'+esc(t.name)+'</strong> '+esc(t.sideEffect)+'<small>'+esc(t.description)+'</small></span></label>').join("");
    renderSkills("#cSkills",type,selectedSkills);
    $("#cChildrenWrap").classList.toggle("hidden",type!=="SUPERVISOR");
    $("#cChildren").innerHTML = type==="SUPERVISOR" ? state.agents.filter(a=>a.type==="WORKER"&&a.enabled&&a.publishedVersion).map(a=>'<label class="check-row"><input type="checkbox" value="'+esc(a.code)+'"><span>'+esc(a.name)+'<small>'+esc(a.description)+'</small></span></label>').join("") : "";
  }
  function bind() {
    $("#roleSelect").onchange = () => {
      api.setRole($("#roleSelect").value);
      applyRoleUi();
      toast("角色: " + api.getRole());
      if (state.view === "agents") renderList();
      if (state.view === "monitor") MonitorUi.render(parseHash().code);
      if (state.view === "support") SupportUi.render(parseHash().code);
      if (["models","tools","mcp","skills"].includes(state.view)) ResourceUi.render(state.view);
    };
    $$(".nav button").forEach((b) => {
      b.onclick = () => navigate("#/" + b.dataset.view);
    });
    $("#btnReloadList").onclick = () => renderList();
    ["filterType", "filterStatus", "filterEnabled"].forEach((id) => {
      $( "#" + id).onchange = () => renderList();
    });
    $("#filterQ").oninput = () => {
      clearTimeout(window.__qT);
      window.__qT = setTimeout(() => renderList(), 250);
    };
    $("#btnCreate").onclick = openCreate;
    $("#cType").onchange = renderCreateOptions;
    $("#createCancel").onclick = () => $("#createModal").classList.add("hidden");
    $("#createOk").onclick = async () => {
      try {
        $("#createOk").disabled = true;
        $("#createError").classList.add("hidden");
        const body = {
          code: $("#cCode").value.trim(),
          name: $("#cName").value.trim(),
          type: $("#cType").value,
          description: $("#cDesc").value.trim(),
        };
        body.definition = defaultDraft(body.code, body.name, body.type, body.description);
        body.definition.prompts.systemPrompt = $("#cPrompt").value;
        body.definition.modelConfig.resourceId=$("#cModel").value||null;
        body.definition.tools = $$("#cTools input:checked").map(x=>x.value);
        body.definition.skills = $$("#cSkills input:checked").map(x=>x.value);
        body.definition.skillVersions=Object.fromEntries(body.definition.skills.map(code=>[code,state.skillResources.find(r=>r.code===code)?.publishedVersion||1]));
        body.definition.children = body.type === "SUPERVISOR" ? $$("#cChildren input:checked").map(x => ({agentCode:x.value, whenToUse:state.agents.find(a=>a.code===x.value)?.description || "", enabled:true})) : [];
        if (!body.name || !body.definition.prompts.systemPrompt.trim()) throw new Error("请填写名称和提示词");
        const detail = await api.createAgent(body);
        $("#createModal").classList.add("hidden");
        toast("已创建，启用后生效");
        navigate("#/agents/" + detail.draft.code);
      } catch (e) {
        const errors = e.data?.errors?.map(x=>x.message).join("；") || e.message;
        $("#createError").textContent = errors;
        $("#createError").classList.remove("hidden");
        toast(errors, "err");
      } finally { $("#createOk").disabled=false; }
    };
    $$(".tabs [data-tab]").forEach((b) => {
      b.onclick = () => {
        if (b.dataset.tab === "children") collectChildrenSilent();
        if (b.dataset.tab === "policies") {
          try {
            collectDraftFromForm();
          } catch (_) {}
          renderPolicies();
        }
        if (b.dataset.tab === "publish") {
          try {
            collectChildrenSilent();
            state.draft = collectDraftFromForm();
          } catch (_) {}
          renderPublishTab();
        }
        setEditorTab(b.dataset.tab);
      };
    });
    $("#fTemp").oninput = () => {
      $("#fTempVal").textContent = $("#fTemp").value;
      markDirty();
    };
    $("#fOutputMode").onchange = () => {
      $("#schemaWrap").classList.toggle("hidden", $("#fOutputMode").value !== "JSON_SCHEMA");
      markDirty();
    };
    $("#btnBackList").onclick = () => navigate("#/agents");
    $("#btnSave").onclick = saveDraft;
    $("#btnValidate").onclick = runValidate;
    $("#btnPublish").onclick = runPublish;
    $("#btnTrialJump").onclick = () =>
      navigate("#/playground?code=" + encodeURIComponent(state.draft.code));
    $("#btnPreview").onclick = localPreview;
    $("#btnAddChild").onclick = () => {
      collectChildrenSilent();
      const workers = state.agents.filter((a) => a.type === "WORKER" && a.status === "PUBLISHED" && a.enabled);
      if (!workers.length) return toast("没有已发布的 WORKER", "err");
      state.draft.children = state.draft.children || [];
      state.draft.children.push({
        agentCode: workers[0].code,
        alias: workers[0].name,
        whenToUse: "",
        priority: 10,
        enabled: true,
      });
      markDirty();
      renderChildren();
    };
    $("#btnPgRun").onclick = runTrial;
    $("#pgMode").onchange = () => { if ($("#pgMode").value === "full_route") $("#pgUseDraft").checked = false; };
    window.addEventListener("hashchange", route);
  }

  async function publishedResources(kind){const rows=(await api.resources(kind)).items||[];return Promise.all(rows.map(async r=>{if(!r.publishedVersion)return r;const detail=await api.resource(r.id);return {...r,config:detail.published?.config||r.config}}));}
  function renderModelSelector(selector,value){const enabled=state.models.filter(m=>m.enabled);$(selector).innerHTML='<option value="">使用系统默认模型</option>'+enabled.map(m=>'<option value="'+esc(m.id)+'">'+esc(m.name+' / '+m.config.provider+' / '+m.config.model)+'</option>').join('');if(value&&!enabled.some(m=>m.id===value))$(selector).insertAdjacentHTML('beforeend','<option value="'+esc(value)+'">'+esc(value)+'（当前不可用，发布需修复）</option>');$(selector).value=value;if(selector==='#fModel')effectiveModel();}
  function effectiveModel(){const m=state.models.find(r=>$('#fModel').value?r.id===$('#fModel').value:r.isDefault);$('#effectiveModel').textContent=m?'实际使用：'+m.name+' / '+m.config.provider+' / '+m.config.model+' · 发布 v'+m.publishedVersion+' · 默认参数 '+JSON.stringify(m.config.parameters||{}):'未找到可用默认模型，请到模型页面配置。';$('#capabilitySummary').textContent='技能依赖与直接工具合并去重；全部写操作仍需确认。Agent 仅继承模型已发布参数，修改模型资源草稿不会立即影响运行。';}
  async function init() {
    bind();
    applyRoleUi();
    await refreshRuntimeBadge();
    await route();
  }

  init();
})();
