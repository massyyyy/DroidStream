#!/usr/bin/env python3
"""verify_refs v4 - estratti per M3 (RTSP, RTP, FEC)."""
import argparse, io, os, re, sys, tarfile, urllib.request

REPOS = {
    "moonlight-common-c": ("moonlight-stream/moonlight-common-c", "master"),
    "sunshine": ("LizardByte/Sunshine", "master"),
}

def short(path):
    parts = path.split("/")
    return "/".join(parts[-2:]) if len(parts) > 1 else path

def keep(path):
    p = path.replace("\\", "/")
    return "/src/" in p and p.endswith((".c", ".h", ".cpp", ".hpp"))

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

def grep(files, pattern, cap=60, only=None):
    rx = re.compile(pattern)
    hits = []
    for path in sorted(files):
        if only and only not in path:
            continue
        for i, line in enumerate(files[path].splitlines(), 1):
            if rx.search(line):
                hits.append("%s:%d: %s" % (short(path), i, line.rstrip()[:220]))
                if len(hits) >= cap:
                    return hits
    return hits or ["(nessuna corrispondenza)"]

def body(files, filename, signature, count=60):
    for path in sorted(files):
        if not path.endswith(filename):
            continue
        src = files[path].splitlines()
        for i, line in enumerate(src):
            if signature in line:
                return ["%s:%d: %s" % (short(path), j + 1, src[j].rstrip()[:220])
                        for j in range(i, min(i + count, len(src)))]
    return ["(non trovato: '%s' in %s)" % (signature, filename)]

def block(title, lines):
    return "## " + title + "\n\n```\n" + "\n".join(lines) + "\n```\n"

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="docs")
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    files, errors = {}, {}
    for name, (repo, branch) in REPOS.items():
        try:
            got = fetch(repo, branch)
            files.update(got)
            print("  file utili:", len(got), flush=True)
        except Exception as e:
            errors[name] = "%s: %s" % (type(e).__name__, e)

    rtsp = ["# refs_report.rtsp - estratti per M3 (RTSP)", ""]
    if errors:
        rtsp += ["## Download falliti", ""] + ["- %s: %s" % (k, v) for k, v in errors.items()] + [""]
    rtsp.append(block("RTSP inizio file", body(files, "rtsp.cpp", "#include", 560)))
    rtsp.append(block("RTSP zona SETUP", body(files, "rtsp.cpp", "Handle RTSP SETUP", 300)))
    rtsp.append(block("RTSP DESCRIBE", body(files, "rtsp.cpp", "Handle RTSP DESCRIBE", 300)))
    rtsp.append(block("RTSP ANNOUNCE", body(files, "rtsp.cpp", "Handle RTSP ANNOUNCE", 300)))
    rtsp.append(block("RTSP PLAY", body(files, "rtsp.cpp", "Handle RTSP PLAY", 200)))
    rtsp.append(block("RtspParser.c (client, completo)", body(files, "RtspParser.c", "#include", 750)))
    open(os.path.join(a.out, "refs_report.rtsp.md"), "w", encoding="utf-8").write("\n".join(rtsp))

    rtp = ["# refs_report.rtp - estratti per M3 (RTP/FEC)", ""]
    rtp.append(block("Struct RTP/payload", body(files, "Limelight-internal.h", "_RTP_HEADER", 130)))
    rtp.append(block("FEC in Limelight-internal.h", grep(files, r"FEC|fec", cap=50, only="Limelight-internal.h")))
    rtp.append(block("stream.cpp: video/fec/parity", grep(files, r"parity|shards|fecPercentage|sendVideo|broadcast|payloadSize|roundRobin", cap=70, only="stream.cpp")))
    rtp.append(block("RtpVideoQueue.c: FEC", grep(files, r"FEC|fec|parity|shards", cap=60, only="RtpVideoQueue.c")))
    rtp.append(block("VideoDepacketizer: header", grep(files, r"header|Header|payload|Payload", cap=60, only="VideoDepacketizer.c")))
    open(os.path.join(a.out, "refs_report.rtp.md"), "w", encoding="utf-8").write("\n".join(rtp))

    print("scritti refs_report.rtsp.md e refs_report.rtp.md", flush=True)
    return 0

if __name__ == "__main__":
    sys.exit(main())
