export const API_BASE = "";

export async function getHealth(): Promise<{
  ok: boolean;
  version: string;
  libraryAvailable: string;
}> {
  const res = await fetch(`${API_BASE}/api/health`);
  if (!res.ok) throw new Error(`health ${res.status}`);
  return res.json();
}

export type ProviderView = {
  name: string;
  mainUrl: string;
  sourcePlugin?: string | null;
  javaType: string;
  tvTypes: string[];
  hasMainPage: boolean;
};

export async function getProviders(): Promise<ProviderView[]> {
  const res = await fetch(`${API_BASE}/api/providers`);
  if (!res.ok) throw new Error(`providers ${res.status}`);
  return res.json();
}

/** fetch + error contract shared by all API calls */
export async function json<T>(url: string, init?: RequestInit): Promise<T> {
  const res = await fetch(url, {
    ...init,
    headers: init?.body ? { "Content-Type": "application/json", ...(init?.headers ?? {}) } : init?.headers,
  });
  if (!res.ok) {
    let detail = "";
    try {
      const body = await res.json();
      detail = body.detail ? `: ${body.detail}` : "";
    } catch { /* ignore */ }
    throw new Error(`${res.status}${detail}`);
  }
  if (res.status === 204) return undefined as T;
  return res.json();
}
