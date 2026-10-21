import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { getProviders, search } from "../lib/api";
import { ItemCard } from "./BrowseContent";

/** Search across whatever provider the user selects. */
export function SearchContent() {
  const providers = useQuery({ queryKey: ["providers"], queryFn: getProviders });
  const names = providers.data?.map((p) => p.name) ?? [];
  const [provider, setProvider] = useState<string | null>(null);
  const [q, setQ] = useState("");
  const [submitted, setSubmitted] = useState<{ provider: string; q: string } | null>(null);

  const results = useQuery({
    queryKey: ["search", submitted?.provider, submitted?.q],
    queryFn: () => search(submitted!.provider, submitted!.q),
    enabled: !!submitted,
    retry: false,
  });

  const active = provider ?? names[0];

  return (
    <section>
      <h1>Search</h1>
      <form
        className="search-form"
        onSubmit={(e) => {
          e.preventDefault();
          if (active && q.trim()) setSubmitted({ provider: active, q: q.trim() });
        }}
      >
        <select value={active} onChange={(e) => setProvider(e.target.value)} aria-label="Provider">
          {names.map((n) => (
            <option key={n} value={n}>{n}</option>
          ))}
        </select>
        <input
          value={q}
          onChange={(e) => setQ(e.target.value)}
          placeholder="Search a movie, show, channel…"
          autoFocus
        />
        <button type="submit">Search</button>
      </form>

      {names.length === 0 && !providers.isPending && (
        <p className="muted">No providers loaded yet — install one from the Repositories page.</p>
      )}
      {results.isPending && submitted && <p className="muted">Searching…</p>}
      {results.isError && (
        <p className="muted warn">
          Search failed: {(results.error as Error).message}. Upstream endpoints move often; try
          another provider.
        </p>
      )}
      <div className="item-grid">
        {results.data?.items.map((item, i) => <ItemCard key={`${i}-${item.url}`} item={item} />)}
      </div>
    </section>
  );
}
