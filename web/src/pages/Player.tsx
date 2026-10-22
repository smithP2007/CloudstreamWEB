import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import Hls from "hls.js";
import type { AudioTrackDto, LinkProbe, StreamDto, SubtitleDto, VideoProbe } from "../lib/api";
import { createDownload, getAudioTracks, getLinkProbe, getSettings, getStreams, playbackCandidates, saveHistory, streamProxyUrl, subtitleProxyUrl } from "../lib/api";
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

  // ---- v2 M4: decode truth ---------------------------------------------------
  // Every FILE candidate gets an ffprobe (server-cached through the engine
  // proxy, probe truth == playback truth) plus the browser's own
  // MediaCapabilities verdict for the probed codec face. probeSources off
  // disables all of it: no probes, no badges, no reranking, no drawer.
  const probeSettings = usePlaybackSettings();

  /** mirrors the currently playing url — read by useProbes so the active link
   * is probed first (badges + drawer fill while the rest are still running). */
  const currentUrlRef = useRef<string | null>(null);

  const { probes, done: probesDone } = useProbes(candidates, probeSettings.probeSources, currentUrlRef);
  const ordered = usePlayableFirstOrder(candidates, probes, probesDone, indexRef, setIndex);
  const playerCandidates = ordered ?? candidates;
  currentUrlRef.current = playerCandidates[index]?.url ?? null;

  /** badge text for a candidate ("": off or no probe data) */
  const badgeFor = useCallback(
    (c: StreamDto): string => (probeSettings.probeSources ? describeDecode(probes.get(c.url)) : ""),
    [probeSettings.probeSources, probes],
  );

  /** capture where we are, then jump to candidate i */
  const pickSource = useCallback((i: number) => {
    if (i < 0 || i >= playerCandidates.length) return;
    const pos = livePosRef.current;
    setSwitchResume(pos != null && pos > 1 ? Math.floor(pos) : null);
    setAudioPick(null); // audio ordinals belong to the previous source
    setIndex(i);
  }, [playerCandidates.length]);

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

  const link = playerCandidates[index];

  /** v2 M4: "What am I watching?" block — the current link's probe truth. */
  const watchInfo = probeSettings.probeSources && link ? (
    <LinkWatchInfo info={link.linkType === "M3U8" ? HLS_INFO : (probes.get(link.url) ?? null)} />
  ) : null;

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
      candidates={playerCandidates}
      onSourcePick={pickSource}
      livePosRef={livePosRef}
      muted={muted}
      setMuted={setMuted}
      reportProgress={reportProgress}
      resumePosition={effectiveResume}
      subtitles={subtitles}
      onFail={handleFail}
      index={index}
      total={playerCandidates.length}
      onDownload={downloadCurrentLink}
      downloadState={downloadState}
      refreshing={refreshing}
      onRefresh={refreshSources}
      badgeFor={badgeFor}
      watchdog={probeSettings.bufferWatchdog}
      watchInfo={watchInfo}
    />
  ) : (
    <NativePlayer
      link={link}
      candidates={playerCandidates}
      onSourcePick={pickSource}
      livePosRef={livePosRef}
      muted={muted}
      setMuted={setMuted}
      reportProgress={reportProgress}
      resumePosition={effectiveResume}
      subtitles={subtitles}
      onFail={handleFail}
      index={index}
      total={playerCandidates.length}
      onDownload={downloadCurrentLink}
      downloadState={downloadState}
      refreshing={refreshing}
      onRefresh={refreshSources}
      audioPick={audioPick}
      onAudioPick={chooseAudioTrack}
      badgeFor={badgeFor}
      watchdog={probeSettings.bufferWatchdog}
      watchInfo={watchInfo}
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
  /** v2 M4: badge text for a candidate option ("": off or no probe data) */
  badgeFor?: (c: StreamDto) => string;
  /** v2 M4: buffer watchdog enabled (settings toggle) */
  watchdog?: boolean;
  /** v2 M4: "What am I watching?" block (current link's probe truth) */
  watchInfo?: React.ReactNode;
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

// ---------- v2 M4: decode truth (probes, badges, ranking, drawer, watchdog) ----

/** Server ffprobe result + the browser's own decode verdict for one link. */
export type ProbeInfo = {
  probe: LinkProbe | null;       // null: probe failed server-side (timeout etc.)
  verdict: DecodeVerdict | null; // null: no MediaCapabilities answer for the codec
};

export type DecodeVerdict = {
  supported: boolean;
  smooth: boolean;
  powerEfficient: boolean;
};

/** Synthetic info for the drawer when the current link is HLS (never probed). */
const HLS_INFO: ProbeInfo = {
  verdict: null,
  probe: {
    container: null,
    formatName: null,
    durationSec: null,
    bitRate: null,
    video: null,
    audio: [],
    subtitlesCount: null,
    streamed: true,
  },
};

/**
 * MediaCapabilities config string for a probed video face. null for codecs we
 * can't describe (mpeg2 etc.) — those keep probed facts in badges but get no
 * ✓/✗ verdict. Chromium software-decodes High10 H.264, so 10-bit h264 is still
 * described with an avc1 string.
 */
function mediaTypeFor(v: VideoProbe): string | null {
  switch (v.codec) {
    case "h264":
      return 'video/mp4; codecs="avc1.640034"'; // High@5.2
    case "hevc":
      return 'video/mp4; codecs="hvc1.1.6.L153.B0"';
    case "vp9":
      return (v.bitDepth === 10 || v.bitDepth === 12)
        ? 'video/mp4; codecs="vp09.00.10.10"'
        : 'video/mp4; codecs="vp09.00.10.08"';
    case "av1":
      return 'video/mp4; codecs="av01.0.06M.08"';
    default:
      return null;
  }
}

/** The browser's answer to "can I decode this?" for a probed video. */
async function decodeVerdictOf(probe: LinkProbe): Promise<DecodeVerdict | null> {
  const v = probe.video;
  if (probe.streamed || !v) return null;
  const mime = mediaTypeFor(v);
  if (!mime) return null;
  type McLike = {
    decodingInfo?: (
      config: unknown,
    ) => Promise<{ supported: boolean; smooth: boolean; powerEfficient: boolean }>;
  };
  const mc = (navigator as unknown as { mediaCapabilities?: McLike }).mediaCapabilities;
  if (!mc?.decodingInfo) return null;
  const config = {
    type: "file",
    video: {
      contentType: mime,
      width: v.width ?? 1280,
      height: v.height ?? 720,
      bitrate: probe.bitRate ?? 3_000_000,
      framerate: v.fps && v.fps > 0 ? Math.min(240, v.fps) : 30,
    },
  };
  try {
    const info = await mc.decodingInfo(config);
    return { supported: info.supported, smooth: info.smooth, powerEfficient: info.powerEfficient };
  } catch {
    return null;
  }
}

/** Probe every distinct FILE candidate (≤4 concurrent); the preferred (currently
 * playing) link is probed first. HLS links are skipped — the server short-
 * circuits them to `streamed` and there's nothing to learn for badges. */
function useProbes(
  candidates: StreamDto[],
  enabled: boolean,
  preferredRef: { current: string | null },
): { probes: Map<string, ProbeInfo>; done: boolean } {
  const [probes, setProbes] = useState<Map<string, ProbeInfo>>(new Map());
  const [done, setDone] = useState(false);

  useEffect(() => {
    if (!enabled) {
      setProbes(new Map());
      setDone(false);
      return;
    }
    const seen = new Set<string>();
    const targets: StreamDto[] = [];
    for (const c of candidates) {
      if (c.linkType === "M3U8" || seen.has(c.url)) continue;
      seen.add(c.url);
      targets.push(c);
    }
    const preferred = preferredRef.current;
    if (preferred) {
      targets.sort((a, b) => (a.url === preferred ? -1 : 0) - (b.url === preferred ? -1 : 0));
    }
    if (targets.length === 0) {
      setProbes(new Map());
      setDone(true);
      return;
    }
    setDone(false);
    let dead = false;
    const acc = new Map<string, ProbeInfo>();
    let cursor = 0;
    const worker = async (): Promise<void> => {
      while (cursor < targets.length) {
        const c = targets[cursor++];
        if (dead) return;
        const probe = await getLinkProbe(c);
        if (dead) return;
        const verdict = probe ? await decodeVerdictOf(probe) : null;
        if (dead) return;
        acc.set(c.url, { probe, verdict });
        setProbes(new Map(acc));
      }
    };
    void Promise.all([worker(), worker(), worker(), worker()]).then(() => {
      if (!dead) setDone(true);
    });
    return () => { dead = true; };
  }, [candidates, enabled]); // preferredRef is read once per batch on purpose
  return { probes, done };
}

/** 0 = certainly smooth … 5 = certainly undecodable; ties keep original order. */
function decodeScore(c: StreamDto, probes: Map<string, ProbeInfo>): number {
  const info = probes.get(c.url);
  if (!info) return 4; // HLS or not probed yet
  const v = info.verdict;
  // any link without usable truth (probe failed, HLS, no video, unknown codec)
  // stays NEUTRAL: a timed-out probe usually means a dead mirror — it must not
  // outrank anything, and unknown codec facts can't be trusted for ranking
  if (!v || !info.probe || info.probe.streamed || !info.probe.video) return 4;
  if (!v.supported) return 5;
  return v.smooth ? 0 : 1;
}

/** Playable-first stable reorder. */
function rankPlayableFirst(cands: StreamDto[], probes: Map<string, ProbeInfo>): StreamDto[] {
  return [...cands].sort((a, b) => decodeScore(a, probes) - decodeScore(b, probes));
}

/**
 * Applies [rankPlayableFirst] ONCE per candidate batch (never re-shuffles
 * mid-play), after the whole probe batch has finished; only picker order
 * changes — the currently playing link keeps playing (its index is remapped
 * by url identity).
 */
function usePlayableFirstOrder(
  candidates: StreamDto[],
  probes: Map<string, ProbeInfo>,
  done: boolean,
  indexRef: { current: number },
  setIndex: (i: number) => void,
): StreamDto[] | null {
  const [ordered, setOrdered] = useState<StreamDto[] | null>(null);
  const applied = useRef(false);
  /** non-zero after the batch deadline — ordering fires even if probes lag */
  const [deadline, setDeadline] = useState(0);

  // a new candidate batch (new extraction) re-arms ranking + drops the order
  useEffect(() => {
    applied.current = false;
    setOrdered(null);
    const id = window.setTimeout(() => {
      // probe stragglers (slow hosters) must not hold the ranking hostage:
      // after 25s order by whatever truth exists (unprobed stays neutral)
      setDeadline(Date.now());
    }, 25_000);
    return () => window.clearTimeout(id);
  }, [candidates]);

  useEffect(() => {
    if (applied.current) return;
    if (!done && deadline === 0) return;
    applied.current = true;
    if (probes.size === 0) return;
    const next = rankPlayableFirst(candidates, probes);
    setOrdered(next);
    const current = candidates[indexRef.current] ?? null;
    if (current) {
      const ni = next.findIndex((c) => c.url === current.url);
      if (ni >= 0 && ni !== indexRef.current) setIndex(ni);
    }
    // candidates/indexRef/setIndex are stable within a batch on purpose
  }, [done, deadline, probes]);
  return ordered;
}

/** Picker-option badge: mark + codec face, e.g. "✓ h264 1080p 10bit". */
function describeDecode(info?: ProbeInfo | null): string {
  if (!info) return "";
  if (!info.probe) return "? no probe";
  if (info.probe.streamed) return "HLS";
  const v = info.probe.video;
  if (!v) return "? no video";
  const res =
    v.height != null
      ? v.height >= 2160 ? "4k" : v.height >= 400 ? `${v.height}p` : `${v.height}`
      : "";
  const bits = v.bitDepth != null && v.bitDepth > 8 ? `${v.bitDepth}bit` : "";
  const hdr = v.hdr ? "HDR" : "";
  const codec = v.codec ?? "";
  const face = [res, codec, bits, hdr].filter(Boolean).join(" ");
  let mark = "";
  if (info.verdict) {
    if (!info.verdict.supported) mark = "✗ ";
    else if (info.verdict.smooth) mark = "✓ ";
    else mark = "△ "; // supported but not "smooth" (software decode)
  }
  return mark + face;
}

function verdictSentence(v: DecodeVerdict | null): string {
  if (!v) return "not known — this browser didn't answer for this codec";
  if (!v.supported) return "NOT supported by this browser — this source was ranked last for a reason";
  return v.smooth
    ? "supported and expected to play smoothly"
    : "supported via software decode — may stutter on weak hardware";
}

/** v2 M4 drawer: the probe truth of the current link, human-readable. */
function LinkWatchInfo({ info }: { info: ProbeInfo | null }) {
  return (
    <details className="watch-info">
      <summary>What am I watching?</summary>
      {!info ? (
        <p className="muted small">probing this source…</p>
      ) : !info.probe ? (
        <p className="muted small">no probe data for this link (server probe failed)</p>
      ) : info.probe.streamed || !info.probe.video ? (
        <p className="muted small">streamed playlist (HLS) — per-file details aren't probed</p>
      ) : (
        (() => {
          const p = info.probe;
          const v = p.video!;
          const audio = p.audio.map((t) => t.lang && t.lang !== "und" ? t.lang : t.title ?? `#${t.ordinal + 1}`);
          return (
            <ul>
              <li>
                <b>container:</b> {p.container ?? "?"}
                {p.durationSec != null ? ` · ${Math.round(p.durationSec / 60)} min` : ""}
                {p.bitRate != null ? ` · ${Math.round((p.bitRate / 1_000_000) * 10) / 10} Mb/s` : ""}
              </li>
              <li>
                <b>video:</b> {v.codec} {v.profile ?? ""}
                {v.width != null && v.height != null ? ` · ${v.width}×${v.height}` : ""}
                {v.fps ? ` @ ${v.fps.toFixed(1)}fps` : ""}
                {v.bitDepth != null && v.bitDepth > 8 ? ` · ${v.bitDepth}-bit` : ""}
                {v.hdr ? " · HDR" : ""}
              </li>
              {p.audio.length > 0 && (
                <li><b>audio:</b> {p.audio.length} track{p.audio.length > 1 ? "s" : ""}{audio.length ? ` (${audio.join(", ")})` : ""}</li>
              )}
              {p.subtitlesCount != null && <li><b>subtitles:</b> {p.subtitlesCount}</li>}
              <li><b>browser decode:</b> {verdictSentence(info.verdict)}</li>
            </ul>
          );
        })()
      )}
    </details>
  );
}

/** Settings for the player portion of playback toggles; server defaults win
 * while loading (probe+watchdog are safe defaults). */
function usePlaybackSettings(): { probeSources: boolean; bufferWatchdog: boolean } {
  const [s, setS] = useState({ probeSources: true, bufferWatchdog: true });
  useEffect(() => {
    let dead = false;
    getSettings()
      .then((v) => {
        if (!dead) setS({ probeSources: v.probeSources, bufferWatchdog: v.bufferWatchdog });
      })
      .catch(() => { /* unreachable server → benign defaults */ });
    return () => { dead = true; };
  }, []);
  return s;
}

/**
 * v2 M4: mid-play stall monitor for direct-file playback. Tolerates the
 * (hoster-real) slow rebuffering; armed only after playback actually started.
 * Stall with ZERO buffered growth for ~10s → one cache-busted reload of the
 * same source; still zero growth ~40s after that → self-heal/advance path.
 */
function useBufferWatchdog(
  videoRef: React.RefObject<HTMLVideoElement | null>,
  enabled: boolean,
  srcKey: string,
  onReload: () => void,
  onGiveUp: () => void,
) {
  const reloadRef = useRef(onReload);
  const giveUpRef = useRef(onGiveUp);
  reloadRef.current = onReload;
  giveUpRef.current = onGiveUp;

  /** links surviving renewals; the [link] effect resets them */
  const stall = useRef({ since: 0, lastEnd: 0, reloaded: false });
  useEffect(() => {
    stall.current = { since: 0, lastEnd: 0, reloaded: false };
  }, [srcKey]);

  useEffect(() => {
    if (!enabled) return;
    const video = videoRef.current;
    if (!video) return;
    const id = window.setInterval(() => {
      if (!video || video.paused) {
        stall.current.since = 0;
        return;
      }
      const end = video.buffered.length
        ? video.buffered.end(video.buffered.length - 1)
        : 0;
      const grew = end > stall.current.lastEnd + 0.25;
      const started = video.currentTime > 0.5 || end > 5;
      stall.current.lastEnd = Math.max(stall.current.lastEnd, end);
      if (!started || grew || video.seeking) {
        stall.current.since = 0;
        if (grew) stall.current.reloaded = false; // recovered: rearms future reload
        return;
      }
      if (stall.current.since === 0) stall.current.since = Date.now();
      const stuck = Date.now() - stall.current.since;
      if (stuck < 10_000) return;
      if (!stall.current.reloaded) {
        stall.current.reloaded = true;
        stall.current.since = 0;
        console.info("[watchdog] stalled 10s — cache-busting reload of the same source");
        reloadRef.current();
      } else if (stuck > 40_000) {
        console.info("[watchdog] stalled 40s after reload — treating as dead link");
        giveUpRef.current();
      }
    }, 1000);
    return () => window.clearInterval(id);
  }, [enabled, videoRef, srcKey]);
}

function Controls({ link, candidates, index, onSourcePick, muted, toggleMute, onDownload, downloadState, videoRef, subtitles, audio, refreshing, onRefresh, badgeFor, watchInfo }: {
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
  /** v2 M4: badge text for a candidate option ("": off or no probe data) */
  badgeFor?: (c: StreamDto) => string;
  /** v2 M4: "What am I watching?" drawer node (orElse null → hidden) */
  watchInfo?: React.ReactNode;
}) {
  const total = candidates.length;
  return (
    <>
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
          {candidates.map((c, i) => {
            const badge = badgeFor?.(c) ?? "";
            return (
              <option key={`${i}-${c.url}`} value={String(i)}>
                {i + 1}/{total} · {c.source} · {c.quality > 0 ? `${c.quality}p` : c.linkType}
                {badge ? ` · ${badge}` : ""}
              </option>
            );
          })}
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
      {watchInfo != null && watchInfo}
    </>
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
  const { link, candidates, onSourcePick, livePosRef, muted, setMuted, reportProgress, resumePosition, subtitles, onFail, index, total, onDownload, downloadState, refreshing, onRefresh, badgeFor, watchdog, watchInfo } = props;
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
        badgeFor={badgeFor}
        watchInfo={watchInfo}
      />
    </div>
  );
}

function NativePlayer(props: SharedPlayerProps) {
  const { link, candidates, onSourcePick, livePosRef, muted, setMuted, reportProgress, resumePosition, subtitles, onFail, index, total, onDownload, downloadState, refreshing, onRefresh, audioPick, onAudioPick, badgeFor, watchdog, watchInfo } = props;
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
   * v2 M4: `cb` is the watchdog's cache-bust counter (0 = no param).
   */
  const [cb, setCb] = useState(0);
  useEffect(() => setCb(0), [link]); // watchdog state belongs to one source

  const streamSrc = useMemo(() => {
    let src = streamProxyUrl(link);
    if (audioPick) {
      src += `&audio=${audioPick.ord}`;
      if (audioPick.pos > 0) src += `&start=${audioPick.pos}`;
    }
    if (cb > 0) src += `&cb=${cb}`;
    return src;
  }, [link, audioPick, cb]);

  // v2 M4: stall → one busted reload, then the self-heal/advance path
  useBufferWatchdog(
    videoRef,
    watchdog === true,
    streamSrc,
    () => setCb((v) => v + 1),
    onFail,
  );

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
        badgeFor={badgeFor}
        watchInfo={watchInfo}
      />
    </div>
  );
}
