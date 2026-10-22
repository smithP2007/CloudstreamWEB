import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import {
  clearAuthToken,
  clearHistory,
  deleteDownload,
  getCapabilities,
  getHealth,
  getProviders,
  getSettings,
  listBookmarks,
  listDownloads,
  listHistory,
  postSettings,
  authToken,
  setAuthToken,
  type PlaybackSettings,
} from "../lib/api";
import { toastError } from "../lib/toast";

/** Settings: backend status, providers overview, library tools, token. */
export default function SettingsPage() {
  const health = useQuery({ queryKey: ["health"], queryFn: getHealth });
  const providers = useQuery({ queryKey: ["providers"], queryFn: getProviders });
  const bookmarks = useQuery({ queryKey: ["bookmarks"], queryFn: listBookmarks });
  const history = useQuery({ queryKey: ["history"], queryFn: () => listHistory() });
  const downloads = useQuery({ queryKey: ["downloads"], queryFn: listDownloads });

  return (
    <section>
      <h1>Settings</h1>

      <div className="settings-cards">
        <div className="card">
          <h2>Backend</h2>
          {health.isPending && <p className="muted">checking…</p>}
          {health.isError && <p className="muted warn">not reachable</p>}
          {health.data?.ok && (
            <>
              <p>
                version <code>{health.data.version}</code>
              </p>
              <p className="muted small mono">
                upstream plugin API: {health.data.libraryAvailable}
              </p>
            </>
          )}
        </div>

        <PlaybackBackends />

        <TokenCard />

        <div className="card">
          <h2>Library</h2>
          <p className="muted small">
            bookmarks {bookmarks.data?.length ?? "…"} · history {history.data?.length ?? "…"} ·
            downloads {downloads.data?.length ?? "…"}
          </p>
          <MaintenanceButtons />
        </div>
      </div>

      <h2>Providers ({providers.data?.length ?? "…"})</h2>
      {providers.isError && <p className="muted warn">Failed: {(providers.error as Error).message}</p>}
      <table className="settings-table">
        <tbody>
          {(providers.data ?? []).map((p) => (
            <tr key={`${p.sourcePlugin}-${p.name}`}>
              <td>
                <strong>{p.name}</strong>
                {p.mainUrl && <span className="muted small mono"> {p.mainUrl}</span>}
              </td>
              <td className="muted small">{p.sourcePlugin ?? "—"}</td>
              <td className="muted small">{p.tvTypes?.join(", ") || "—"}</td>
              <td className="muted small">{p.hasMainPage ? "home ✓" : "search only"}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </section>
  );
}

/** v2 M2: capability status + the five playback toggles. A toggle is grayed
 * out when the capability it needs is absent (e.g. ffmpeg not installed). */
function PlaybackBackends() {
  const caps = useQuery({ queryKey: ["capabilities"], queryFn: getCapabilities });
  const settings = useQuery({ queryKey: ["settings"], queryFn: getSettings });
  const qc = useQueryClient();
  const [busy, setBusy] = useState(false);

  const flip = async (patch: Partial<PlaybackSettings>) => {
    setBusy(true);
    try {
      await postSettings(patch);
      qc.invalidateQueries({ queryKey: ["settings"] });
    } catch (e) {
      toastError("setting failed", e);
    } finally {
      setBusy(false);
    }
  };

  const cap = caps.data;
  const s = settings.data;
  const ffmpegNote = cap
    ? cap.ffmpegAvailable
      ? `✓ ${cap.ffmpegVersion ?? "installed"}`
      : "✗ not installed — run scripts/bootstrap.sh (remux/transcode stay off)"
    : "…";

  return (
    <div className="card">
      <h2>Playback backends</h2>

      <p className="muted small mono">
        ffmpeg: {ffmpegNote}
        <br />
        audio streams listable: {cap ? (cap.audioRemuxAvailable ? "✓ (ffprobe)" : "✗") : "…"}
        <br />
        GPU: {cap ? (cap.gpuAccelerators.length ? cap.gpuAccelerators.join(", ") : "none detected (CPU only)") : "…"}
      </p>

      {settings.isPending && <p className="muted">loading…</p>}
      {settings.isError && <p className="muted warn">couldn't load settings</p>}
      {settings.data && (
        <div className="toggle-list">
          <label className="toggle-row">
            <input
              type="checkbox"
              checked={settings.data.audioRemux}
              disabled={busy || !cap?.audioRemuxAvailable}
              onChange={(e) => void flip({ audioRemux: e.target.checked })}
            />
            <span>
              <strong>Audio language switching</strong>
              <span className="muted small">
                muxed multi-audio files (e.g. Hindi/English/Korean tracks) get a language selector — remuxes MKV
                streams to MP4 while playing
                {!cap?.audioRemuxAvailable && " (needs ffmpeg + ffprobe)"}
              </span>
            </span>
          </label>

          <label className="toggle-row">
            <input
              type="checkbox"
              checked={settings.data.probeSources}
              disabled={busy || !cap?.decodeProbeAvailable}
              onChange={(e) => void flip({ probeSources: e.target.checked })}
            />
            <span>
              <strong>Probe video sources</strong>
              <span className="muted small">
                check each link's real codec/speed before ranking, and mark what your browser can decode
                {!cap?.decodeProbeAvailable && " (needs ffprobe)"}
              </span>
            </span>
          </label>

          <label className="toggle-row">
            <input
              type="checkbox"
              checked={settings.data.liveTranscode}
              disabled={busy || !cap?.liveTranscodeAvailable}
              onChange={(e) => void flip({ liveTranscode: e.target.checked })}
            />
            <span>
              <strong>Live transcoding</strong>
              <span className="muted small">
                convert streams the browser can't decode while playing (slower start, CPU/GPU heavy)
                {!cap?.liveTranscodeAvailable && " (needs ffmpeg)"}
              </span>
            </span>
          </label>

          <label className="toggle-row">
            <span>
              <strong>GPU acceleration</strong>
              <span className="muted small">
                for conversions — {cap?.gpuAccelerators.length ? `detected: ${cap.gpuAccelerators.join(", ")}` : "none detected, 'on' will fall back to CPU"}
              </span>
            </span>
            <select
              value={settings.data.gpuAccel}
              disabled={busy}
              onChange={(e) => void flip({ gpuAccel: e.target.value as PlaybackSettings["gpuAccel"] })}
            >
              <option value="auto">auto</option>
              <option value="on">on</option>
              <option value="off">off</option>
            </select>
          </label>

          <label className="toggle-row">
            <input
              type="checkbox"
              checked={settings.data.convertAfterDownload}
              disabled={busy || !cap?.liveTranscodeAvailable}
              onChange={(e) => void flip({ convertAfterDownload: e.target.checked })}
            />
            <span>
              <strong>Convert after download</strong>
              <span className="muted small">
                keep an extra 8-bit MP4 copy of each download so tricky files play smoothly later
                {!cap?.liveTranscodeAvailable && " (needs ffmpeg)"}
              </span>
            </span>
          </label>
        </div>
      )}
    </div>
  );
}

function TokenCard() {  const [stored, setStored] = useState(!!authToken());
  return (
    <div className="card">
      <h2>LAN token</h2>
      <p className="muted small">
        When the server runs with <code>CS_WEB_LAN_TOKEN</code>, the SPA stores the shared
        token after the first 401 prompt.
      </p>
      <p>
        status:{" "}
        {stored ? (
          <>
            <span className="good">● stored</span>{" "}
            <button onClick={() => { clearAuthToken(); setStored(false); }}>clear</button>
          </>
        ) : (
          <span className="muted">○ not stored (server may not require one)</span>
        )}
      </p>
      <form
        onSubmit={(e) => {
          e.preventDefault();
          const input = e.currentTarget.elements.namedItem("t") as HTMLInputElement | null;
          const value = input?.value.trim() ?? "";
          if (!value) return;
          setAuthToken(value);
          setStored(true);
          input!.value = "";
        }}
      >
        <input name="t" placeholder="set token manually" autoComplete="off" />
        <button type="submit">save</button>
      </form>
    </div>
  );
}

function MaintenanceButtons() {
  const qc = useQueryClient();
  const [busy, setBusy] = useState(false);

  const run = async (label: string, f: () => Promise<void>) => {
    if (!window.confirm(label)) return;
    setBusy(true);
    try {
      await f();
      qc.invalidateQueries();
    } catch (e) {
      toastError("failed", e);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="library-tools">
      <button
        disabled={busy}
        onClick={() =>
          void run(
            "Delete the entire watch history?",
            async () => {
              await clearHistory();
            },
          )
        }
      >
        clear all history
      </button>
      <button
        disabled={busy}
        onClick={() =>
          void run(
            "Discard all downloads (files on disk are removed too)?",
            async () => {
              const rows = await listDownloads();
              for (const d of rows) await deleteDownload(d.id);
            },
          )
        }
      >
        discard all downloads
      </button>
    </div>
  );
}
