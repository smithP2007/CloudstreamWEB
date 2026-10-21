import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import Hls from "hls.js";
import type { StreamDto, SubtitleDto } from "../lib/api";
import { createDownload, playbackCandidates, saveHistory, streamProxyUrl, subtitleProxyUrl } from "../lib/api";

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
  const candidates = useMemo(() => playbackCandidates(links), [links]);
  const [index, setIndex] = useState(0);
  const [muted, setMuted] = useState(false);
  const [downloadState, setDownloadState] = useState<string | null>(null);

  useEffect(() => setIndex(0), [links]);

  const indexRef = useRef(0);
  useEffect(() => { indexRef.current = index; }, [index]);
  /** stable advance: components keep it in their load effect deps */
  const advance = useCallback(() => setIndex(indexRef.current + 1), []);

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
      muted={muted}
      setMuted={setMuted}
      reportProgress={reportProgress}
      resumePosition={resumePosition}
      subtitles={subtitles}
      onFail={advance}
      index={0}
      total={1}
      onDownload={downloadCurrentLink}
      downloadState={downloadState}
    />
  ) : (
    <NativePlayer
      link={link}
      muted={muted}
      setMuted={setMuted}
      reportProgress={reportProgress}
      resumePosition={resumePosition}
      subtitles={subtitles}
      onFail={advance}
      index={index}
      total={candidates.length}
      onDownload={downloadCurrentLink}
      downloadState={downloadState}
    />
  );
}

type SharedPlayerProps = {
  link: StreamDto;
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

function Controls({ link, index, total, muted, toggleMute, onDownload, downloadState }: {
  link: StreamDto;
  index: number;
  total: number;
  muted: boolean;
  toggleMute: () => void;
  onDownload: () => void;
  downloadState: string | null;
}) {
  return (
    <div className="player-controls small mono muted">
      <span>
        {index + 1}/{total} · {link.source} · {link.quality > 0 ? `${link.quality}p` : link.linkType}
      </span>
      <span>
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

  return { videoRef, progressRef };
}

function HlsPlayer(props: SharedPlayerProps) {
  const { link, muted, setMuted, reportProgress, resumePosition, subtitles, onFail, index, total, onDownload, downloadState } = props;
  const { videoRef, progressRef } = useProgressBinding(link, reportProgress);
  const [mutedLocal, setMutedLocal] = useState(muted);

  useEffect(() => {
    const video = videoRef.current;
    if (!video) return;

    const hls = new Hls({
      // the proxy rewrites every segment URL already; keep in-buffer behavior default
      enableWorker: true,
    });
    hls.loadSource(streamProxyUrl(link));
    hls.attachMedia(video);
    hls.on(Hls.Events.ERROR, (_e, data) => {
      if (data.fatal) {
        hls.destroy();
        onFail();
      }
    });

    return () => hls.destroy();
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
        index={index}
        total={total}
        muted={mutedLocal}
        toggleMute={() => {
          setMutedLocal(!mutedLocal);
          setMuted(!mutedLocal);
        }}
        onDownload={onDownload}
        downloadState={downloadState}
      />
    </div>
  );
}

function NativePlayer(props: SharedPlayerProps) {
  const { link, muted, setMuted, reportProgress, resumePosition, subtitles, onFail, index, total, onDownload, downloadState } = props;
  const { videoRef, progressRef } = useProgressBinding(link, reportProgress);
  const [mutedLocal, setMutedLocal] = useState(muted);

  // resume position may arrive before or after bind — feed it to the controller
  useEffect(() => {
    progressRef.current?.setResume(resumePosition);
  }, [progressRef, resumePosition, link]);

  return (
    <div className="player">
      <video
        key={streamProxyUrl(link)}
        ref={videoRef}
        controls
        autoPlay
        muted={mutedLocal}
        playsInline
        onError={onFail}
        src={streamProxyUrl(link)}
      >
        <SubtitleTracks subtitles={subtitles} />
      </video>
      <Controls
        link={link}
        index={index}
        total={total}
        muted={mutedLocal}
        toggleMute={() => {
          setMutedLocal(!mutedLocal);
          setMuted(!mutedLocal);
        }}
        onDownload={onDownload}
        downloadState={downloadState}
      />
    </div>
  );
}
