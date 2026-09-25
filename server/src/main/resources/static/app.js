// Keyboard shortcuts (ignored while typing): n = quick add, g then d/a/t = Doing/Active/All, ? = help.
// Also hides the undo toast a few seconds after it appears.
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

  let hideTimer;
  document.addEventListener("htmx:oobAfterSwap", (e) => {
    if (e.detail.target.id !== "toast") return;
    clearTimeout(hideTimer);
    hideTimer = setTimeout(() => e.detail.target.classList.remove("show"), 6000);
  });
})();
