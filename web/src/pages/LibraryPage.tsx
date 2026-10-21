import { useState } from "react";
import { Link } from "react-router-dom";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import {
  cancelDownload,
  clearHistory,
  deleteDownload,
  listBookmarks,
  listContinueWatching,
  listDownloads,
  listHistory,
  removeBookmark,
  removeHistory,
  resumeDownload,
} from "../lib/api";

/** Library hub: Continue Watching, Bookmarks, watch History, Downloads. */
export function LibraryPage() {
  const [tab, setTab] = useState<"continue" | "bookmarks" | "history" | "downloads">("continue");

  const tabs = [
    ["continue", "Continue watching"],
    ["bookmarks", "Bookmarks"],
    ["history", "History"],
    ["downloads", "Downloads"],
  ] as const;

  return (
    <section>
      <h1>Library</h1>
      <div className="library-tabs mono small">
        {tabs.map(([id, label]) => (
          <button key={id} className={tab === id ? "active" : ""} onClick={() => setTab(id)}>
            {label}
          </button>
        ))}
      </div>
      {tab === "continue" && <ContinueRow />}
      {tab === "bookmarks" && <BookmarksGrid />}
      {tab === "history" && <HistoryList />}
      {tab === "downloads" && <DownloadsPanel />}
    </section>
  );
}

/** Link target for a history/bookmark row: deep-links the exact episode. */
function detailHref(provider: string, url: string, episodeData?: string | null): string {
  const p = new URLSearchParams({ provider, url });
  if (episodeData) p.set("data", episodeData);
  return `/detail?${p.toString()}`;
}

function fmtPos(position: number, duration?: number | null): string {
  const fmt = (s: number) => new Date(s * 1000).toISOString().slice(duration != null && duration >= 3600 ? 11 : 14, 19);
  return duration ? `${fmt(position)} / ${fmt(duration)}` : fmt(position);
}

// ---------- continue watching -------------------------------------------------

function ContinueRow() {
  const continueQ = useQuery({ queryKey: ["continue"], queryFn: () => listContinueWatching() });
  const qc = useQueryClient();

  if (continueQ.isPending) return <p className="muted">Loading…</p>;
  if (continueQ.isError) return <p className="muted warn">Failed: {(continueQ.error as Error).message}</p>;
  const rows = continueQ.data ?? [];
  if (rows.length === 0) {
    return <p className="muted">Nothing in progress yet — resume shows up after you watch something.</p>;
  }

  const drop = async (provider: string, url: string, episodeData: string) => {
    await removeHistory(provider, url, episodeData);
    qc.invalidateQueries({ queryKey: ["continue"] });
  };

  return (
    <div className="library-grid">
      {rows.map((r) => {
        const pct = r.duration && r.duration > 0 ? Math.min(100, Math.round((r.position / r.duration) * 100)) : 0;
        return (
          <div key={`${r.provider}-${r.url}-${r.episodeData}`} className="library-card">
            <Link to={detailHref(r.provider, r.url, r.episodeData)} className="library-card-link">
              <div className="library-poster">
                {r.poster
                  ? <img src={r.poster} alt="" loading="lazy" />
                  : <span className="poster-fallback">{r.name.slice(0, 2)}</span>}
                <div className="progress-track"><div className="progress-fill" style={{ width: `${pct}%` }} /></div>
              </div>
              <div className="library-card-body">
                <div className="library-card-title">{r.name}</div>
                <div className="muted small">{r.episodeLabel ?? "resume"}</div>
                <div className="muted small mono">{fmtPos(r.position, r.duration)} · {pct}%</div>
              </div>
            </Link>
            <button className="link-danger small" onClick={() => void drop(r.provider, r.url, r.episodeData)}>
              ✕ remove
            </button>
          </div>
        );
      })}
    </div>
  );
}

// ---------- bookmarks ---------------------------------------------------------

function BookmarksGrid() {
  const bookmarksQ = useQuery({ queryKey: ["bookmarks"], queryFn: listBookmarks });
  const qc = useQueryClient();

  if (bookmarksQ.isPending) return <p className="muted">Loading…</p>;
  if (bookmarksQ.isError) return <p className="muted warn">Failed: {(bookmarksQ.error as Error).message}</p>;
  const rows = bookmarksQ.data ?? [];
  if (rows.length === 0) {
    return <p className="muted">No bookmarks yet — the star button on any detail page adds one.</p>;
  }

  const drop = async (provider: string, url: string) => {
    await removeBookmark(provider, url);
    qc.invalidateQueries({ queryKey: ["bookmarks"] });
  };

  return (
    <div className="library-grid">
      {rows.map((r) => (
        <div key={`${r.provider}-${r.url}`} className="library-card">
          <Link to={detailHref(r.provider, r.url)} className="library-card-link">
            <div className="library-poster">
              {r.poster
                ? <img src={r.poster} alt="" loading="lazy" />
                : <span className="poster-fallback">{r.name.slice(0, 2)}</span>}
            </div>
            <div className="library-card-body">
              <div className="library-card-title">{r.name}</div>
              <div className="muted small">{r.provider}{r.type ? ` · ${r.type}` : ""}</div>
            </div>
          </Link>
          <button className="link-danger small" onClick={() => void drop(r.provider, r.url)}>
            ✕ remove
          </button>
        </div>
      ))}
    </div>
  );
}

// ---------- history -----------------------------------------------------------

function HistoryList() {
  const historyQ = useQuery({ queryKey: ["history"], queryFn: () => listHistory() });
  const qc = useQueryClient();

  if (historyQ.isPending) return <p className="muted">Loading…</p>;
  if (historyQ.isError) return <p className="muted warn">Failed: {(historyQ.error as Error).message}</p>;
  const rows = historyQ.data ?? [];

  const drop = async (provider: string, url: string, episodeData: string) => {
    await removeHistory(provider, url, episodeData);
    qc.invalidateQueries({ queryKey: ["history"] });
    qc.invalidateQueries({ queryKey: ["continue"] });
  };
  const dropAll = async () => {
    await clearHistory();
    qc.invalidateQueries({ queryKey: ["history"] });
    qc.invalidateQueries({ queryKey: ["continue"] });
  };

  return (
    <>
      <div className="library-tools">
        <button onClick={() => void dropAll()} disabled={rows.length === 0}>clear all history</button>
      </div>
      {rows.length === 0 && <p className="muted">No watch history yet.</p>}
      <ul className="history-list">
        {rows.map((r) => (
          <li key={`${r.provider}-${r.url}-${r.episodeData}`}>
            <Link to={detailHref(r.provider, r.url, r.episodeData)}>
              {r.poster && <img src={r.poster} alt="" loading="lazy" />}
              <span>
                <strong>{r.name}</strong>
                <span className="muted small">
                  {" "}{r.episodeLabel ?? ""} · {r.finished ? "finished" : fmtPos(r.position, r.duration)}
                  {" "}· {r.provider} · {r.updatedAt?.replace("T", " ").slice(0, 16)}
                </span>
              </span>
            </Link>
            <button className="link-danger small" onClick={() => void drop(r.provider, r.url, r.episodeData)}>
              ✕
            </button>
          </li>
        ))}
      </ul>
    </>
  );
}

// ---------- downloads ---------------------------------------------------------

function humanBytes(n: number): string {
  if (n >= 1 << 30) return `${(n / (1 << 30)).toFixed(1)} GB`;
  if (n >= 1 << 20) return `${(n / (1 << 20)).toFixed(1)} MB`;
  if (n >= 1 << 10) return `${(n / (1 << 10)).toFixed(0)} KB`;
  return `${n} B`;
}

function DownloadsPanel() {
  const dlQ = useQuery({
    queryKey: ["downloads"],
    queryFn: listDownloads,
    refetchInterval: 1800, // live progress while the panel is open
  });
  const qc = useQueryClient();
  const refresh = () => qc.invalidateQueries({ queryKey: ["downloads"] });

  if (dlQ.isPending) return <p className="muted">Loading…</p>;
  if (dlQ.isError) return <p className="muted warn">Failed: {(dlQ.error as Error).message}</p>;
  const rows = dlQ.data ?? [];
  if (rows.length === 0) {
    return <p className="muted">No downloads yet — the ⬇ button in the player queues the current link.</p>;
  }

  const act = async (f: () => Promise<unknown>) => {
    await f();
    refresh();
  };

  return (
    <div className="downloads-list">
      {rows.map((d) => {
        const pct = d.totalBytes ? Math.min(100, Math.round((d.downloadedBytes / d.totalBytes) * 100)) : null;
        const active = d.state === "RUNNING" || d.state === "QUEUED";
        const resumable = d.state === "PAUSED" || d.state === "FAILED";
        return (
          <div key={d.id} className="download-row">
            <div className="download-head">
              <strong title={d.filePath ?? undefined}>{d.name}</strong>
              <span className="muted small mono">
                {d.state}
                {d.totalBytes ? ` · ${humanBytes(d.downloadedBytes)} / ${humanBytes(d.totalBytes)}` : ` · ${humanBytes(d.downloadedBytes)}`}
                {d.provider ? ` · ${d.provider}` : ""}
                {d.detail ? ` · ${d.detail}` : ""}
              </span>
            </div>
            {pct != null && (
              <div className="progress-track wide">
                <div className={`progress-fill${d.state === "FAILED" ? " bad" : ""}`} style={{ width: `${pct}%` }} />
              </div>
            )}
            <div className="download-actions small">
              {active && <button onClick={() => void act(() => cancelDownload(d.id))}>pause</button>}
              {resumable && <button onClick={() => void act(() => resumeDownload(d.id))}>resume</button>}
              <button className="link-danger" onClick={() => void act(() => deleteDownload(d.id))}>
                {d.state === "DONE" ? "delete file" : "discard"}
              </button>
            </div>
          </div>
        );
      })}
    </div>
  );
}
