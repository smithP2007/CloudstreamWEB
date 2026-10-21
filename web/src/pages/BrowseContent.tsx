import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { Link } from "react-router-dom";
import { getHome, getProviders, type ItemDto } from "../lib/api";

/** Browses provider home rows; every card opens its detail page. */
export function BrowseContent() {
  const providers = useQuery({ queryKey: ["providers"], queryFn: getProviders });
  const names = providers.data?.map((p) => p.name) ?? [];
  const [provider, setProvider] = useState<string | null>(null);
  const active = provider ?? names[0];

  const home = useQuery({
    queryKey: ["home", active],
    queryFn: () => getHome(active as string),
    enabled: !!active,
    retry: false,
  });

  return (
    <section>
      <h1>Browse</h1>
      {providers.isPending && <p className="muted">Loading providers…</p>}
      {names.length === 0 && !providers.isPending && (
        <p className="muted">No providers loaded — install one from the Repositories page.</p>
      )}
      {names.length > 0 && (
        <div className="provider-picker">
          <select
            value={active}
            onChange={(e) => setProvider(e.target.value)}
            aria-label="Provider"
          >
            {names.map((n) => (
              <option key={n} value={n}>{n}</option>
            ))}
          </select>
        </div>
      )}

      {home.isPending && <p className="muted">Loading rows… (upstream scraping, may take a while)</p>}
      {home.isError && (
        <p className="muted warn">
          This provider's home page failed: {(home.error as Error).message}. Some community
          providers and upstream sites go down without notice — try another provider.
        </p>
      )}
      {home.data?.rows
        .filter((r) => r.items.length > 0)
        .map((row) => (
          <div key={row.name}>
            <h2>{row.name}</h2>
            <div className="item-grid">
              {row.items.map((item, i) => (
                <ItemCard key={`${row.name}-${i}`} item={item} />
              ))}
            </div>
          </div>
        ))}
    </section>
  );
}

export function ItemCard({ item }: { item: ItemDto }) {
  return (
    <Link
      className="item-card"
      to={`/detail?provider=${encodeURIComponent(item.provider)}&url=${encodeURIComponent(item.url)}`}
    >
      {item.poster ? (
        <img src={item.poster} alt="" loading="lazy" />
      ) : (
        <div className="item-card-blank" />
      )}
      <div className="item-card-title">{item.name}</div>
      <div className="muted small">{item.type ?? ""}</div>
    </Link>
  );
}
