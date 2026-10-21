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

// ---------- content types (mirror server DTOs in ContentRoutes.kt) ----------

export type ItemDto = {
  name: string;
  url: string;
  provider: string;
  sourcePlugin?: string | null;
  poster?: string | null;
  type?: string | null;
};

export type HomeRowDto = { name: string; items: ItemDto[] };
export type HomeDto = { provider: string; rows: HomeRowDto[] };
export type SearchDto = { provider: string; page: number; items: ItemDto[] };

export type EpisodeDto = {
  name?: string | null;
  season?: number | null;
  episode?: number | null;
  poster?: string | null;
  description?: string | null;
  data: string;
  dubStatus?: string | null;
};

export type DetailDto = {
  provider: string;
  url: string;
  name: string;
  type: string;
  poster?: string | null;
  year?: number | null;
  plot?: string | null;
  tags?: string[] | null;
  durationMinutes?: number | null;
  episodes: EpisodeDto[];
};

export type StreamDto = {
  name: string;
  url: string;
  source: string;
  quality: number;
  headers: Record<string, string>;
  linkType: string;
};

export type StreamsDto = { provider: string; data: string; links: StreamDto[] };

export async function getHome(provider: string, mainUrl?: string): Promise<HomeDto> {
  const p = new URLSearchParams({ provider });
  if (mainUrl) p.set("mainUrl", mainUrl);
  return json<HomeDto>(`/api/home?${p.toString()}`);
}

export async function search(provider: string, q: string, mainUrl?: string): Promise<SearchDto> {
  const p = new URLSearchParams({ provider, q });
  if (mainUrl) p.set("mainUrl", mainUrl);
  return json<SearchDto>(`/api/search?${p.toString()}`);
}

export async function getDetail(provider: string, url: string, mainUrl?: string): Promise<DetailDto> {
  const p = new URLSearchParams({ provider, url });
  if (mainUrl) p.set("mainUrl", mainUrl);
  return json<DetailDto>(`/api/detail?${p.toString()}`);
}

export async function getStreams(provider: string, data: string, mainUrl?: string): Promise<StreamsDto> {
  const p = new URLSearchParams({ provider, data });
  if (mainUrl) p.set("mainUrl", mainUrl);
  return json<StreamsDto>(`/api/streams?${p.toString()}`);
}

/**
 * Every provider link streams through the server-side /api/stream bridge:
 * it replays the headers the extractor requires and rewrites m3u8 playlists.
 */
export function streamProxyUrl(link: Pick<StreamDto, "url" | "headers">): string {
  const headers = JSON.stringify(link.headers ?? {});
  const hasHeaders = headers.length > 2;
  const h = hasHeaders
    ? `&h=${btoa(headers).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "")}`
    : "";
  return `/api/stream?url=${encodeURIComponent(link.url)}${h}`;
}

/** Ordered playback candidates: HLS first (proxy + hls.js), then direct files. */
export function playbackCandidates(links: StreamDto[]): StreamDto[] {
  const typeRank = (t: string) =>
    t === "M3U8" ? 2 : t === "VIDEO" || t === "FILE" ? 1 : 0;
  return [...links]
    .filter((l) => /^https?:/i.test(l.url))
    .sort((a, b) => typeRank(b.linkType) - typeRank(a.linkType) || b.quality - a.quality);
}
