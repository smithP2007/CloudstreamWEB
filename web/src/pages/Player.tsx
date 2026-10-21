import { useEffect, useMemo, useRef, useState } from "react";
import Hls from "hls.js";
import type { StreamDto } from "../lib/api";
import { playbackCandidates, streamProxyUrl } from "../lib/api";

/**
 * Tries playback candidates in order: M3U8 links go through hls.js with the
 * proxy (every nested segment keeps its headers), direct VIDEO/FILE links are
 * played natively. A hard media error advances to the next candidate.
 */
export function Player({ links }: { links: StreamDto[] }) {
  const candidates = useMemo(() => playbackCandidates(links), [links]);
  const [index, setIndex] = useState(0);
  const [muted, setMuted] = useState(false);

  useEffect(() => setIndex(0), [links]);

  const link = candidates[index];

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
    <HlsPlayer link={link} muted={muted} onFail={() => setIndex(index + 1)} />
  ) : (
    <NativePlayer link={link} index={index} total={candidates.length} muted={muted} setMuted={setMuted} onFail={() => setIndex(index + 1)} />
  );
}

function Controls({ link, index, total, muted, toggleMute }: {
  link: StreamDto;
  index: number;
  total: number;
  muted: boolean;
  toggleMute: () => void;
}) {
  return (
    <div className="player-controls small mono muted">
      <span>
        {index + 1}/{total} · {link.source} · {link.quality > 0 ? `${link.quality}p` : link.linkType}
      </span>
      <button onClick={toggleMute}>{muted ? "unmute" : "mute"}</button>
    </div>
  );
}

function HlsPlayer({ link, muted: mutedProp, onFail }: { link: StreamDto; muted: boolean; onFail: () => void }) {
  const ref = useRef<HTMLVideoElement>(null);
  const [muted, setMuted] = useState(mutedProp);

  useEffect(() => {
    const video = ref.current;
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
  }, [link, onFail]);

  return (
    <div className="player">
      <video ref={ref} controls autoPlay muted={muted} playsInline />
      <Controls
        link={link}
        index={0}
        total={1}
        muted={muted}
        toggleMute={() => setMuted(!muted)}
      />
    </div>
  );
}

function NativePlayer({ link, index, total, muted, setMuted, onFail }: {
  link: StreamDto;
  index: number;
  total: number;
  muted: boolean;
  setMuted: (m: boolean) => void;
  onFail: () => void;
}) {
  return (
    <div className="player">
      <video
        key={streamProxyUrl(link)}
        controls
        autoPlay
        muted={muted}
        playsInline
        onError={onFail}
        src={streamProxyUrl(link)}
      />
      <Controls link={link} index={index} total={total} muted={muted} toggleMute={() => setMuted(!muted)} />
    </div>
  );
}
