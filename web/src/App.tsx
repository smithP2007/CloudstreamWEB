import { NavLink, Route, Routes } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import ReposPage from "./pages/ReposPage";
import { getHealth, getProviders } from "./lib/api";

async function fetchHealth(): Promise<{ ok: boolean; version: string; libraryAvailable: string }> {
  return getHealth();
}

const NAV = [
  { to: "/", label: "Home" },
  { to: "/search", label: "Search" },
  { to: "/repos", label: "Repositories" },
  { to: "/library", label: "Library" },
  { to: "/settings", label: "Settings" },
];

function NotFound() {
  return <p className="muted">Nothing here yet.</p>;
}

export default function App() {
  const health = useQuery({ queryKey: ["health"], queryFn: fetchHealth });

  return (
    <div className="layout">
      <aside className="sidebar">
        <div className="brand">
          <span className="brand-mark">◉</span> CloudStream <span className="muted">web</span>
        </div>
        <nav>
          {NAV.map((n) => (
            <NavLink key={n.to} to={n.to} end className={({ isActive }) => (isActive ? "active" : "")}>
              {n.label}
            </NavLink>
          ))}
        </nav>
        <div className="status">
          {health.isPending && <span className="muted">connecting…</span>}
          {health.isError && <span className="bad">● backend not reachable</span>}
          {health.data?.ok && (
            <span className="good" title={health.data.libraryAvailable}>
              ● connected — upstream plugin API: {health.data.libraryAvailable}
            </span>
          )}
        </div>
      </aside>
      <main>
        <Routes>
          <Route path="/" element={<Home />} />
          <Route path="/search" element={<Placeholder title="Search" />} />
          <Route path="/repos" element={<ReposPage />} />
          <Route path="/library" element={<Placeholder title="Library" />} />
          <Route path="/settings" element={<Placeholder title="Settings" />} />
          <Route path="*" element={<NotFound />} />
        </Routes>
      </main>
    </div>
  );
}

function Home() {
  const providers = useQuery({ queryKey: ["providers"], queryFn: getProviders });

  return (
    <section>
      <h1>Home</h1>
      <p className="muted">
        Provider-driven rows will appear here once a repository is installed.
      </p>
      <h2>Active providers ({providers.data?.length ?? "…"})</h2>
      {providers.isPending && <p className="muted">Loading…</p>}
      {providers.isError && <p className="muted">Providers unavailable.</p>}
      {providers.data?.length === 0 && (
        <p className="muted">No providers loaded — install one from the Repositories page.</p>
      )}
      {providers.data?.map((p) => (
        <div className="card row" key={p.mainUrl + p.name}>
          <div>
            <div className="repo-name">{p.name}</div>
            <div className="muted small mono">{p.mainUrl} · {p.javaType}</div>
          </div>
          <div className="small">{p.tvTypes.join(", ")}</div>
        </div>
      ))}
    </section>
  );
}

function Placeholder({ title }: { title: string }) {
  return (
    <section>
      <h1>{title}</h1>
      <p className="muted">Coming in a later phase of the build plan.</p>
    </section>
  );
}
