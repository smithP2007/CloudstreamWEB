import { useEffect, useMemo, useState } from "react";
import { useSearchParams } from "react-router-dom";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import {
  addBookmark,
  getDetail,
  getHistoryEntry,
  getStreams,
  isBookmarked,
  removeBookmark,
  type EpisodeDto,
} from "../lib/api";
import { Player, type PlayerMeta } from "./Player";

/**
 * Detail page: media info + episode strip. Selecting an episode loads its
 * stream links and mounts the Player (all links through /api/stream).
 *
 * Phase 4: a `?data=` URL parameter deep-links a specific episode (used by
 * Continue Watching), the hero carries a bookmark toggle, and the player gets
 * library meta + the episode's saved resume position.
 */
export function DetailContent() {
  const [params] = useSearchParams();
  const provider = params.get("provider") ?? "";
  const url = params.get("url") ?? "";
  const wantedData = params.get("data");

  const detail = useQuery({
    queryKey: ["detail", provider, url],
    queryFn: () => getDetail(provider, url),
    enabled: !!provider && !!url,
    retry: false,
  });

  const [episodeIndex, setEpisodeIndex] = useState(0);

  // deep link: /detail?...&data=<episodeData> selects that episode
  useEffect(() => {
    if (!detail.data || !wantedData) return;
    const i = detail.data.episodes.findIndex((e) => e.data === wantedData);
    if (i >= 0) setEpisodeIndex(i);
  }, [detail.data, wantedData]);

  const currentEpisode = detail.data?.episodes?.[episodeIndex];

  const streams = useQuery({
    queryKey: ["streams", provider, currentEpisode?.data],
    queryFn: () => getStreams(provider, currentEpisode!.data),
    enabled: !!currentEpisode,
    retry: false,
  });

  // saved watch position for the selected episode → Player seek
  const resume = useQuery({
    queryKey: ["history-entry", provider, url, currentEpisode?.data],
    queryFn: () => getHistoryEntry(provider, url, currentEpisode!.data),
    enabled: !!currentEpisode,
    retry: false,
  });

  const bookmarked = useQuery({
    queryKey: ["bookmark", provider, url],
    queryFn: () => isBookmarked(provider, url),
    enabled: !!provider && !!url,
  });

  const meta = useMemo<PlayerMeta | undefined>(() => {
    if (!detail.data || !currentEpisode) return undefined;
    const label =
      currentEpisode.episode != null
        ? currentEpisode.season != null
          ? `S${currentEpisode.season}E${currentEpisode.episode}`
          : `E${currentEpisode.episode}`
        : currentEpisode.name ?? null;
    return {
      provider,
      url,
      name: detail.data.name,
      episodeData: currentEpisode.data,
      episodeLabel: label,
      poster: detail.data.poster,
    };
  }, [provider, url, detail.data, currentEpisode]);

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
              <BookmarkToggle
                provider={provider}
                url={url}
                bookmarked={!!bookmarked.data}
                name={detail.data.name}
                poster={detail.data.poster}
                type={detail.data.type}
              />
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
          {streams.data && (
            <Player
              links={streams.data.links}
              subtitles={streams.data.subtitles}
              meta={meta}
              resumePosition={resume.data?.position}
            />
          )}
        </>
      )}
    </section>
  );
}

function BookmarkToggle({
  provider,
  url,
  bookmarked,
  name,
  poster,
  type,
}: {
  provider: string;
  url: string;
  bookmarked: boolean;
  name: string;
  poster?: string | null;
  type?: string | null;
}) {
  const qc = useQueryClient();
  const [busy, setBusy] = useState(false);

  const toggle = async () => {
    setBusy(true);
    try {
      if (bookmarked) {
        await removeBookmark(provider, url);
      } else {
        await addBookmark({ provider, url, name, poster, type });
      }
      qc.invalidateQueries({ queryKey: ["bookmark", provider, url] });
      qc.invalidateQueries({ queryKey: ["bookmarks"] });
    } finally {
      setBusy(false);
    }
  };

  return (
    <button className="bookmark-toggle" onClick={toggle} disabled={busy || !name}>
      {bookmarked ? "★ bookmarked" : "☆ bookmark"}
    </button>
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
