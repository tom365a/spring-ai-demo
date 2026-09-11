(function (global) {
  const API = "";

  class ApiError extends Error {
    constructor(code, message, data, httpStatus) {
      super(message || "request failed");
      this.code = code;
      this.data = data;
      this.httpStatus = httpStatus;
    }
  }

  function getRole() {
    return sessionStorage.getItem("adminRole") || "viewer";
  }

  function setRole(role) {
    sessionStorage.setItem("adminRole", role || "viewer");
  }

  function rank(role) {
    switch ((role || "").toLowerCase()) {
      case "publisher":
      case "admin":
        return 3;
      case "editor":
        return 2;
      case "viewer":
        return 1;
      default:
        return 0;
    }
  }

  function can(minRole) {
    return rank(getRole()) >= rank(minRole);
  }

  async function api(path, options = {}) {
    const opts = options || {};
    const headers = Object.assign(
      { "X-Admin-Role": getRole() },
      opts.headers || {}
    );
    let body = opts.body;
    if (body != null && typeof body !== "string") {
      headers["Content-Type"] = "application/json";
      body = JSON.stringify(body);
    }
    const res = await fetch(API + path, {
      method: opts.method || "GET",
      headers,
      body,
    });
    let json = null;
    try {
      json = await res.json();
    } catch {
      throw new ApiError(50001, "invalid json response", null, res.status);
    }
    if (!res.ok || (json && json.code != null && json.code !== 0)) {
      throw new ApiError(
        json && json.code != null ? json.code : res.status,
        (json && json.message) || res.statusText,
        json && json.data,
        res.status
      );
    }
    return json.data !== undefined ? json.data : json;
  }

  global.AdminApi = {
    ApiError,
    getRole,
    setRole,
    can,
    api,
    listAgents: (q) => {
      const p = new URLSearchParams();
      if (q?.type) p.set("type", q.type);
      if (q?.status) p.set("status", q.status);
      if (q?.enabled != null && q.enabled !== "") p.set("enabled", q.enabled);
      if (q?.q) p.set("q", q.q);
      const s = p.toString();
      return api("/api/v1/admin/agents" + (s ? "?" + s : ""));
    },
    getAgent: (code) => api("/api/v1/admin/agents/" + encodeURIComponent(code)),
    createAgent: (body) => api("/api/v1/admin/agents", { method: "POST", body }),
    updateAgent: (code, body) =>
      api("/api/v1/admin/agents/" + encodeURIComponent(code), {
        method: "PUT",
        body,
      }),
    enable: (code) => api("/api/v1/admin/agents/"+encodeURIComponent(code)+"/enable", {method:"POST"}),
    disable: (code) => api("/api/v1/admin/agents/"+encodeURIComponent(code)+"/disable", {method:"POST"}),
    runtime: () => api("/api/v1/agents/runtime"),
    catalogSkills: () => api("/api/v1/admin/catalog/skills"),
    validate: (code) =>
      api("/api/v1/admin/agents/" + encodeURIComponent(code) + "/validate", {
        method: "POST",
      }),
    publish: (code, remark) =>
      api("/api/v1/admin/agents/" + encodeURIComponent(code) + "/publish", {
        method: "POST",
        body: { remark: remark || "" },
      }),
    rollback: (code, version, remark) =>
      api("/api/v1/admin/agents/" + encodeURIComponent(code) + "/rollback", {
        method: "POST",
        body: { version, remark: remark || "" },
      }),
    versions: (code) =>
      api("/api/v1/admin/agents/" + encodeURIComponent(code) + "/versions"),
    version: (code, ver) =>
      api(
        "/api/v1/admin/agents/" +
          encodeURIComponent(code) +
          "/versions/" +
          ver
      ),
    trial: (code, body) =>
      api("/api/v1/admin/agents/" + encodeURIComponent(code) + "/trial", {
        method: "POST",
        body,
      }),
    catalogTools: () => api("/api/v1/admin/catalog/tools"),
    catalogMcp: () => api("/api/v1/admin/catalog/mcp-servers"),
    refreshMcp: (id) =>
      api("/api/v1/admin/catalog/mcp-servers/" + encodeURIComponent(id) + "/refresh", {
        method: "POST",
      }),
    resources: (kind) => api('/api/v1/admin/resources?kind='+encodeURIComponent(kind)),
    resource: (id) => api('/api/v1/admin/resources/'+encodeURIComponent(id)),
    saveResource: (id,body) => api('/api/v1/admin/resources'+(id?'/'+encodeURIComponent(id):''),{method:id?'PUT':'POST',body}),
    resourceAction: (id,action,body={}) => api('/api/v1/admin/resources/'+encodeURIComponent(id)+'/'+action,{method:'POST',body}),
    resourceImpact: (id,action,version) => api('/api/v1/admin/resources/'+encodeURIComponent(id)+'/impact?action='+encodeURIComponent(action)+(version!=null?'&version='+version:'')),
    resourceVersions: (id) => api('/api/v1/admin/resources/'+encodeURIComponent(id)+'/versions'),
    resourceReferences: (id) => api('/api/v1/admin/resources/'+encodeURIComponent(id)+'/references'),
    deleteResource: (id) => api('/api/v1/admin/resources/'+encodeURIComponent(id),{method:'DELETE'}),
    confirmAction: (id,sessionId,confirm) => api('/api/v1/confirmations/'+encodeURIComponent(id),{method:'POST',body:{sessionId,confirm}}),
    debugConfig: () => api("/api/v1/debug/config"),
  };
})(window);
