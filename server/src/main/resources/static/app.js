// Keyboard shortcuts (ignored while typing): n = quick add, g then d/a/t = Doing/Active/All, ? = help.
// Plus the All tree's outliner, the task editor's pills, timed tasks, bulk selection, and small
// behaviours for htmx fragments and the undo toast.
(() => {
  let pendingG = false;
  const go = { d: "/doing", a: "/active", t: "/all" };
  const help = "n  jump to quick add\ng, then d / a / t  go to Doing / Active / All\nEsc  leave a text box, close the task panel, or leave focus\n\n" +
    "All tree (click a row first):\n↑ ↓  move between rows    ← →  fold / unfold\nEnter  new task below    Tab / Shift-Tab  indent / outdent\n" +
    "Space  complete    Alt-↑ / Alt-↓  move up / down\ne  edit    s  star    f  focus\nDrag a row: top or bottom edge to reorder, middle to nest\n\n" +
    "Review:\n← →  earlier / later    − / +  zoom out / in\n\n" +
    "z  zoom into the folder on screen (folder mode), or out of the mode";
  document.addEventListener("keydown", (e) => {
    const typing = e.target.closest("input, textarea, select");
    if (e.key === "Escape" && typing) { e.target.blur(); return; }
    // Esc leaves focus (every device's, and unpins).
    if (e.key === "Escape" && !typing) { const leave = document.querySelector("#focus .leave"); if (leave) { leave.click(); return; } }
    // Esc closes the task panel, back to the list alone.
    if (e.key === "Escape" && !typing) { const close = document.querySelector("#detail .detail-close"); if (close) { location.href = close.href; return; } }
    if (typing || e.ctrlKey || e.metaKey) return;
    const node = e.target.closest(".node");
    if (node && outlineKey(e, node)) { e.preventDefault(); return; }
    if (e.altKey) return;
    // Keys marked on the page with data-key: Review's ← → (step) and − / + (zoom); z, folder mode.
    const keyed = document.querySelector(`[data-key="${e.key === "=" ? "+" : e.key}"]`);
    if (keyed) { if (keyed.href) location.href = keyed.href; else keyed.click(); return; }
    if (pendingG && go[e.key]) { location.href = go[e.key]; pendingG = false; return; }
    pendingG = e.key === "g";
    if (e.key === "n") { e.preventDefault(); document.getElementById("quickadd")?.focus(); }
    if (e.key === "?") alert(help);
  });

  // --- Notes (the editor's title card). The preview shows 5 lines (2 on a phone) with "more" when
  // there's more; clicking it (not a link) edits the whole note, and leaving the field shows the
  // preview again, rebuilt from what's typed.
  const linkify = (el, text) => {
    el.replaceChildren();
    let at = 0;
    for (const m of text.matchAll(/https?:\/\/\S+/g)) {
      const url = m[0].replace(/[.,);:!?]+$/, "");
      el.append(text.slice(at, m.index));
      const a = Object.assign(document.createElement("a"), { href: url, target: "_blank", rel: "noopener", textContent: url });
      el.append(a);
      at = m.index + url.length;
    }
    el.append(text.slice(at));
  };
  const markMore = () => document.querySelectorAll(".notes").forEach((n) => {
    const p = n.querySelector(".notes-preview");
    n.querySelector(".more")?.remove();
    if (p && p.scrollHeight > p.clientHeight + 1) p.after(Object.assign(document.createElement("div"), { className: "more", textContent: "more" }));
  });
  document.addEventListener("DOMContentLoaded", markMore);
  document.addEventListener("htmx:afterSettle", markMore);
  window.addEventListener("resize", markMore);
  // Where in the preview's text a click landed, or null unless it's on the text itself (not the space
  // beside a line or under the last one).
  const offsetAt = (el, x, y) => {
    const pos = document.caretPositionFromPoint?.(x, y);
    const range = document.caretRangeFromPoint?.(x, y);
    const node = pos ? pos.offsetNode : range?.startContainer, at = pos ? pos.offset : range?.startOffset;
    if (!node || node.nodeType !== Node.TEXT_NODE || !el.contains(node) || !node.length) return null;
    const char = document.createRange();
    const i = Math.min(at, node.length - 1);
    char.setStart(node, i);
    char.setEnd(node, i + 1);
    const box = char.getBoundingClientRect();
    if (y < box.top || y > box.bottom || x < box.left - 12 || x > box.right + 12) return null;
    let total = 0;
    const walk = document.createTreeWalker(el, NodeFilter.SHOW_TEXT);
    for (let n; (n = walk.nextNode()); ) {
      if (n === node) return total + at;
      total += n.length;
    }
    return null;
  };
  document.addEventListener("click", (e) => {
    const notes = e.target.closest(".notes");
    if (!notes || e.target.closest("a") || !e.target.closest(".notes-preview, .more")) return;
    const preview = notes.querySelector(".notes-preview");
    const clicked = e.target.closest(".notes-preview") ? offsetAt(preview, e.clientX, e.clientY) : null;
    notes.classList.add("editing");
    // The cursor where the text was clicked; anywhere else, at the end, ready to add to the note.
    const input = notes.querySelector(".notes-input");
    const at = Math.min(clicked ?? input.value.length, input.value.length);
    input.focus();
    input.setSelectionRange(at, at);
  });
  // Clicking anywhere else leaves the note (even where a click doesn't move focus, as on some
  // WebKit buttons), which folds it back into its preview below.
  document.addEventListener("pointerdown", (e) => {
    const open = document.querySelector(".notes.editing");
    if (open && !open.contains(e.target)) open.querySelector(".notes-input").blur();
  });
  document.addEventListener("focusout", (e) => {
    if (!e.target.matches(".notes-input")) return;
    const notes = e.target.closest(".notes"), text = e.target.value.trim();
    let p = notes.querySelector(".notes-preview");
    if (!text) { p?.remove(); notes.classList.remove("editing"); return; }
    if (!p) { p = Object.assign(document.createElement("div"), { className: "notes-preview" }); notes.prepend(p); }
    linkify(p, text);
    notes.classList.remove("editing");
    markMore();
  });

  // Settings save as soon as a value is picked (the CSP forbids inline handlers, hence here).
  document.addEventListener("change", (e) => { if (e.target.matches("form.settings select, form.settings input")) e.target.form.submit(); });

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
      case "f": htmx.ajax("POST", `/focus/start?task=${id}`, { swap: "none" }); return true;
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

  // The focus picker's search: opening it puts the cursor in the box.
  document.addEventListener("toggle", (e) => {
    if (e.target.matches?.(".focus-search") && e.target.open) e.target.querySelector("input")?.focus();
  }, true);
  document.addEventListener("focusin", (e) => { const n = e.target.closest(".node"); if (n) focusId = n.dataset.id; });
  // The list's periodic refresh would wipe a half-typed task or a drag in progress; skip it then.
  document.addEventListener("htmx:beforeRequest", (e) => {
    const refresh = e.detail.requestConfig.verb === "get" && e.detail.requestConfig.path === "/list/all";
    if (refresh && (dragId || document.querySelector(".outline-new"))) e.preventDefault();
  });
  // The lists and the focus screen refresh themselves, but not in a hidden tab (hx-trigger's own
  // [condition] filters need eval, which the CSP forbids).
  document.addEventListener("htmx:beforeRequest", (e) => {
    const { verb, path } = e.detail.requestConfig;
    if (document.hidden && verb === "get" && /^\/(list\/|focus\/body)/.test(path)) e.preventDefault();
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
    e.dataTransfer.setData("text/plain", n.dataset.id); // Firefox won't start a drag without data
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

  // --- Bulk edit. "Select" makes clicks in the list pick tasks and checklists (folders and projects
  // are skipped, as on the phone); "Edit selected" opens the bulk editor. Captured before htmx sees the click, so
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
    const row = e.target.closest(".row[data-type='TASK'], .row[data-type='CHECKLIST']");
    if (!row) return;
    const id = row.dataset.taskId;
    if (!selected.delete(id)) selected.add(id);
    paintSelection();
  }, true);
  document.addEventListener("htmx:afterSwap", paintSelection);

  // Rows work like the phone's. Right-click (a long press on a touchscreen) opens the row's menu at the
  // pointer: snooze, pin, focus, a subtask. A click anywhere else on a row opens the task in the side
  // panel; the parent's name, buttons and links keep their own. (In selection mode the handler above
  // takes clicks first.)
  const closeMenus = (except) => {
    document.querySelectorAll("details.more[open]").forEach((d) => { if (d !== except) d.open = false; });
    document.querySelectorAll(".pin-control.open").forEach((p) => { if (p !== except) p.classList.remove("open"); });
  };
  const placeAt = (menu, e) => {
    menu.style.left = `${Math.max(8, Math.min(e.clientX, innerWidth - menu.offsetWidth - 8))}px`;
    menu.style.top = `${Math.max(8, Math.min(e.clientY, innerHeight - menu.offsetHeight - 8))}px`;
  };
  document.addEventListener("contextmenu", (e) => {
    // The pin's own menu: focus, the timer.
    const pin = e.target.closest(".pin-control");
    if (pin) {
      e.preventDefault();
      closeMenus(pin);
      pin.classList.add("open");
      placeAt(pin.querySelector(".pin-menu"), e);
      return;
    }
    const more = e.target.closest(".row[data-task-id]")?.querySelector("details.more");
    if (!more || e.target.closest("input, textarea")) return;
    e.preventDefault();
    closeMenus(more);
    more.open = true;
    placeAt(more.querySelector(".menu"), e);
  });
  document.addEventListener("click", (e) => {
    if (!e.target.closest("details.more") || e.target.closest(".pin-menu")) closeMenus();
    if (e.target.closest("a, button, input, textarea, label, details, select")) return;
    e.target.closest("#list .row[data-task-id]")?.querySelector(".title a.edit")?.click();
  });
  document.addEventListener("keydown", (e) => { if (e.key === "Escape") closeMenus(); });

  // The row whose task is open in the panel stays highlighted, also after the list refreshes itself
  // or another task is opened in the panel (the URL is the task's).
  const markCurrent = () => {
    const id = location.pathname.match(/^\/tasks\/(\d+)/)?.[1];
    document.querySelectorAll("#list .row, #list .folder").forEach((r) =>
      r.classList.toggle("current", !!id && (r.dataset.taskId ?? r.dataset.id) === id));
  };
  document.addEventListener("htmx:afterSettle", markCurrent);
  window.addEventListener("popstate", () => setTimeout(markCurrent));

  // --- Timed tasks. The timer is the current task's, on the server, shared with every device (the
  // phone, the top bar): this shows it (the bar, the running row's play button, the sidebar's Now strip),
  // ticks it down, and sends Start / Pause / Resume / Stop / more time back. It's re-read every 30s and
  // when the tab comes back, so changes elsewhere show up. At zero it asks whether the task is done.
  let timer = null, skew = 0, askMore = false, alerted = null;
  const now = () => Date.now() + skew; // the server's clock, which the phone's alarm also counts to
  const leftOf = (t) => (t.endsAt ? t.endsAt - now() : t.remaining);
  const over = (t) => !!t?.endsAt && leftOf(t) <= 0;
  const clock = (ms) => {
    const s = Math.max(0, Math.ceil(ms / 1000)), mm = String(Math.floor(s / 60) % 60).padStart(2, "0"), ss = String(s % 60).padStart(2, "0");
    return s >= 3600 ? `${Math.floor(s / 3600)}:${mm}:${ss}` : `${Math.floor(s / 60)}:${ss}`;
  };
  const esc = (s) => s.replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" })[c]);
  const baseTitle = document.title;
  const takeTimer = async (res) => {
    const body = await res.json();
    skew = body.now - Date.now();
    if (timer?.endsAt !== body.timer?.endsAt) askMore = false;
    timer = body.timer;
    renderTimer();
  };
  const syncTimer = () => fetch("/timer").then(takeTimer).catch(() => {});
  // After an action, the list (or focus screen) catches up too: a task done, or a pin moved.
  const timerPost = (path) => fetch(path, { method: "POST" }).then(takeTimer).then(() => htmx.trigger(document.body, "refresh")).catch(() => {});

  function renderTimer() {
    const t = timer, bar = document.getElementById("timer");
    // A row's timer pill shows its length ("1h"), or the time left while this task's timer is on.
    document.querySelectorAll(".play").forEach((b) => {
      const mine = !!t && !over(t) && t.id === b.dataset.taskId;
      b.classList.toggle("running", mine && !!t.endsAt);
      b.querySelector(".play-time").textContent = mine ? clock(leftOf(t)) : duration(+b.dataset.minutes);
    });
    document.querySelectorAll(".now-timer").forEach((el) => { el.textContent = t ? (over(t) ? "time's up" : clock(leftOf(t)) + (t.endsAt ? "" : " ⏸")) : ""; });
    document.title = over(t) ? `⏰ ${baseTitle}` : baseTitle;
    if (!bar) return;
    bar.hidden = !t;
    if (!t) return;
    if (over(t) && askMore) {
      bar.innerHTML = `<span>How much more time?</span>` +
        [5, 10, 15, 30, 60].map((m) => `<button data-timer="add" data-minutes="${m}">+${m < 60 ? m + "m" : "1h"}</button>`).join("") +
        `<input type="number" min="1" placeholder="min" class="timer-custom"><button data-timer="add">Start</button>`;
    } else if (over(t)) {
      bar.innerHTML = `<span>Time's up: <b>${esc(t.title)}</b>. Is it done?</span><button data-timer="done" class="primary">Done</button><button data-timer="more">Not yet</button>`;
    } else {
      bar.innerHTML = `<span class="timer-title">${esc(t.title)}</span><span class="timer-left">${clock(leftOf(t))}</span>` +
        `<button data-timer="${t.endsAt ? "pause" : "resume"}">${t.endsAt ? "Pause" : "Resume"}</button><button data-timer="stop">Stop</button>`;
    }
  }

  // At zero: ask, with a notification (if allowed) and a few beeps, since the tab may be in the
  // background. Once per timer end, and not for one long over by the time this page saw it.
  function timeUp(t) {
    if (alerted === t.endsAt) return;
    alerted = t.endsAt;
    renderTimer();
    if (leftOf(t) < -60000) return;
    try { if (window.Notification?.permission === "granted") new Notification("Time's up", { body: t.title }); } catch {}
    try {
      const audio = new AudioContext();
      [0, 0.4, 0.8].forEach((at) => {
        const beep = audio.createOscillator();
        beep.frequency.value = 880;
        beep.connect(audio.destination);
        beep.start(audio.currentTime + at);
        beep.stop(audio.currentTime + at + 0.2);
      });
    } catch {}
  }

  setInterval(() => {
    if (!timer?.endsAt) return;
    if (over(timer)) timeUp(timer);
    else document.querySelectorAll("#timer .timer-left, .play.running .play-time, .now-timer").forEach((el) => el.replaceChildren(clock(leftOf(timer))));
  }, 1000);
  setInterval(() => { if (document.visibilityState === "visible") syncTimer(); }, 30000);
  document.addEventListener("visibilitychange", () => { if (document.visibilityState === "visible") syncTimer(); });
  document.addEventListener("htmx:afterSwap", renderTimer); // re-marks the running row's button
  document.addEventListener("DOMContentLoaded", syncTimer);

  document.addEventListener("click", (e) => {
    const play = e.target.closest(".play");
    if (play && !selecting()) {
      const t = timer;
      if (t && t.id === play.dataset.taskId && !over(t)) {
        timerPost(t.endsAt ? "/timer/pause" : "/timer/resume");
      } else {
        timerPost(`/timer/start?task=${play.dataset.taskId}`);
        try { if (window.Notification?.permission === "default") Notification.requestPermission(); } catch {}
      }
      return;
    }
    const action = e.target.closest("#timer [data-timer]")?.dataset.timer;
    if (!action || !timer) return;
    if (action === "pause") timerPost("/timer/pause");
    if (action === "resume") timerPost("/timer/resume");
    if (action === "stop") timerPost("/timer/stop");
    if (action === "more") { askMore = true; renderTimer(); }
    if (action === "add") {
      const minutes = +(e.target.dataset.minutes || document.querySelector("#timer .timer-custom")?.value || 0);
      if (minutes > 0) timerPost(`/timer/add?minutes=${minutes}`);
    }
    if (action === "done") timerPost("/timer/done");
  });

  // Clear quick add after a successful add (here rather than in an inline hx-on handler, so the
  // Content-Security-Policy can forbid inline script).
  document.addEventListener("htmx:afterRequest", (e) => {
    if (e.detail.successful && e.detail.elt.matches("form.quickadd")) e.detail.elt.reset();
  });

  // --- Task editor. Pills open popovers (one at a time); their text follows the controls inside, in
  // the same words as the phone's chips (Labels / chipDate / formatDuration in Kotlin).
  const MONTHS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];
  const pillDate = (date, time) => {
    // A time alone is today's (the server reads it so too).
    if (!date) return time ? `Today ${pillTime(time)}` : null;
    const [, m, d] = date.split("-").map(Number);
    if (!time) return `${MONTHS[m - 1]} ${d}`;
    return `${MONTHS[m - 1]} ${d} ${pillTime(time)}`;
  };
  const pillTime = (time) => {
    const [h, min] = time.split(":").map(Number);
    return `${h % 12 || 12}:${String(min).padStart(2, "0")} ${h < 12 ? "AM" : "PM"}`;
  };
  const duration = (m) => (m < 60 ? `${m}m` : m % 60 === 0 ? `${m / 60}h` : `${Math.floor(m / 60)}h ${m % 60}m`);
  // The server words it (Labels.repeat), in the preview it re-renders on each change.
  const repeatText = (pop) => pop.querySelector(".rep-preview")?.dataset.summary || null;
  document.addEventListener("htmx:afterSwap", (e) => { const pp = e.target.closest?.(".pp[data-kind=repeat]"); if (pp) refreshPill(pp); });
  // A repeat preset fills the builder's fields; a plain one also closes the popover, "…" ones open the builder.
  document.addEventListener("click", (e) => {
    const preset = e.target.closest(".rep-preset");
    if (!preset) return;
    const rep = preset.closest(".rep"), set = JSON.parse(preset.dataset.set);
    for (const [name, value] of Object.entries(set)) {
      rep.querySelectorAll(`[name="${name}"]`).forEach((i) => {
        if (i.type === "radio" || i.type === "checkbox") i.checked = Array.isArray(value) ? value.includes(i.value) : i.value === value;
        else i.value = value;
      });
    }
    rep.querySelectorAll(".rep-preset").forEach((b) => b.classList.toggle("on", b === preset));
    rep.classList.toggle("building", preset.classList.contains("rep-open"));
    rep.closest(".pop").dispatchEvent(new Event("change", { bubbles: true }));
    if (!preset.classList.contains("rep-open")) preset.closest(".pp").open = false;
  });
  function pillValue(pp) {
    const pop = pp.querySelector(".pop");
    switch (pp.dataset.kind) {
      case "date": return pillDate(pop.querySelector("input[type=date]").value, pop.querySelector("input[type=time]").value);
      case "select": { const s = pop.querySelector("select"); return s.value ? s.selectedOptions[0].textContent : null; }
      case "tree": { const r = pop.querySelector("input:checked"); return r?.value ? r.dataset.label : null; }
      case "remind": {
        // The same short form as the server's reminderSummary.
        const parts = [];
        if (pop.querySelector("[name=remindStart]").checked) parts.push("At start");
        const before = pop.querySelector("select");
        if (before.value) parts.push(before.value === "0" ? "At due time" : before.selectedOptions[0].textContent.replace(" min", "m").replace(" hour", "h").replace(" day", "d").replace("before", "before due"));
        const date = pop.querySelector("[name=remindAtDate]").value;
        if (date) parts.push(pillDate(date, pop.querySelector("[name=remindAtTime]").value));
        return parts.length ? parts.join(" · ") : null;
      }
      case "timer": { const m = +pop.querySelector("[name=duration]").value; return m > 0 ? duration(m) : null; }
      case "repeat": return repeatText(pop);
    }
    return null;
  }
  function refreshPill(pp) {
    const value = pillValue(pp), summary = pp.querySelector("summary");
    summary.classList.toggle("set", !!value);
    summary.querySelector(".pp-text").textContent = value ?? pp.dataset.label;
    const color = pp.querySelector(".pop select")?.selectedOptions[0]?.dataset.color ?? pp.querySelector(".folder-tree input:checked")?.dataset.color;
    if (color) pp.style.setProperty("--tint", color); else pp.style.removeProperty("--tint");
  }
  document.addEventListener("input", (e) => { const pp = e.target.closest(".pp[data-kind]"); if (pp) refreshPill(pp); });
  document.addEventListener("change", (e) => {
    const pp = e.target.closest(".pp[data-kind]");
    if (!pp) return;
    refreshPill(pp);
    if (e.target.closest(".folder-tree")) pp.open = false; // picking a folder is the whole job
  });
  // A date pill's quick choice (in an hour, tomorrow...): fills the date and time, then closes.
  document.addEventListener("click", (e) => {
    const quick = e.target.closest(".date-quick [data-date]");
    if (!quick) return;
    const pp = quick.closest(".pp");
    const date = pp.querySelector("input[type=date]");
    date.value = quick.dataset.date;
    pp.querySelector("input[type=time]").value = quick.dataset.time;
    date.dispatchEvent(new Event("change", { bubbles: true })); // the pill's text, and the editor's save
    pp.open = false;
  });
  document.addEventListener("click", (e) => {
    const preset = e.target.closest(".pop .preset[data-minutes]");
    if (preset) {
      const pp = preset.closest(".pp");
      pp.querySelector("[name=duration]").value = preset.dataset.minutes;
      refreshPill(pp);
      return;
    }
    const clear = e.target.closest(".pp-clear");
    if (clear) {
      e.preventDefault(); // don't also open the popover
      const pp = clear.closest(".pp");
      // Radios go back to their group's first choice (for Repeat, "don't repeat").
      pp.querySelectorAll(".pop input").forEach((i) => {
        if (i.type === "checkbox") i.checked = false;
        else if (i.type === "radio") i.checked = i === pp.querySelector(`.pop input[name="${i.name}"]`);
        else i.value = "";
      });
      pp.querySelector(".pop").dispatchEvent(new Event("change", { bubbles: true }));
      pp.querySelectorAll(".pop select").forEach((s) => { s.selectedIndex = 0; });
      refreshPill(pp);
      pp.open = false;
      return;
    }
    // Clicking outside an open popover closes it.
    document.querySelectorAll(".pp[open]").forEach((pp) => { if (!pp.contains(e.target)) pp.open = false; });
  });
  document.addEventListener("toggle", (e) => {
    if (e.target.matches?.(".pp") && e.target.open) document.querySelectorAll(".pp[open]").forEach((pp) => { if (pp !== e.target) pp.open = false; });
  }, true);
  // Enter in the (wrapping) title saves instead of adding a line; a maybe is never starred.
  document.addEventListener("keydown", (e) => {
    if (e.key === "Enter" && !e.shiftKey && e.target.matches(".title-input")) { e.preventDefault(); e.target.form.requestSubmit(); }
  });
  // Enter in a pill's popover (a date, a time, minutes) applies it and closes the popover, rather than
  // submitting the whole editor. Popovers with forms of their own (Related's "+" ones) keep theirs.
  document.addEventListener("keydown", (e) => {
    const pp = e.target.closest?.(".pp");
    if (e.key !== "Enter" || !pp || !e.target.closest(".pop") || !e.target.form?.classList.contains("editor")) return;
    e.preventDefault();
    e.target.dispatchEvent(new Event("change", { bubbles: true })); // the auto-save hears it
    if (pp.dataset.kind) refreshPill(pp);
    pp.open = false;
    pp.querySelector("summary").focus();
  });
  document.addEventListener("change", (e) => {
    const other = { maybe: "starred", starred: "maybe" }[e.target.name];
    if (other && e.target.checked && e.target.closest(".editor")) e.target.form.querySelector(`[name=${other}]`).checked = false;
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
  // --- The editor's Related section. A subtask is dragged by its handle, or moved with Alt+Up/Down,
  // to just before/after another subtask of the same task.
  let subDrag = null;
  const moveSub = (row, anchor, after) => htmx.ajax("POST", `/tasks/${row.dataset.task}/subtasks/${row.dataset.sub}/move`,
    { target: "#related", swap: "outerHTML", values: { anchor: anchor.dataset.sub, after: after ? "1" : "0" } });
  document.addEventListener("dragstart", (e) => {
    if (!e.target.matches?.(".drag-handle")) return;
    subDrag = e.target.closest(".rel-row.sub");
    e.dataTransfer.effectAllowed = "move";
    e.dataTransfer.setData("text/plain", subDrag.dataset.sub);
    e.dataTransfer.setDragImage(subDrag, 12, 12);
  });
  document.addEventListener("dragover", (e) => {
    const n = e.target.closest?.(".rel-row.sub");
    if (!subDrag || !n || n === subDrag) return;
    e.preventDefault();
    document.querySelectorAll(".rel-row[data-drop]").forEach((r) => delete r.dataset.drop);
    const r = n.getBoundingClientRect();
    n.dataset.drop = e.clientY < r.top + r.height / 2 ? "before" : "after";
  });
  document.addEventListener("drop", (e) => {
    const n = e.target.closest?.(".rel-row.sub");
    if (!subDrag || !n?.dataset.drop) return;
    e.preventDefault();
    moveSub(subDrag, n, n.dataset.drop === "after");
  });
  document.addEventListener("dragend", () => { subDrag = null; document.querySelectorAll(".rel-row[data-drop]").forEach((r) => delete r.dataset.drop); });
  document.addEventListener("keydown", (e) => {
    const row = e.target.closest?.(".rel-row.sub");
    if (!row || !e.altKey || (e.key !== "ArrowUp" && e.key !== "ArrowDown")) return;
    const subs = [...row.parentElement.querySelectorAll(".rel-row.sub")], next = subs[subs.indexOf(row) + (e.key === "ArrowDown" ? 1 : -1)];
    e.preventDefault();
    if (next) moveSub(row, next, e.key === "ArrowDown");
  });

  // The add field: ↓ goes into the suggestions (↑ ↓ between them, Enter picks one), Tab switches the
  // kind, Esc clears. Pasting several lines offers a subtask each.
  document.addEventListener("keydown", (e) => {
    const form = e.target.closest?.(".add-related");
    if (!form) return;
    const input = form.querySelector("input[name=text]"), suggs = [...form.querySelectorAll(".sugg")];
    if (e.target === input && e.key === "Tab" && !e.shiftKey) {
      const kinds = [...form.querySelectorAll("input[name=kind]")], at = kinds.findIndex((k) => k.checked);
      if (kinds.length < 2) return;
      e.preventDefault();
      kinds[(at + 1) % kinds.length].checked = true;
      input.dispatchEvent(new Event("kindchange"));
    } else if (e.key === "ArrowDown" || e.key === "ArrowUp") {
      const i = e.target === input ? -1 : suggs.indexOf(e.target);
      const to = e.key === "ArrowDown" ? suggs[i + 1] : i <= 0 ? input : suggs[i - 1];
      if (to) { e.preventDefault(); to.focus(); }
    } else if (e.key === "Escape") {
      input.value = "";
      form.querySelector(".suggest").replaceChildren();
    }
  });
  document.addEventListener("change", (e) => {
    if (e.target.matches?.(".add-related input[name=kind]")) e.target.form.querySelector("input[name=text]").dispatchEvent(new Event("kindchange"));
  });
  // A waiting item's "Follow up": a task of your own to chase it, typed into quick add for you to finish.
  document.addEventListener("click", (e) => {
    const b = e.target.closest?.("[data-follow-up]");
    const box = b && document.getElementById("quickadd");
    if (!box) return;
    box.value = b.dataset.followUp;
    box.focus();
  });
  document.addEventListener("paste", (e) => {
    const form = e.target.closest?.(".add-related");
    const lines = (e.clipboardData?.getData("text") ?? "").split(/\r?\n/).filter((l) => l.trim());
    if (!form || lines.length < 2 || form.querySelector("input[name=kind]:checked")?.value !== "subtask") return;
    if (!confirm(`Add ${lines.length} subtasks?`)) return;
    e.preventDefault();
    htmx.ajax("POST", form.getAttribute("hx-post"), { target: "#related", swap: "outerHTML", values: { kind: "subtask", text: lines.join("\n") } });
  });
})();
