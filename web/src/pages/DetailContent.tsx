import { useState } from "react";
import { useSearchParams } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import { getDetail, getStreams, type EpisodeDto } from "../lib/api";
import { Player } from "./Player";

/**
 * Detail page: media info + episode strip. Selecting an episode loads its
 * stream links and mounts the Player (all links through /api/stream).
 */
export function DetailContent() {
  const [params] = useSearchParams();
  const provider = params.get("provider") ?? "";
  const url = params.get("url") ?? "";

  const detail = useQuery({
    queryKey: ["detail", provider, url],
    queryFn: () => getDetail(provider, url),
    enabled: !!provider && !!url,
    retry: false,
  });

  const [episodeIndex, setEpisodeIndex] = useState(0);

  const streams = useQuery({
    queryKey: ["streams", provider, detail.data?.episodes?.[episodeIndex]?.data],
    queryFn: () => getStreams(provider, detail.data!.episodes[episodeIndex].data),
    enabled: !!detail.data?.episodes?.[episodeIndex],
    retry: false,
  });

  if (!provider || !url) return <p className="muted">Missing provider/url parameters.</p>;

  return (
    <section>
      {detail.isPending && <p className="muted">Loading details…</p>}
      {detail.isError && (
        <>
          <h1>Availability error</h1>
          <p className="muted warn">
            {(detail.error as Error).message} — many community providers mirror sites that
            block or rotate; try the same item on another provider from Search.
          </p>
        </>
      )}

      {detail.data && (
        <>
          <div className="detail-hero">
            {detail.data.poster && <img src={detail.data.poster} alt="" />}
            <div>
              <h1>{detail.data.name}</h1>
              <div className="muted small">
                {detail.data.type}
                {detail.data.year ? ` · ${detail.data.year}` : ""}
                {detail.data.durationMinutes ? ` · ${detail.data.durationMinutes} min` : ""}
                {detail.data.tags?.length ? ` · ${detail.data.tags.slice(0, 4).join(", ")}` : ""}
              </div>
              {detail.data.plot && <p className="muted">{detail.data.plot.slice(0, 500)}</p>}
            </div>
          </div>

          <h2>Episodes</h2>
          <div className="episode-strip">
            {detail.data.episodes.map((ep, i) => (
              <EpisodeBadge
                key={`${i}-${ep.data}`}
                ep={ep}
                active={i === episodeIndex}
                onClick={() => setEpisodeIndex(i)}
              />
            ))}
          </div>

          <h2>Player</h2>
          {streams.isPending && <p className="muted">Resolving stream links… (extractors run upstream)</p>}
          {streams.isError && (
            <p className="muted warn">Stream resolution failed: {(streams.error as Error).message}</p>
          )}
          {streams.data && <Player links={streams.data.links} />}
        </>
      )}
    </section>
  );
}

function EpisodeBadge({ ep, active, onClick }: { ep: EpisodeDto; active: boolean; onClick: () => void }) {
  const label =
    ep.episode != null
      ? ep.season != null ? `S${ep.season}E${ep.episode}` : `E${ep.episode}`
      : ep.name ?? "Play";
  return (
    <button className={`episode-badge${active ? " active" : ""}`} onClick={onClick} title={ep.name ?? ""}>
      {ep.poster && <img src={ep.poster} alt="" loading="lazy" />}
      <span>{label}</span>
    </button>
  );
}
