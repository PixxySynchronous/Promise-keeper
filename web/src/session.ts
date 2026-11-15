// The login token lives in memory, with a copy in sessionStorage so a page reload keeps you logged in until
// the tab closes. Storage can throw (private mode, blocked site data), so every access is wrapped and the
// app works without it.
const KEY = "pk_token";
let memory: string | null = null;

export function loadToken(): string | null {
  if (memory) return memory;
  try {
    memory = sessionStorage.getItem(KEY);
  } catch {
    memory = null;
  }
  return memory;
}

export function saveToken(token: string | null): void {
  memory = token;
  try {
    if (token) sessionStorage.setItem(KEY, token);
    else sessionStorage.removeItem(KEY);
  } catch {
    // Memory copy still works for this page load.
  }
}
