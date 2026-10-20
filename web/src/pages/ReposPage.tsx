import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { API_BASE } from "../lib/api";

type CatalogEntry = { url: string; verified: boolean };
type RepoEntry = {
  url: string;
  name?: string | null;
  description?: string | null;
  iconUrl?: string | null;
  manifestVersion: number;
  enabled: boolean;
};
type PluginView = {
  url: string;
  name: string;
  internalName: string;
  status: number;
  version: number;
  apiVersion: number;
  language?: string | null;
  description?: string | null;
  tvTypes?: string[] | null;
  authors: string[];
};

/** Java-side uses Base64.getUrlEncoder().withoutPadding() */
export function b64url(s: string): string {
  return btoa(unescape(encodeURIComponent(s))).replace(/=+$/, "");
}

const STATUS_LABEL: Record<number, string> = {
  0: "down",
  1: "ok",
  2: "slow",
  3: "beta",
};

async function json<T>(url: string, init?: RequestInit): Promise<T> {
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

export default function ReposPage() {
  const qc = useQueryClient();
  const [input, setInput] = useState("");
  const [expanded, setExpanded] = useState<string | null>(null);

  const repos = useQuery({
    queryKey: ["repos"],
    queryFn: () => json<RepoEntry[]>("/api/repos"),
  });

  const catalog = useQuery({
    queryKey: ["catalog"],
    queryFn: () => json<CatalogEntry[]>("/api/catalog"),
    staleTime: 5 * 60 * 1000,
  });

  const addRepo = useMutation({
    mutationFn: (value: string) =>
      json("/api/repos", { method: "POST", body: JSON.stringify({ input: value }) }),
    onSuccess: () => {
      setInput("");
      qc.invalidateQueries({ queryKey: ["repos"] });
    },
  });

  const removeRepo = useMutation({
    mutationFn: (url: string) =>
      json(`/api/repos?url=${encodeURIComponent(url)}`, { method: "DELETE" }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["repos"] }),
  });

  const submit = (e: React.FormEvent) => {
    e.preventDefault();
    if (input.trim()) addRepo.mutate(input.trim());
  };

  return (
    <section>
      <h1>Repositories</h1>
      <p className="muted">
        Add any CloudStream repository (full URL, cloudstreamrepo:// link, or a cutt.ly/py.md
        shortcode). Keep in mind: adding a repo runs third-party code from it on this host.
      </p>

      <form className="add-repo" onSubmit={submit}>
        <input
          type="text"
          placeholder="https://…/repo.json  ·  cloudstreamrepo://…  ·  shortcode"
          value={input}
          onChange={(e) => setInput(e.target.value)}
        />
        <button type="submit" disabled={addRepo.isPending || !input.trim()}>
          {addRepo.isPending ? "Adding…" : "Add"}
        </button>
      </form>
      {addRepo.isError && (
        <p className="bad">Could not add repository — {String(addRepo.error)}</p>
      )}

      <h2>Your repositories</h2>
      {repos.isPending && <p className="muted">Loading…</p>}
      {repos.isError && <p className="bad">Failed to load repositories.</p>}
      {repos.data && repos.data.length === 0 && (
        <p className="muted">
          none yet — see the suggestions below, or the upstream catalog at
          recloudstream/cs-repos
        </p>
      )}
      <div className="repo-list">
        {repos.data?.map((repo) => (
          <RepoRow
            key={repo.url}
            repo={repo}
            expanded={expanded === repo.url}
            onToggle={() => setExpanded(expanded === repo.url ? null : repo.url)}
            onRemove={() => removeRepo.mutate(repo.url)}
            removing={removeRepo.isPending}
          />
        ))}
      </div>

      <h2>Upstream catalog (recloudstream/cs-repos)</h2>
      <p className="muted">The official list published by the CloudStream upstream project.</p>
      {catalog.isPending && <p className="muted">Loading catalog…</p>}
      {catalog.isError && <p className="muted">Catalog unavailable (offline?).</p>}
      {catalog.data && (
        <div className="repo-list">
          {catalog.data.map((entry) => (
            <div className="card row" key={entry.url}>
              <div>
                <div className="mono small">{entry.url}</div>
                {entry.verified && <span className="badge good">verified</span>}
              </div>
              <button
                type="button"
                disabled={addRepo.isPending}
                onClick={() => addRepo.mutate(entry.url)}
              >
                Add
              </button>
            </div>
          ))}
        </div>
      )}
    </section>
  );
}

function RepoRow({
  repo,
  expanded,
  onToggle,
  onRemove,
  removing,
}: {
  repo: RepoEntry;
  expanded: boolean;
  onToggle: () => void;
  onRemove: () => void;
  removing: boolean;
}) {
  let host = repo.url;
  try {
    host = new URL(repo.url).host;
  } catch { /* keep raw */ }

  return (
    <div className="card">
      <div className="card-head row">
        <div>
          <div className="repo-name">
            {repo.name ?? "Unnamed repository"}{" "}
            <span className="badge">v{repo.manifestVersion}</span>
          </div>
          <div className="muted small mono">
            {host} {repo.enabled ? "" : "(disabled)"}
          </div>
          {repo.description && <div className="muted small">{repo.description}</div>}
        </div>
        <div className="row gap">
          <button type="button" onClick={onToggle}>
            {expanded ? "Hide plugins" : "Browse plugins"}
          </button>
          <button type="button" className="danger" onClick={onRemove} disabled={removing}>
            Remove
          </button>
        </div>
      </div>
      {expanded && <PluginTable repoUrl={repo.url} />}
    </div>
  );
}

function PluginTable({ repoUrl }: { repoUrl: string }) {
  const plugins = useQuery({
    queryKey: ["plugins", repoUrl],
    queryFn: () => json<PluginView[]>(`/api/repos/${b64url(repoUrl)}/plugins`),
  });

  if (plugins.isPending) return <p className="muted">Loading plugins…</p>;
  if (plugins.isError) return <p className="bad">Failed to load plugin list.</p>;
  if (!plugins.data?.length) return <p className="muted">No plugins in this repository.</p>;

  return (
    <table className="plugins">
      <thead>
        <tr>
          <th>Name</th>
          <th>Types</th>
          <th>Lang</th>
          <th>Status</th>
          <th>Ver</th>
          <th>Installed</th>
        </tr>
      </thead>
      <tbody>
        {plugins.data.map((p) => (
          <tr key={p.internalName + p.url}>
            <td>
              <div>{p.name}</div>
              <div className="muted small">{p.description ?? p.internalName}</div>
            </td>
            <td className="small">{(p.tvTypes ?? []).join(", ") || "—"}</td>
            <td className="small">{p.language ?? "—"}</td>
            <td>
              <span className={`badge ${p.status === 1 ? "good" : p.status === 0 ? "bad" : ""}`}>
                {STATUS_LABEL[p.status] ?? String(p.status)}
              </span>
            </td>
            <td className="small mono">{p.version}</td>
            <td>
              <span className="badge muted-badge" title="Installation arrives in Phase 2">
                not yet
              </span>
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}
