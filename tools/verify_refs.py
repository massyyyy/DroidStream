#!/usr/bin/env python3
"""verify_refs v5 - scelta trasporto RTSP (ENet vs TCP)."""
import argparse, io, os, re, sys, tarfile, urllib.request

REPOS = {"moonlight-common-c": ("moonlight-stream/moonlight-common-c", "master")}

def short(p):
    x = p.split("/")
    return "/".join(x[-2:]) if len(x) > 1 else p

def keep(p):
    p = p.replace("\\", "/")
    return "/src/" in p and p.endswith((".c", ".h"))

def fetch(repo, branch):
    url = "https://codeload.github.com/%s/tar.gz/refs/heads/%s" % (repo, branch)
    print("download:", url, flush=True)
    raw = urllib.request.urlopen(url, timeout=240).read()
    print("  %.1f MB" % (len(raw) / 1e6), flush=True)
    out = {}
    with tarfile.open(fileobj=io.BytesIO(raw), mode="r:gz") as tf:
        for m in tf:
            if m.isfile() and keep(m.name) and m.size < 2000000:
                try:
                    out[m.name] = tf.extractfile(m).read().decode("utf-8", "ignore")
                except Exception:
                    pass
    return out

def grep(files, pattern, cap=120, only=None):
    rx = re.compile(pattern)
    hits = []
    for path in sorted(files):
        if only and only not in path:
            continue
        for i, l in enumerate(files[path].splitlines(), 1):
            if rx.search(l):
                hits.append("%s:%d: %s" % (short(path), i, l.rstrip()[:220]))
                if len(hits) >= cap:
                    return hits
    return hits or ["(nessuna corrispondenza)"]

def body(files, filename, sig, count=150):
    for path in sorted(files):
        if not path.endswith(filename):
            continue
        src = files[path].splitlines()
        for i, l in enumerate(src):
            if sig in l:
                return ["%s:%d: %s" % (short(path), j + 1, src[j].rstrip()[:220])
                        for j in range(i, min(i + count, len(src)))]
    return ["(non trovato: '%s' in %s)" % (sig, filename)]

def block(t, lines):
    return "## " + t + "\n\n```\n" + "\n".join(lines) + "\n```\n"

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="docs")
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    files = {}
    for name, (repo, branch) in REPOS.items():
        try:
            got = fetch(repo, branch)
            files.update(got)
            print("  file utili:", len(got), flush=True)
        except Exception as e:
            print("ERRORE", name, type(e).__name__, e, flush=True)

    out = ["# refs_report.enet - scelta trasporto RTSP (ENet vs TCP)", ""]
    out.append(block("Quali file usano ENet", grep(files, r"[Ee]net|ENET", cap=150)))
    out.append(block("Connection.c: performRtspHandshake", body(files, "Connection.c", "performRtspHandshake", 260)))
    out.append(block("Connection.c: LiStartConnection", body(files, "Connection.c", "int LiStartConnection", 200)))
    out.append(block("Limelight.h: SERVER_INFORMATION", body(files, "Limelight.h", "_SERVER_INFORMATION", 90)))
    out.append(block("rtspSessionUrl / porta RTSP", grep(files, r"rtspSessionUrl|RTSP_SETUP_PORT|rtspPort|48010", cap=60)))
    out.append(block("Confronti di versione", grep(files, r"AppVersionQuad\[", cap=80)))
    open(os.path.join(a.out, "refs_report.enet.md"), "w", encoding="utf-8").write("\n".join(out))
    print("scritto refs_report.enet.md", flush=True)
    return 0

if __name__ == "__main__":
    sys.exit(main())
