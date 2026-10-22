import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import Hls from "hls.js";
import type { AudioTrackDto, StreamDto, SubtitleDto } from "../lib/api";
import { createDownload, getAudioTracks, getStreams, playbackCandidates, saveHistory, streamProxyUrl, subtitleProxyUrl } from "../lib/api";
import { toastError } from "../lib/toast";

/** What the library needs to attribute watch progress + downloads to an item. */
export type PlayerMeta = {
  provider: string;
  url: string;
  name: string;
  episodeData: string;
  episodeLabel?: string | null;
  poster?: string | null;
};

/**
 * Tries playback candidates in order: M3U8 links go through hls.js with the
 * proxy (every nested segment keeps its headers), direct VIDEO/FILE links are
 * played natively. A hard media error advances to the next candidate.
 *
 * Phase 4: watch progress is reported as history (~10s throttle + pause/end/
 * unmount), the player seeks to a saved resume position, provider subtitles
 * mount as proxied WebVTT tracks, and any playing link can be queued to disk.
 */
export function Player({
  links,
  subtitles,
  meta,
  resumePosition,
}: {
  links: StreamDto[];
  subtitles?: SubtitleDto[];
  meta?: PlayerMeta;
  resumePosition?: number;
}) {
  /** v2 M3: after a failed link is re-extracted, the fresh link set replaces
   * the prop's (the parent doesn't need to know). */
  const [freshLinks, setFreshLinks] = useState<StreamDto[] | null>(null);
  const [refreshing, setRefreshing] = useState(false);
  const candidates = useMemo(() => playbackCandidates(freshLinks ?? links), [links, freshLinks]);
  const [index, setIndex] = useState(0);
  const [muted, setMuted] = useState(false);
  const [downloadState, setDownloadState] = useState<string | null>(null);

  /** playhead of the active <video>, mirrored by useProgressBinding (ref writes only) */
  const livePosRef = useRef<number | null>(null);
  /**
   * When the user (or a failure) switches source mid-play, continue at the
   * current playhead instead of the episode's saved position. null → fall
   * back to the original resumePosition from history.
   */
  const [switchResume, setSwitchResume] = useState<number | null>(null);
  const effectiveResume = switchResume ?? resumePosition;

  useEffect(() => setIndex(0), [links]);
  useEffect(() => setSwitchResume(null), [links]);

  const indexRef = useRef(0);
  useEffect(() => { indexRef.current = index; }, [index]);

  /** capture where we are, then jump to candidate i */
  const pickSource = useCallback((i: number) => {
    if (i < 0 || i >= candidates.length) return;
    const pos = livePosRef.current;
    setSwitchResume(pos != null && pos > 1 ? Math.floor(pos) : null);
    setAudioPick(null); // audio ordinals belong to the previous source
    setIndex(i);
  }, [candidates.length]);

  /** stable advance: components keep it in their load effect deps */
  const advance = useCallback(() => {
    const pos = livePosRef.current;
    setSwitchResume(pos != null && pos > 1 ? Math.floor(pos) : null);
    setAudioPick(null); // ordinals of the old source mean nothing to the next
    setIndex(indexRef.current + 1);
  }, []);

  // ---- v2 M3: dead links self-heal -----------------------------------------
  // Stream URLs can die mid-play (signed links expire, hosters 403). On the
  // FIRST failure of a link we silently re-run the extractor once and swap in
  // the same source's fresh URL; only after that do we advance to the next
  // candidate. The ↺ button forces the same re-extract manually.

  const candidatesRef = useRef<StreamDto[]>([]);
  useEffect(() => { candidatesRef.current = candidates; }, [candidates]);

  const reExtractTried = useRef(new Set<string>());
  useEffect(() => {
    reExtractTried.current = new Set();
    setFreshLinks(null);
  }, [links]);

  /** one failure action at a time (onError + hlsError can both fire) */
  const failLock = useRef(0);

  const retrySameSource = useCallback(async (manual: boolean): Promise<boolean> => {
    const cur = candidatesRef.current[indexRef.current];
    if (!cur || !meta) return false;
    if (!manual) {
      if (reExtractTried.current.has(cur.url)) return false;
      reExtractTried.current.add(cur.url);
    }
    setRefreshing(true);
    try {
      const fresh = await getStreams(meta.provider, meta.episodeData, meta.url);
      const same = fresh.links.filter((l) => l.source === cur.source && l.quality === cur.quality);
      const match = same.find((l) => l.url !== cur.url) ?? same[0];
      if (!match) return false;
      const pos = livePosRef.current;
      if (pos != null && pos > 1) setSwitchResume(Math.floor(pos));
      // keep pointing at the same source inside the re-ranked list
      const ni = playbackCandidates(fresh.links).findIndex((l) => l.url === match.url);
      if (ni < 0) return false;
      setFreshLinks(fresh.links);
      setIndex(ni);
      return true;
    } catch {
      return false;
    } finally {
      setRefreshing(false);
    }
  }, [meta]);

  const handleFail = useCallback(() => {
    const now = Date.now();
    if (now - failLock.current < 1500) return;
    failLock.current = now;
    void (async () => {
      if (await retrySameSource(false)) return;
      advance();
    })();
  }, [retrySameSource, advance]);

  const refreshSources = useCallback(() => {
    void retrySameSource(true);
  }, [retrySameSource]);

  // ---- v2 M3: server-side audio language switching for muxed files ----------
  // The picker reports a track ordinal; the player re-sources the stream with
  // &audio=<ordinal>&start=<playhead> and the server remuxes (always -c copy).
  // switchResume is pinned to 0 so the browser-side resume seek can't fire on
  // top of the remux's own -ss (the fresh timeline starts at that point).
  const [audioPick, setAudioPick] = useState<{ ord: number; pos: number } | null>(null);
  useEffect(() => setAudioPick(null), [links]);

  const chooseAudioTrack = useCallback((ord: number) => {
    const pos = livePosRef.current;
    setSwitchResume(0);
    setAudioPick({ ord, pos: pos != null && pos > 1 ? Math.floor(pos) : 0 });
  }, []);

  const link = candidates[index];

  /** Watch-progress → /api/library/history (server auto-finishes near the end). */
  const reportProgress = useCallback(
    (position: number, duration: number, finished: boolean) => {
      if (!meta) return;
      if (!(duration > 0) || !(position > 0)) return;
      saveHistory({
        provider: meta.provider,
        url: meta.url,
        episodeData: meta.episodeData,
        name: meta.name,
        episodeLabel: meta.episodeLabel,
        poster: meta.poster,
        position,
        duration,
        finished,
      }).catch(() => { /* history is best-effort */ });
    },
    [meta],
  );

  /** Queue the currently playing link as a disk download. */
  const downloadCurrentLink = useCallback(async () => {
    if (!link) return;
    // extension hints live inside extractor names like "…1080p…mkv[2.33GB …]"
    const extHint = (/\.(mp4|mkv|webm|avi|mov|ts|m3u8)\b/i.exec(link.name)?.[1] ?? null) as string | null;
    setDownloadState("queueing…");
    try {
      await createDownload({
        provider: meta?.provider ?? null,
        url: meta?.url ?? null,
        name: meta ? `${meta.name}${meta.episodeLabel ? ` - ${meta.episodeLabel}` : ""}` : link.source,
        link: { url: link.url, headers: link.headers ?? {} },
        extHint,
      });
      setDownloadState("queued ✓");
    } catch (e) {
      setDownloadState(`failed: ${(e as Error).message}`);
      toastError("Download failed", e);
    }
    setTimeout(() => setDownloadState(null), 4000);
  }, [link, meta]);

  if (candidates.length === 0) {
    return <p className="muted">No playable links were extracted for this episode.</p>;
  }

  if (!link) {
    return (
      <div className="card">
        <p className="muted">
          Tried {candidates.length} link(s) — none played directly in the browser. Some mirrors need
          a signed session; press load again for a fresh link.
        </p>
      </div>
    );
  }

  return link.linkType === "M3U8" ? (
    <HlsPlayer
      link={link}
      candidates={candidates}
      onSourcePick={pickSource}
      livePosRef={livePosRef}
      muted={muted}
      setMuted={setMuted}
      reportProgress={reportProgress}
      resumePosition={effectiveResume}
      subtitles={subtitles}
      onFail={handleFail}
      index={index}
      total={candidates.length}
      onDownload={downloadCurrentLink}
      downloadState={downloadState}
      refreshing={refreshing}
      onRefresh={refreshSources}
    />
  ) : (
    <NativePlayer
      link={link}
      candidates={candidates}
      onSourcePick={pickSource}
      livePosRef={livePosRef}
      muted={muted}
      setMuted={setMuted}
      reportProgress={reportProgress}
      resumePosition={effectiveResume}
      subtitles={subtitles}
      onFail={handleFail}
      index={index}
      total={candidates.length}
      onDownload={downloadCurrentLink}
      downloadState={downloadState}
      refreshing={refreshing}
      onRefresh={refreshSources}
      audioPick={audioPick}
      onAudioPick={chooseAudioTrack}
    />
  );
}

type SharedPlayerProps = {
  link: StreamDto;
  /** the full ordered candidate list — the source picker lists them all */
  candidates: StreamDto[];
  /** switch to candidate i (from the picker or ◀/▶) */
  onSourcePick: (i: number) => void;
  /** parent ref mirroring the active playhead, for position-continuity on switch */
  livePosRef: { current: number | null };
  muted: boolean;
  setMuted: (m: boolean) => void;
  reportProgress: (pos: number, dur: number, finished: boolean) => void;
  resumePosition?: number;
  subtitles?: SubtitleDto[];
  onFail: () => void;
  index: number;
  total: number;
  onDownload: () => void;
  downloadState: string | null;
  /** v2 M3: re-run the extractor for the current source (↺ button state) */
  refreshing?: boolean;
  onRefresh?: () => void;
  /** v2 M3: server-side audio track pick (ordinal remuxed + playhead start) */
  audioPick?: { ord: number; pos: number } | null;
  onAudioPick?: (ord: number) => void;
};

/** Subtitle tracks (the same-origin /api/subtitle bridge converts SRT → WebVTT). */
function SubtitleTracks({ subtitles }: { subtitles?: SubtitleDto[] }) {
  if (!subtitles?.length) return null;
  return (
    <>
      {subtitles.map((s, i) => (
        <track
          key={`${i}-${s.url}`}
          src={subtitleProxyUrl(s)}
          srcLang={(s.lang || "und").split(/[-_ ]+/)[0]}
          label={s.lang || "subtitles"}
          kind="subtitles"
        />
      ))}
    </>
  );
}

/**
 * Our own subtitle selector (the browser's native CC menu is fine, but
 * invisible on a video without a menu bar). Selecting applies textTrack
 * modes immediately. hls.js/media-source attaches rebuild the track list
 * with default modes, so a poll re-applies the user's pick until the
 * media settles (~2 min window).
 */
function SubtitlePicker({
  videoRef,
  subtitles,
}: {
  videoRef: React.RefObject<HTMLVideoElement | null>;
  subtitles?: SubtitleDto[];
}) {
  const [selected, setSelected] = useState("off");
  const count = subtitles?.length ?? 0;

  const apply = useCallback((option: string) => {
    const v = videoRef.current;
    const tracks = v ? Array.from(v.textTracks) : [];
    tracks.forEach((t, i) => {
      t.mode = String(i) === option ? "showing" : "disabled";
    });
    setSelected(option);
  }, [videoRef]);

  useEffect(() => {
    setSelected("off");
  }, [count]);

  /** the picker UI only mirrors state; tracks are applied on change + restore */
  const onPick = (e: React.ChangeEvent<HTMLSelectElement>) => apply(e.target.value);

  /** Keep the user's choice after hls.js re-attaches media (modes reset). */
  useEffect(() => {
    if (selected === "off" || !count) return;
    let ticks = 0;
    const id = window.setInterval(() => {
      const v = videoRef.current;
      if (!v || v.textTracks.length === 0) return;
      const track = v.textTracks[Number(selected)];
      if (track && track.mode !== "showing") {
        track.mode = "showing";
      }
      if (++ticks > 240) window.clearInterval(id); // ~2 min settle window
    }, 500);
    return () => window.clearInterval(id);
  }, [selected, count, videoRef]);

  if (!count) return null;

  return (
    <select
      className="sub-pick"
      value={selected}
      onChange={onPick}
      title="Subtitle track"
      aria-label="Subtitle track"
    >
      <option value="off">subs off</option>
      {subtitles!.map((s, i) => (
        <option key={`${i}-${s.url}`} value={String(i)}>
          {s.lang || `subtitles ${i + 1}`}
        </option>
      ))}
    </select>
  );
}

type HlsAudioTrackLike = { id?: number; name?: string; lang?: string };

/**
 * Audio-track selector for HLS sources. hls.js lists every rendered audio
 * rendition (EXT-X-MEDIA groups); switching sets hls.audioTrack, and the
 * proxy already rewrites rendition playlists like any other manifest URL.
 * Hidden unless the stream actually offers more than one audio track.
 */
function HlsAudioPicker({ hls }: { hls: Hls | null }) {
  const [tracks, setTracks] = useState<HlsAudioTrackLike[] | null>(null);
  const [current, setCurrent] = useState(-1);

  useEffect(() => {
    if (!hls) {
      setTracks(null);
      setCurrent(-1);
      return;
    }
    const read = () => {
      const list = (hls.audioTracks ?? []) as unknown as HlsAudioTrackLike[];
      if (list.length > 1) {
        setTracks(list);
        setCurrent(hls.audioTrack);
      } else {
        setTracks(null);
        setCurrent(-1);
      }
    };
    read();
    // tracks arrive when the manifest is parsed; SWITCHED keeps the select honest
    hls.on(Hls.Events.AUDIO_TRACKS_UPDATED, read);
    hls.on(Hls.Events.AUDIO_TRACK_SWITCHED, read);
    return () => {
      hls.off(Hls.Events.AUDIO_TRACKS_UPDATED, read);
      hls.off(Hls.Events.AUDIO_TRACK_SWITCHED, read);
    };
  }, [hls]);

  if (!hls || !tracks || tracks.length < 2) return null;

  return (
    <select
      className="aud-pick"
      value={String(current)}
      onChange={(e) => {
        const i = Number(e.target.value);
        hls.audioTrack = i;
        setCurrent(i);
      }}
      title="Audio track"
      aria-label="Audio track"
    >
      {tracks.map((t, i) => (
        <option key={String(i)} value={String(i)}>
          {t.name || t.lang || `audio ${i + 1}`}
        </option>
      ))}
    </select>
  );
}

/**
 * Audio-track selector for direct (native <video>) sources, where the tracks
 * live on the media element. Chromium/Safari expose `audioTracks` for some
 * containers; browsers that don't simply never show this picker, so it's a
 * pure progressive enhancement.
 */
function NativeAudioPicker({
  videoRef,
  link,
}: {
  videoRef: React.RefObject<HTMLVideoElement | null>;
  link: StreamDto;
}) {
  type NativeAudioTrack = { label?: string; language?: string; enabled: boolean };
  type NativeAudioTrackList = { length: number; [i: number]: NativeAudioTrack };
  const [tracks, setTracks] = useState<NativeAudioTrack[] | null>(null);
  const [current, setCurrent] = useState(0);

  useEffect(() => {
    setTracks(null);
    setCurrent(0);
    let ticks = 0;
    const id = window.setInterval(() => {
      const at = (
        videoRef.current as unknown as { audioTracks?: NativeAudioTrackList } | null
      )?.audioTracks;
      const list = at ? Array.from(at) : null;
      if (list && list.length > 1) {
        setTracks(list);
        const enabled = list.findIndex((t) => t.enabled);
        setCurrent(Math.max(0, enabled));
        window.clearInterval(id);
      }
      if (++ticks > 120) window.clearInterval(id); // ~1 min: tracks appear after metadata
    }, 500);
    return () => window.clearInterval(id);
  }, [videoRef, link]); // link: reset for each switched source

  if (!tracks || tracks.length < 2) return null;

  const apply = (i: number) => {
    const at = (
      videoRef.current as unknown as { audioTracks?: NativeAudioTrackList } | null
    )?.audioTracks;
    if (at) Array.from(at).forEach((t, j) => { t.enabled = j === i; });
    setCurrent(i);
  };

  return (
    <select
      className="aud-pick"
      value={String(current)}
      onChange={(e) => apply(Number(e.target.value))}
      title="Audio track"
      aria-label="Audio track"
    >
      {tracks.map((t, i) => (
        <option key={String(i)} value={String(i)}>
          {t.label || t.language || `audio ${i + 1}`}
        </option>
      ))}
    </select>
  );
}

/**
 * v2 M3: language picker for muxed multi-audio FILES. Browsers can't switch
 * tracks inside a Matroska container (Chromium doesn't even play the container
 * without the server's remux), so switching happens server-side: pick a track,
 * the player re-sources through <code>?audio=<ordinal>&start=<playhead></code>
 * and the server remuxes with <code>-c copy</code>. Hidden whenever the browser
 * exposes native <code>audioTracks</code> (Safari handles mkv itself) or when
 * the probe finds nothing switchable.
 */
function ServerAudioPicker({
  videoRef,
  link,
  picked,
  onPick,
}: {
  videoRef: React.RefObject<HTMLVideoElement | null>;
  link: StreamDto;
  picked: number | null;
  onPick: (ordinal: number) => void;
}) {
  const [tracks, setTracks] = useState<AudioTrackDto[] | null>(null);
  const [nativeHandles, setNativeHandles] = useState(false);

  useEffect(() => {
    setTracks(null);
    setNativeHandles(false);
    let dead = false;
    // If the browser can enumerate native tracks, the native picker wins
    // (pure client switching). Chromium needs the server path regardless.
    let ticks = 0;
    const poll = window.setInterval(() => {
      const el = videoRef.current as unknown as { audioTracks?: { length: number } } | null;
      const len = el?.audioTracks?.length ?? 0;
      if (len > 0) {
        setNativeHandles(true);
        window.clearInterval(poll);
      } else if (++ticks > 8) {
        window.clearInterval(poll);
      }
    }, 250);
    getAudioTracks(link).then((t) => {
      if (!dead) setTracks(t);
    });
    return () => {
      dead = true;
      window.clearInterval(poll);
    };
  }, [link, videoRef]);

  if (nativeHandles || !tracks || tracks.length < 2) return null;

  const label = (t: AudioTrackDto): string => {
    try {
      if (t.lang && t.lang !== "und") {
        const Display = (
          Intl as unknown as {
            DisplayNames?: new (opts: { type: "language" }) => { of: (code: string) => string | undefined };
          }
        ).DisplayNames;
        if (Display) {
          const name = new Display({ type: "language" }).of(t.lang);
          if (name && name !== t.lang) return name;
        }
      }
    } catch {
      /* DisplayNames unavailable — fall through to metadata */
    }
    return t.title || t.codec || `audio ${t.ordinal + 1}`;
  };

  const current = picked ?? tracks.find((t) => t.isDefault)?.ordinal ?? 0;

  return (
    <select
      className="aud-pick"
      value={String(current)}
      onChange={(e) => onPick(Number(e.target.value))}
      title="Audio language"
      aria-label="Audio language"
    >
      {tracks.map((t) => (
        <option key={t.ordinal} value={String(t.ordinal)}>
          {label(t)}
        </option>
      ))}
    </select>
  );
}

function Controls({ link, candidates, index, onSourcePick, muted, toggleMute, onDownload, downloadState, videoRef, subtitles, audio, refreshing, onRefresh }: {
  link: StreamDto;
  candidates: StreamDto[];
  index: number;
  onSourcePick: (i: number) => void;
  muted: boolean;
  toggleMute: () => void;
  onDownload: () => void;
  downloadState: string | null;
  videoRef: React.RefObject<HTMLVideoElement | null>;
  subtitles?: SubtitleDto[];
  /** player-specific audio picker (<HlsAudioPicker>/<NativeAudioPicker>) */
  audio?: React.ReactNode;
  /** v2 M3: manual re-extract of the current source (self-heal backup) */
  refreshing?: boolean;
  onRefresh?: () => void;
}) {
  const total = candidates.length;
  return (
    <div className="player-controls small mono muted">
      <span className="source-switch">
        {total > 1 && (
          <>
            <button
              onClick={() => onSourcePick(index - 1)}
              disabled={index <= 0}
              title="Previous source"
              aria-label="Previous source"
            >
              ◀
            </button>{" "}
          </>
        )}
        <select
          className="src-pick"
          value={String(index)}
          onChange={(e) => onSourcePick(Number(e.target.value))}
          title="Switch source"
          aria-label="Video source"
          disabled={total < 2}
        >
          {candidates.map((c, i) => (
            <option key={`${i}-${c.url}`} value={String(i)}>
              {i + 1}/{total} · {c.source} · {c.quality > 0 ? `${c.quality}p` : c.linkType}
            </option>
          ))}
        </select>
        {total > 1 && (
          <>
            {" "}
            <button
              onClick={() => onSourcePick(index + 1)}
              disabled={index >= total - 1}
              title="Next source"
              aria-label="Next source"
            >
              ▶
            </button>
          </>
        )}
        {onRefresh && (
          <>
            {" "}
            <button
              onClick={onRefresh}
              disabled={!!refreshing}
              title="Refresh sources: re-extract fresh links for this episode"
              aria-label="Refresh sources"
            >
              {refreshing ? "…" : "↺"}
            </button>
          </>
        )}
      </span>
      <span>
        {audio ? (
          <>
            {audio}{" "}
          </>
        ) : null}
        <SubtitlePicker videoRef={videoRef} subtitles={subtitles} />{" "}
        <button onClick={toggleMute}>{muted ? "unmute" : "mute"}</button>{" "}
        <button onClick={onDownload} disabled={downloadState === "queueing…"}>
          {downloadState ?? "⬇ download"}
        </button>
      </span>
    </div>
  );
}

/**
 * Binds the standard progress/seek behavior to a <video>. Returns a controller:
 * `setResume(pos)` arms the resume seek (retrying once metadata exists), and
 * cleanup reports the last known position so navigating away mid-episode still
 * records progress.
 */
function bindProgressEvents(
  video: HTMLVideoElement,
  report: (pos: number, dur: number, finished: boolean) => void,
) {
  const state = {
    lastSent: 0,
    position: 0,
    duration: 0,
    resumePos: undefined as number | undefined,
  };
  let resumeDone = false;

  const trySeek = () => {
    const pos = state.resumePos;
    if (resumeDone || pos == null || !video.duration) return;
    // only from a fresh start (currentTime still near 0), never over a scrub
    if (pos > 5 && video.currentTime < 5 && pos < video.duration - 30) {
      video.currentTime = pos;
    }
    resumeDone = true;
  };

  const maybeSave = (force = false, finished = false) => {
    const now = Date.now();
    if (!force && now - state.lastSent < 10_000) return;
    state.lastSent = now;
    report(state.position, state.duration, finished);
  };
  const onTime = () => {
    state.position = video.currentTime;
    state.duration = video.duration;
    maybeSave();
  };
  const onPause = () => {
    state.position = video.currentTime;
    state.duration = video.duration;
    maybeSave(true);
  };
  const onEnded = () => {
    state.position = state.duration;
    maybeSave(true, true);
  };

  video.addEventListener("loadedmetadata", trySeek);
  video.addEventListener("timeupdate", onTime);
  video.addEventListener("pause", onPause);
  video.addEventListener("ended", onEnded);
  // metadata may already be here (cache): attempt an immediate seek
  if (video.readyState >= 1) trySeek();

  return {
    setResume(pos?: number) {
      if (state.resumePos === pos) return;
      state.resumePos = pos;
      trySeek();
    },
    /** last known playhead — used to continue at the same spot when sources switch */
    getPosition() {
      return state.position;
    },
    unbind(finalReport = false) {
      video.removeEventListener("loadedmetadata", trySeek);
      video.removeEventListener("timeupdate", onTime);
      video.removeEventListener("pause", onPause);
      video.removeEventListener("ended", onEnded);
      if (finalReport && state.duration > 0 && state.position > 5) {
        report(state.position, state.duration, false);
      }
    },
  };
}

type ProgressController = ReturnType<typeof bindProgressEvents>;

function useProgressBinding(
  link: StreamDto,
  reportProgress: (pos: number, dur: number, finished: boolean) => void,
  livePosRef?: { current: number | null },
) {
  const videoRef = useRef<HTMLVideoElement>(null);
  const progressRef = useRef<ProgressController | null>(null);

  const bind = useCallback(() => {
    const video = videoRef.current;
    if (!video) return null;
    progressRef.current?.unbind();
    const controller = bindProgressEvents(video, reportProgress);
    progressRef.current = controller;
    return controller;
  }, [reportProgress]);

  useEffect(() => {
    const controller = bind();
    return () => {
      controller?.unbind(true);
      if (progressRef.current === controller) progressRef.current = null;
    };
  }, [bind, link]);

  // Mirror the playhead into the parent's ref (~2x/s, ref writes only —
  // never re-renders). The parent reads it when switching sources so the
  // next candidate continues at the same timestamp.
  useEffect(() => {
    if (!livePosRef) return;
    const id = window.setInterval(() => {
      const pos = progressRef.current?.getPosition();
      if (pos != null && pos > 0) livePosRef.current = pos;
    }, 500);
    return () => window.clearInterval(id);
  }, [livePosRef]);

  return { videoRef, progressRef };
}

/**
 * Player keyboard shortcuts (space play/pause, arrows ±5s, PageUp/Down ±30s,
 * m mute, f fullscreen). Ignored while typing in inputs/selects.
 */
function useKeyboardControls(
  videoRef: React.RefObject<HTMLVideoElement | null>,
  setMutedBoth: (m: boolean) => void,
) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const t = e.target as HTMLElement | null;
      if (t && (t.tagName === "INPUT" || t.tagName === "TEXTAREA" || t.tagName === "SELECT")) return;
      const video = videoRef.current;
      if (!video) return;
      switch (e.key) {
        case " ":
          e.preventDefault();
          if (video.paused) video.play().catch(() => {});
          else video.pause();
          break;
        case "ArrowLeft":
          e.preventDefault();
          video.currentTime = Math.max(0, video.currentTime - 5);
          break;
        case "ArrowRight":
          e.preventDefault();
          video.currentTime = Math.min(video.duration || Infinity, video.currentTime + 5);
          break;
        case "PageUp":
        case "PageDown": {
          e.preventDefault();
          const step = e.key === "PageUp" ? 30 : -30;
          const clamped = Math.min(video.duration || Infinity, Math.max(0, video.currentTime + step));
          video.currentTime = clamped;
          break;
        }
        case "m":
        case "M":
          setMutedBoth(!video.muted);
          break;
        case "f":
        case "F":
          if (document.fullscreenElement) document.exitFullscreen().catch(() => {});
          else video.requestFullscreen?.().catch(() => {});
          break;
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [videoRef, setMutedBoth]);
}

function HlsPlayer(props: SharedPlayerProps) {
  const { link, candidates, onSourcePick, livePosRef, muted, setMuted, reportProgress, resumePosition, subtitles, onFail, index, total, onDownload, downloadState, refreshing, onRefresh } = props;
  const { videoRef, progressRef } = useProgressBinding(link, reportProgress, livePosRef);
  const [mutedLocal, setMutedLocal] = useState(muted);
  const setMutedBoth = useCallback((m: boolean) => {
    setMutedLocal(m);
    setMuted(m);
  }, [setMuted]);
  useKeyboardControls(videoRef, setMutedBoth);

  // instance lifted into state so the audio picker can enumerate/switch tracks
  const [hlsInstance, setHlsInstance] = useState<Hls | null>(null);

  useEffect(() => {
    const video = videoRef.current;
    if (!video) return;

    const hls = new Hls({
      // the proxy rewrites every segment URL already; keep in-buffer behavior default
      enableWorker: true,
    });
    setHlsInstance(hls);
    hls.loadSource(streamProxyUrl(link));
    hls.attachMedia(video);
    hls.on(Hls.Events.ERROR, (_e, data) => {
      if (data.fatal) {
        hls.destroy();
        onFail();
      }
    });

    return () => {
      setHlsInstance(null);
      hls.destroy();
    };
  }, [videoRef, link, onFail]);

  // resume position may arrive before or after bind — feed it to the controller
  useEffect(() => {
    progressRef.current?.setResume(resumePosition);
  }, [progressRef, resumePosition, link]);

  return (
    <div className="player">
      <video ref={videoRef} controls autoPlay muted={mutedLocal} playsInline>
        <SubtitleTracks subtitles={subtitles} />
      </video>
      <Controls
        link={link}
        candidates={candidates}
        index={index}
        onSourcePick={onSourcePick}
        muted={mutedLocal}
        toggleMute={() => {
          setMutedLocal(!mutedLocal);
          setMuted(!mutedLocal);
        }}
        onDownload={onDownload}
        downloadState={downloadState}
        videoRef={videoRef}
        subtitles={subtitles}
        audio={<HlsAudioPicker hls={hlsInstance} />}
        refreshing={refreshing}
        onRefresh={onRefresh}
      />
    </div>
  );
}

function NativePlayer(props: SharedPlayerProps) {
  const { link, candidates, onSourcePick, livePosRef, muted, setMuted, reportProgress, resumePosition, subtitles, onFail, index, total, onDownload, downloadState, refreshing, onRefresh, audioPick, onAudioPick } = props;
  const { videoRef, progressRef } = useProgressBinding(link, reportProgress, livePosRef);
  const [mutedLocal, setMutedLocal] = useState(muted);
  const setMutedBoth = useCallback((m: boolean) => {
    setMutedLocal(m);
    setMuted(m);
  }, [setMuted]);
  useKeyboardControls(videoRef, setMutedBoth);

  // resume position may arrive before or after bind — feed it to the controller
  useEffect(() => {
    progressRef.current?.setResume(resumePosition);
  }, [progressRef, resumePosition, link]);

  /**
   * v2 M3: the effective source. A picked audio ordinal remuxes the stream
   * server-side; &start carries the captured playhead so the switch is
   * seamless (the fresh timeline begins at the seek point, and the parent
   * pinned switchResume to 0 so no browser-side resume seek can stack on it).
   */
  const streamSrc = useMemo(() => {
    let src = streamProxyUrl(link);
    if (audioPick) {
      src += `&audio=${audioPick.ord}`;
      if (audioPick.pos > 0) src += `&start=${audioPick.pos}`;
    }
    return src;
  }, [link, audioPick]);

  return (
    <div className="player">
      <video
        key={streamSrc}
        ref={videoRef}
        controls
        autoPlay
        muted={mutedLocal}
        playsInline
        onError={onFail}
        src={streamSrc}
      >
        <SubtitleTracks subtitles={subtitles} />
      </video>
      <Controls
        link={link}
        candidates={candidates}
        index={index}
        onSourcePick={onSourcePick}
        muted={mutedLocal}
        toggleMute={() => {
          setMutedLocal(!mutedLocal);
          setMuted(!mutedLocal);
        }}
        onDownload={onDownload}
        downloadState={downloadState}
        videoRef={videoRef}
        subtitles={subtitles}
        audio={
          <ServerAudioPicker
            videoRef={videoRef}
            link={link}
            picked={audioPick?.ord ?? null}
            onPick={(ord) => onAudioPick?.(ord)}
          />
        }
        refreshing={refreshing}
        onRefresh={onRefresh}
      />
    </div>
  );
}
