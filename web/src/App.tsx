import { NavLink, Route, Routes } from "react-router-dom";
import { BrowseContent } from "./pages/BrowseContent";
import { SearchContent } from "./pages/SearchContent";
import { DetailContent } from "./pages/DetailContent";
import { LibraryPage } from "./pages/LibraryPage";
import { useQuery } from "@tanstack/react-query";
import ReposPage from "./pages/ReposPage";
import { getHealth } from "./lib/api";

async function fetchHealth(): Promise<{ ok: boolean; version: string; libraryAvailable: string }> {
  return getHealth();
}

const NAV = [
  { to: "/browse", label: "Browse" },
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
          <Route path="/browse" element={<BrowseContent />} />
          <Route path="/search" element={<SearchContent />} />
          <Route path="/detail" element={<DetailContent />} />
          <Route path="/repos" element={<ReposPage />} />
          <Route path="/library" element={<LibraryPage />} />
          <Route path="/settings" element={<Placeholder title="Settings" />} />
          <Route path="*" element={<NotFound />} />
        </Routes>
      </main>
    </div>
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
