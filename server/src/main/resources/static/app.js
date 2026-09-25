// Keyboard shortcuts (ignored while typing): n = quick add, g then d/a/t = Doing/Active/All, ? = help.
// Plus the All tree's outliner, and small behaviours for htmx fragments, the editor's pickers and
// the undo toast.
(() => {
  let pendingG = false;
  const go = { d: "/doing", a: "/active", t: "/all" };
  const help = "n  quick add\ng d / g a / g t  Doing / Active / All\nEsc  leave the text box\n\n" +
    "All tree (click a row first):\n↑ ↓  move between rows    ← →  fold / unfold\nEnter  new task below    Tab / Shift-Tab  indent / outdent\n" +
    "Space  complete    Alt-↑ / Alt-↓  move up / down\ne  edit    s  star\nDrag a row: top or bottom edge to reorder, middle to nest";
  document.addEventListener("keydown", (e) => {
    const typing = e.target.closest("input, textarea, select");
    if (e.key === "Escape" && typing) { e.target.blur(); return; }
    if (typing || e.ctrlKey || e.metaKey) return;
    const node = e.target.closest(".node");
    if (node && outlineKey(e, node)) { e.preventDefault(); return; }
    if (e.altKey) return;
    if (pendingG && go[e.key]) { location.href = go[e.key]; pendingG = false; return; }
    pendingG = e.key === "g";
    if (e.key === "n") { e.preventDefault(); document.getElementById("quickadd")?.focus(); }
    if (e.key === "?") alert(help);
  });

  // --- All-tree outliner. Every action re-renders #list; focus then returns to the same row (or the
  // row the server names in X-Focus, e.g. a task just created with Enter).
  let focusId = null;
  let dragId = null;
  const rows = () => [...document.querySelectorAll("#list .node")];
  const post = (url, values = {}) => htmx.ajax("POST", url, { target: "#list", swap: "outerHTML", values });
  const toggle = (id) => htmx.ajax("GET", `/list/all?toggle=${id}`, { target: "#list", swap: "outerHTML" });

  function outlineKey(e, node) {
    const list = rows(), i = list.indexOf(node), id = node.dataset.id;
    if (e.altKey && (e.key === "ArrowUp" || e.key === "ArrowDown")) { post(`/outline/${id}/${e.key === "ArrowUp" ? "up" : "down"}`); return true; }
    if (e.altKey) return false;
    switch (e.key) {
      case "ArrowUp": list[i - 1]?.focus(); return true;
      case "ArrowDown": list[i + 1]?.focus(); return true;
      case "ArrowLeft":
        if (node.dataset.collapsed === "false") toggle(id);
        else list.find((r) => r.dataset.id === node.dataset.parent)?.focus();
        return true;
      case "ArrowRight": if (node.dataset.collapsed === "true") toggle(id); return true;
      case "Tab": post(`/outline/${id}/${e.shiftKey ? "outdent" : "indent"}`); return true;
      case " ":
        if (node.dataset.kind !== "task") return true;
        focusId = (list[i + 1] ?? list[i - 1])?.dataset.id; // the completed row disappears
        post(`/tasks/${id}/complete?mode=ALL`);
        return true;
      case "Enter": newTask(node, list); return true;
      case "e": location.href = `/tasks/${id}?mode=ALL`; return true;
      case "s": post(`/tasks/${id}/star?mode=ALL`); return true;
    }
    return false;
  }

  // Enter: a text box below the row (and its unfolded children); typing a task and Enter adds it as
  // the next sibling, Esc cancels.
  function newTask(node, list) {
    document.querySelector(".outline-new")?.remove();
    let last = node;
    for (const r of list.slice(list.indexOf(node) + 1)) {
      if (+r.dataset.depth <= +node.dataset.depth) break;
      last = r;
    }
    const input = document.createElement("input");
    input.className = "outline-new";
    input.placeholder = "New task…";
    input.style.marginLeft = `${+node.dataset.depth * 18 + 32}px`;
    last.after(input);
    input.focus();
    input.addEventListener("keydown", (e) => {
      if (e.key === "Enter" && input.value.trim()) post(`/outline/${node.dataset.id}/sibling`, { text: input.value });
      if (e.key === "Escape") { input.remove(); node.focus(); }
    });
    input.addEventListener("blur", () => setTimeout(() => input.remove(), 200));
  }

  document.addEventListener("focusin", (e) => { const n = e.target.closest(".node"); if (n) focusId = n.dataset.id; });
  // The list's periodic refresh would wipe a half-typed task or a drag in progress; skip it then.
  document.addEventListener("htmx:beforeRequest", (e) => {
    const refresh = e.detail.requestConfig.verb === "get" && e.detail.requestConfig.path === "/list/all";
    if (refresh && (dragId || document.querySelector(".outline-new"))) e.preventDefault();
  });
  document.addEventListener("htmx:afterSwap", (e) => {
    if (!document.querySelector("#list.outline") || document.activeElement !== document.body) return;
    const id = e.detail.xhr?.getResponseHeader("X-Focus") || focusId;
    if (id) document.querySelector(`#list .node[data-id="${id}"]`)?.focus();
  });

  // Drag a row onto another: the top or bottom quarter drops it before/after, the middle nests it.
  const zoneOf = (n, y) => {
    const r = n.getBoundingClientRect(), q = r.height / 4;
    return y < r.top + q ? "before" : y > r.bottom - q ? "after" : "into";
  };
  const clearDrop = () => document.querySelectorAll("[data-drop]").forEach((n) => delete n.dataset.drop);
  document.addEventListener("dragstart", (e) => {
    const n = e.target.closest?.(".node");
    if (!n) return;
    dragId = n.dataset.id;
    e.dataTransfer.effectAllowed = "move";
  });
  document.addEventListener("dragover", (e) => {
    const n = e.target.closest?.(".node");
    if (!dragId || !n || n.dataset.id === dragId) return;
    e.preventDefault();
    clearDrop();
    n.dataset.drop = zoneOf(n, e.clientY);
  });
  document.addEventListener("drop", (e) => {
    const n = e.target.closest?.(".node");
    if (!dragId || !n || !n.dataset.drop) return;
    e.preventDefault();
    focusId = dragId;
    post(`/outline/${dragId}/move`, { target: n.dataset.id, zone: n.dataset.drop });
    clearDrop();
    dragId = null;
  });
  document.addEventListener("dragend", () => { dragId = null; clearDrop(); });

  // --- Bulk edit. "Select" makes clicks in the list pick task rows (folders and projects are skipped,
  // as on the phone); "Edit selected" opens the bulk editor. Captured before htmx sees the click, so
  // a picked row's checkbox or star doesn't fire.
  const selected = new Set();
  const selecting = () => document.body.classList.contains("selecting");
  const paintSelection = () => {
    document.querySelectorAll("#list .row[data-task-id]").forEach((r) => r.classList.toggle("selected", selected.has(r.dataset.taskId)));
    const tools = document.querySelector(".list-tools");
    if (!tools) return;
    tools.querySelector(".selection-count").textContent = selecting() ? `${selected.size} selected` : "";
    tools.querySelector(".select-toggle").textContent = selecting() ? "Cancel" : "Select";
    tools.querySelector(".bulk-edit").disabled = selected.size === 0;
  };
  document.addEventListener("click", (e) => {
    if (e.target.closest("#toast .dismiss")) { document.getElementById("toast").classList.remove("show"); return; }
    if (e.target.closest(".select-toggle")) {
      document.body.classList.toggle("selecting");
      selected.clear();
      paintSelection();
      return;
    }
    if (e.target.closest(".bulk-edit")) {
      location.href = `/bulk?ids=${[...selected].join(",")}&mode=${document.querySelector(".list-tools").dataset.mode}`;
      return;
    }
    if (!selecting() || !e.target.closest("#list")) return;
    e.preventDefault();
    e.stopPropagation();
    const row = e.target.closest(".row[data-type='TASK']");
    if (!row) return;
    const id = row.dataset.taskId;
    if (!selected.delete(id)) selected.add(id);
    paintSelection();
  }, true);
  document.addEventListener("htmx:afterSwap", paintSelection);

  // Clear quick add after a successful add (here rather than in an inline hx-on handler, so the
  // Content-Security-Policy can forbid inline script).
  document.addEventListener("htmx:afterRequest", (e) => {
    if (e.detail.successful && e.detail.elt.matches("form.quickadd")) e.detail.elt.reset();
  });

  // Filter a picker's checkbox list by its search box.
  document.addEventListener("input", (e) => {
    if (!e.target.matches("[data-filter]")) return;
    const q = e.target.value.toLowerCase();
    e.target.closest(".picker").querySelectorAll(".options label").forEach((l) => {
      l.hidden = !l.textContent.toLowerCase().includes(q);
    });
  });

  // The undo toast hides a few seconds after it appears, whether swapped in or rendered with the page.
  let hideTimer;
  const hideSoon = (toast) => {
    clearTimeout(hideTimer);
    hideTimer = setTimeout(() => toast.classList.remove("show"), 6000);
  };
  // A question (the "open subtasks" one) stays until it's answered.
  document.addEventListener("htmx:oobAfterSwap", (e) => {
    const toast = e.detail.target;
    if (toast.id === "toast" && !toast.classList.contains("question")) hideSoon(toast);
    else if (toast.id === "toast") clearTimeout(hideTimer);
  });
  document.addEventListener("DOMContentLoaded", () => {
    const toast = document.getElementById("toast");
    if (toast?.classList.contains("show")) hideSoon(toast);
  });
})();
