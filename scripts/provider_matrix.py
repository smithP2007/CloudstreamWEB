#!/usr/bin/env python3
"""Provider connectivity matrix for cloud-stream-web.

For EVERY loaded provider: home -> search -> detail -> streams -> proxy
playability check (HTTP status + content type of the best link replayed
through the /api/stream bridge). Stdlib only; prints a table.

usage: scripts/provider_matrix.py [query]        (default query: news)
"""
import json
import sys
import urllib.error
import urllib.parse
import urllib.request

BASE = "http://localhost:8080"


def api(path, timeout=180, **params):
    q = urllib.parse.urlencode({k: v for k, v in params.items() if v is not None})
    url = BASE + path + (("?" + q) if q else "")
    req = urllib.request.Request(url, headers={"Accept": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, json.loads(r.read().decode() or "null")
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.loads(e.read().decode() or "null")
        except Exception:
            return e.code, {}
    except Exception as e:  # connection refused etc
        return 0, {"error": "NET", "detail": f"{type(e).__name__}: {e}"}


def playcheck(url):
    """Fetch a small range through the proxy; returns (status, content-type, range)."""
    if not isinstance(url, str) or not url.startswith("http"):
        return ("-"), "-", "-"
    proxy_url = BASE + "/api/stream?url=" + urllib.parse.quote(url, safe="")
    req = urllib.request.Request(
        proxy_url, headers={"Range": "bytes=0-1023", "User-Agent": "Mozilla/5.0 (playability check)"}
    )
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            return (r.status, r.headers.get("Content-Type", "-"),
                    r.headers.get("Content-Range") or r.headers.get("Content-Length") or "-")
    except urllib.error.HTTPError as e:
        return (e.code, e.headers.get("Content-Type", "-"), "-")
    except Exception as e:
        return (0, f"ERR {type(e).__name__}", str(e)[:60])


def pick(rows, extra):
    for r in rows or []:
        for it in r.get("items", []):
            if it.get("url"):
                return it
    for it in extra or []:
        if it.get("url"):
            return it
    return None


def cols(r):
    return [] if r is None else r.get("rows", [])


def main():
    query = sys.argv[1] if len(sys.argv) > 1 else "news"

    st, providers = api("/api/providers")
    if providers is None:
        print("FAILED to talk to", BASE)
        return 1

    results = []
    for p in providers:
        name = p["name"]
        row = {"provider": name, "plugin": p.get("sourcePlugin") or "-"}

        # ---- home ----------------------------------------------------------
        st, home = api("/api/home", provider=name)
        rows = cols(home) if isinstance(home, dict) else []
        nitems = sum(len(r.get("items", [])) for r in rows)
        row["home"] = f"rows={len(rows)} items={nitems}" if st == 200 else f"{st}: {home.get('error','?') if isinstance(home,dict) else '?'}"

        # ---- search ----------------------------------------------------------
        st, sr = api("/api/search", provider=name, q=query)
        items = sr.get("items", []) if isinstance(sr, dict) else []
        row["search"] = f"items={len(items)}" if st == 200 else f"{st}: {sr.get('error','?')[:28] if isinstance(sr,dict) and 'error' in sr else 'no search'}"

        # ---- detail ----------------------------------------------------------
        first = pick(rows, items)
        if first is None:
            row["detail"] = row["streams"] = row["play"] = "-"
            results.append(row)
            continue

        st, det = api("/api/detail", provider=name, url=first["url"])
        if st != 200:
            row["detail"] = f"{st}: {det.get('error','?')[:24]}" if isinstance(det, dict) else f"{st}"
            row["streams"] = row["play"] = "-"
            results.append(row)
            continue

        eps = det.get("episodes", []) or []
        row["detail"] = f"{str(det.get('name','?'))[:36]} ep={len(eps)}"

        # ---- streams -----------------------------------------------------------
        if not eps:
            row["streams"] = "no episodes"
            row["play"] = "-"
            results.append(row)
            continue

        data0 = eps[0].get("data")
        st, streams = api("/api/streams", provider=name, data=data0)
        if not (isinstance(streams, dict) and "links" in streams):
            row["streams"] = f"{st}: {streams.get('error','?')[:24] if isinstance(streams,dict) else '?'}"
            row["play"] = "-"
            results.append(row)
            continue

        links = streams.get("links", []) or []
        row["streams"] = f"links={len(links)}" + (
            "[" + ",".join(str(l.get("linkType", "?")) for l in links[:3]) + "]" if links else ""
        )
        if not links:
            row["play"] = "-"
            results.append(row)
            continue

        # ---- playability through the proxy ------------------------------------
        # try top-quality links first; a content-type that looks like media wins
        candidates = [
            l for l in links
            if l.get("url", "").startswith("http")
        ]
        candidates.sort(key=lambda l: (l.get("quality", 0),), reverse=True)
        best_line, best_status = "no http link", None
        for cand in candidates[:4]:
            s, ct, rng = playcheck(cand["url"])
            line = f"{s} {ct} {rng} [{str(cand['url'])[:36]}]"
            if s == 200 and ct.startswith(("video/", "audio/", "application/vnd.apple.", "application/x-mpeg")):
                best_line, best_status = line, True
                break
            if best_status is None:
                best_line, best_status = line, False
        row["play"] = best_line
        results.append(row)

    # ---- report ---------------------------------------------------------------
    headers = ["provider", "plugin", "home", "search", "detail", "streams", "play"]
    widths = {c: max(len(c), *(len(str(r.get(c, "-"))) for r in results)) for c in headers}
    print(" | ".join(c.ljust(widths[c]) for c in headers))
    for r in results:
        print(" | ".join(str(r.get(c, "-")).ljust(widths[c]) for c in headers))

    streamable = [r["provider"] for r in results if str(r.get("play", "")).startswith(("200", "206"))]
    print()
    print("playable-through-proxy:", streamable if streamable else "NONE")
    return 0 if streamable else 2


if __name__ == "__main__":
    sys.exit(main())
