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
