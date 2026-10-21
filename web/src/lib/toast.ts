/**
 * Minimal toast surfacing for typed API errors (Phase 5 hardening).
 * One fixed container, auto-dismiss, styling from .toast/.toast.error.
 */
let wrap: HTMLDivElement | null = null;

function container(): HTMLDivElement {
  if (!wrap) {
    wrap = document.createElement("div");
    wrap.className = "toast-wrap";
    document.body.appendChild(wrap);
  }
  return wrap;
}

export function toast(message: string, kind: "info" | "error" = "info", ms = 5000): void {
  const el = document.createElement("div");
  el.className = `toast ${kind}`;
  el.textContent = message;
  container().appendChild(el);
  window.setTimeout(() => el.remove(), ms);
}

/** Convenience for promise chains */
export function toastError(prefix: string, e: unknown): void {
  const msg = e instanceof Error ? e.message : String(e);
  toast(`${prefix}: ${msg}`, "error");
}
