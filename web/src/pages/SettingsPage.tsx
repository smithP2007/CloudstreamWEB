import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import {
  clearAuthToken,
  clearHistory,
  deleteDownload,
  getHealth,
  getProviders,
  listBookmarks,
  listDownloads,
  listHistory,
  authToken,
  setAuthToken,
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

function TokenCard() {
  const [stored, setStored] = useState(!!authToken());
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
