import { NavLink, Route, Routes } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";

async function fetchHealth(): Promise<{ ok: boolean; version: string; libraryAvailable: string }> {
  const res = await fetch("/api/health");
  if (!res.ok) throw new Error(`health ${res.status}`);
  return res.json();
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
          <Route path="/repos" element={<Placeholder title="Repositories" />} />
          <Route path="/library" element={<Placeholder title="Library" />} />
          <Route path="/settings" element={<Placeholder title="Settings" />} />
          <Route path="*" element={<NotFound />} />
        </Routes>
      </main>
    </div>
  );
}

function Home() {
  return (
    <section>
      <h1>Home</h1>
      <p className="muted">
        Provider-driven rows will appear here once a repository is installed.
      </p>
      <p className="muted">
        Next step in the build (Phase 2): install the upstream extensions repo and load real
        CloudStream plugins against this JVM backend.
      </p>
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
