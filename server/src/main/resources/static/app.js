// Keyboard shortcuts (ignored while typing): n = quick add, g then d/a/t = Doing/Active/All, ? = help.
// Plus small behaviours for htmx fragments, the editor's pickers and the undo toast.
(() => {
  let pendingG = false;
  const go = { d: "/doing", a: "/active", t: "/all" };
  document.addEventListener("keydown", (e) => {
    const typing = e.target.closest("input, textarea, select");
    if (e.key === "Escape" && typing) { e.target.blur(); return; }
    if (typing || e.ctrlKey || e.metaKey || e.altKey) return;
    if (pendingG && go[e.key]) { location.href = go[e.key]; pendingG = false; return; }
    pendingG = e.key === "g";
    if (e.key === "n") { e.preventDefault(); document.getElementById("quickadd")?.focus(); }
    if (e.key === "?") alert("n  quick add\ng d / g a / g t  Doing / Active / All\nEsc  leave the text box");
  });

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
  document.addEventListener("htmx:oobAfterSwap", (e) => { if (e.detail.target.id === "toast") hideSoon(e.detail.target); });
  document.addEventListener("DOMContentLoaded", () => {
    const toast = document.getElementById("toast");
    if (toast?.classList.contains("show")) hideSoon(toast);
  });
})();
