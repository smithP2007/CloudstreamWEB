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
  return json<ProviderView[]>(`${API_BASE}/api/providers`);
}

/** fetch + error contract shared by all API calls */
export async function json<T>(url: string, init?: RequestInit): Promise<T> {
  const res = await fetch(url, {
    ...init,
    headers: {
      ...authHeaders(),
      ...(init?.body ? { "Content-Type": "application/json" } : {}),
      ...(init?.headers ?? {}),
    },
  });
  if (res.status === 401 && !url.includes("token=")) {
    window.dispatchEvent(new Event("csweb-auth"));
    throw new Error("401: this server requires the LAN token");
  }
  if (!res.ok) {
    let detail = "";
    let code = "";
    try {
      const body = await res.json();
      detail = body.detail ? `: ${body.detail}` : "";
      code = body.error ? ` ${body.error}` : "";
    } catch { /* ignore */ }
    throw new Error(`${res.status}${code}${detail}`);
  }
  if (res.status === 204) return undefined as T;
  return res.json();
}

// ---------- optional LAN token (CS_WEB_LAN_TOKEN mode) -----------------------

/** The stored shared token, if the user supplied one. */
export function authToken(): string | null {
  try {
    return localStorage.getItem("csweb.token");
  } catch {
    return null;
  }
}

export function setAuthToken(token: string): void {
  try {
    localStorage.setItem("csweb.token", token);
  } catch { /* private mode: token lives only until reload */ }
}

function authHeaders(): Record<string, string> {
  const t = authToken();
  return t ? { Authorization: `Bearer ${t}` } : {};
}

/** ?token= form for <video>/<track> sources that cannot send headers. */
function authQuery(): string {
  const t = authToken();
  return t ? `&token=${encodeURIComponent(t)}` : "";
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

export type SubtitleDto = {
  lang: string;
  url: string;
  headers: Record<string, string>;
};

export type StreamsDto = {
  provider: string;
  data: string;
  links: StreamDto[];
  subtitles?: SubtitleDto[];
};

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
  return proxyUrl("/api/stream", link.url, link.headers ?? {});
}

/**
 * Provider subtitles go through their own bridge: it replays headers and
 * converts SRT timestamps to WebVTT on the fly.
 */
export function subtitleProxyUrl(sub: Pick<SubtitleDto, "url" | "headers">): string {
  return proxyUrl("/api/subtitle", sub.url, sub.headers ?? {});
}

function proxyUrl(base: string, url: string, headers: Record<string, string>): string {
  const encoded = JSON.stringify(headers ?? {});
  const hasHeaders = encoded.length > 2;
  const h = hasHeaders
    ? `&h=${btoa(encoded).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "")}`
    : "";
  return `${base}?url=${encodeURIComponent(url)}${h}${authQuery()}`;
}

/** Ordered playback candidates: HLS first (proxy + hls.js), then direct files. */
export function playbackCandidates(links: StreamDto[]): StreamDto[] {
  const typeRank = (t: string) =>
    t === "M3U8" ? 2 : t === "VIDEO" || t === "FILE" ? 1 : 0;
  return [...links]
    .filter((l) => /^https?:/i.test(l.url))
    .sort((a, b) => typeRank(b.linkType) - typeRank(a.linkType) || b.quality - a.quality);
}

// ---------- library (Phase 4) ------------------------------------------------

export type BookmarkDto = {
  provider: string;
  url: string;
  name: string;
  poster?: string | null;
  type?: string | null;
  sourcePlugin?: string | null;
  createdAt?: string | null;
};

export type HistoryEntryDto = {
  provider: string;
  url: string;
  episodeData: string;
  name: string;
  episodeLabel?: string | null;
  poster?: string | null;
  position: number;
  duration?: number | null;
  finished: boolean;
  updatedAt?: string | null;
};

export type DownloadDto = {
  id: string;
  provider?: string | null;
  url?: string | null;
  name: string;
  state: "QUEUED" | "RUNNING" | "PAUSED" | "DONE" | "FAILED" | string;
  downloadedBytes: number;
  totalBytes?: number | null;
  detail?: string | null;
  filePath?: string | null;
  createdAt?: string | null;
};

// -- bookmarks

export async function listBookmarks(): Promise<BookmarkDto[]> {
  return json<BookmarkDto[]>("/api/library/bookmarks");
}

export async function isBookmarked(provider: string, url: string): Promise<boolean> {
  const p = new URLSearchParams({ provider, url });
  return (await json<{ bookmarked: boolean }>(`/api/library/bookmark?${p.toString()}`)).bookmarked;
}

export async function addBookmark(b: Omit<BookmarkDto, "createdAt">): Promise<void> {
  await json("/api/library/bookmarks", { method: "POST", body: JSON.stringify(b) });
}

export async function removeBookmark(provider: string, url: string): Promise<void> {
  const p = new URLSearchParams({ provider, url });
  await json(`/api/library/bookmarks?${p.toString()}`, { method: "DELETE" });
}

// -- history / continue watching

export async function getHistoryEntry(
  provider: string,
  url: string,
  episodeData: string,
): Promise<HistoryEntryDto | null> {
  const p = new URLSearchParams({ provider, url, data: episodeData });
  try {
    return await json<HistoryEntryDto>(`/api/library/history/entry?${p.toString()}`);
  } catch (e) {
    // 404 = no entry yet (expected for first play)
    if (String(e).includes("404")) return null;
    throw e;
  }
}

export type HistoryUpsert = {
  provider: string;
  url: string;
  episodeData: string;
  name: string;
  episodeLabel?: string | null;
  poster?: string | null;
  position: number;
  duration?: number | null;
  finished?: boolean;
};

export async function saveHistory(h: HistoryUpsert): Promise<void> {
  await json("/api/library/history", { method: "POST", body: JSON.stringify(h) });
}

export async function listHistory(limit = 60): Promise<HistoryEntryDto[]> {
  return json<HistoryEntryDto[]>(`/api/library/history?limit=${limit}`);
}

export async function listContinueWatching(limit = 24): Promise<HistoryEntryDto[]> {
  return json<HistoryEntryDto[]>(`/api/library/continue?limit=${limit}`);
}

export async function removeHistory(provider: string, url: string, episodeData?: string): Promise<void> {
  const p = new URLSearchParams({ provider, url });
  if (episodeData) p.set("data", episodeData);
  await json(`/api/library/history?${p.toString()}`, { method: "DELETE" });
}

export async function clearHistory(): Promise<void> {
  await json("/api/library/history", { method: "DELETE" });
}

// -- downloads

export async function listDownloads(): Promise<DownloadDto[]> {
  return json<DownloadDto[]>("/api/downloads");
}

export type DownloadCreate = {
  provider?: string | null;
  url?: string | null;
  name: string;
  link: { url: string; headers?: Record<string, string> };
  extHint?: string | null;
};

export async function createDownload(d: DownloadCreate): Promise<DownloadDto> {
  return json<DownloadDto>("/api/downloads", { method: "POST", body: JSON.stringify(d) });
}

export async function cancelDownload(id: string): Promise<DownloadDto> {
  return json<DownloadDto>(`/api/downloads/${id}/cancel`, { method: "POST" });
}

export async function resumeDownload(id: string): Promise<DownloadDto> {
  return json<DownloadDto>(`/api/downloads/${id}/resume`, { method: "POST" });
}

export async function deleteDownload(id: string): Promise<void> {
  await json(`/api/downloads/${id}`, { method: "DELETE" });
}
