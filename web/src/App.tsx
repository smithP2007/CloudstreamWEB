import { NavLink, Route, Routes } from "react-router-dom";
import { useEffect, useState } from "react";
import { BrowseContent } from "./pages/BrowseContent";
import { SearchContent } from "./pages/SearchContent";
import { DetailContent } from "./pages/DetailContent";
import { LibraryPage } from "./pages/LibraryPage";
import { useQuery } from "@tanstack/react-query";
import ReposPage from "./pages/ReposPage";
import { getHealth, setAuthToken } from "./lib/api";

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
  /** Shown when the server answers 401 (CS_WEB_LAN_TOKEN mode). */
  const [needToken, setNeedToken] = useState(false);

  useEffect(() => {
    const on = () => setNeedToken(true);
    window.addEventListener("csweb-auth", on);
    return () => window.removeEventListener("csweb-auth", on);
  }, []);

  return (
    <div className="layout">
      {needToken && (
        <div className="auth-overlay">
          <form
            className="card auth-card"
            onSubmit={(e) => {
              e.preventDefault();
              const input = (e.currentTarget.elements.namedItem("token") as HTMLInputElement | null);
              const value = input?.value.trim() ?? "";
              if (!value) return;
              setAuthToken(value);
              window.location.reload(); // re-run every query with the stored token
            }}
          >
            <h2>Restricted server</h2>
            <p className="muted">
              This backend only serves /api requests with the shared LAN token (CS_WEB_LAN_TOKEN).
            </p>
            <input name="token" autoFocus placeholder="paste token" autoComplete="off" />
            <button type="submit">Save &amp; retry</button>
          </form>
        </div>
      )}
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
