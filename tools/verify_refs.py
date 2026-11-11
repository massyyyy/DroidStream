#!/usr/bin/env python3
"""Scarica i sorgenti di riferimento e VERIFICA le ipotesi di protocollo.
Non modifica nulla, non fa fallire la build: produce un report onesto.
Ogni riga dice FOUND (con file:riga) oppure NOT FOUND. Cio' che e' NOT FOUND
NON va usato come base per implementare."""
import argparse, io, json, os, re, sys, tarfile, urllib.request

REPOS = {
    "moonlight-common-c": ("moonlight-stream/moonlight-common-c", "master"),
    "sunshine": ("LizardByte/Sunshine", "master"),
    "moonlight-android": ("moonlight-stream/moonlight-android", "master"),
}
WANTED = ("Limelight.h", "Limelight-internal.h", "ControlStream.c", "RtspParser.c",
          "RtpFecQueue.c", "VideoDepacketizer.c", "Misc.c", "nvhttp.cpp", "rtsp.cpp",
          "stream.cpp", "input.cpp", "config.cpp", "NsdServiceDiscoverer.java",
          "ComputerFinder.java", "ServerHelper.java", ".gitmodules")

CHECKS = [
    ("tcp_https_47984", r"47984"), ("tcp_http_47989", r"47989"), ("tcp_rtsp_48010", r"48010"),
    ("udp_video_47998", r"47998"), ("udp_control_47999", r"47999"), ("udp_audio_48000", r"48000"),
    ("udp_48002", r"48002"),
    ("pair_getservercert", r"getservercert"), ("pair_clientchallenge", r"clientchallenge"),
    ("pair_serverchallengeresp", r"serverchallengeresp"), ("pair_clientpairingsecret", r"clientpairingsecret"),
    ("pair_pairchallenge", r"pairchallenge"), ("pair_serverpairingresp", r"serverpairingresp"),
    ("pair_pairstate", r"pairstate"),
    ("rtsp_rikey", r"rikey"), ("rtsp_announce", r"ANNOUNCE"), ("rtsp_describe", r"DESCRIBE"),
    ("serverinfo", r"serverinfo"), ("applist", r"applist"),
    ("fec_nanors", r"nanors"), ("fec_percentage", r"[Ff]ec[Pp]ercentage|fec_percentage"),
    ("enet", r"enet_host_create|enet_host_service"),
    ("mdns_nvstream", r"_nvstream"),
    ("aes_crypto", r"AES_set_encrypt_key|EVP_EncryptInit|rikeyid"),
]

def fetch(name, repo, branch):
    url = "https://codeload.github.com/%s/tar.gz/refs/heads/%s" % (repo, branch)
    print("download:", url, flush=True)
    raw = urllib.request.urlopen(url, timeout=180).read()
    print("  %.1f MB" % (len(raw) / 1e6), flush=True)
    out = {}
    with tarfile.open(fileobj=io.BytesIO(raw), mode="r:gz") as tf:
        for m in tf:
            if not m.isfile():
                continue
            base = os.path.basename(m.name)
            if base in WANTED and m.size < 4_000_000:
                try:
                    out[m.name] = tf.extractfile(m).read().decode("utf-8", "ignore")
                except Exception:
                    pass
    return out

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="docs")
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    files, errors = {}, {}
    for name, (repo, branch) in REPOS.items():
        try:
            got = fetch(name, repo, branch)
            files.update(got)
            print("  file utili:", len(got), flush=True)
        except Exception as e:
            errors[name] = "%s: %s" % (type(e).__name__, e)
            print("  ERRORE:", errors[name], flush=True)

    results = []
    for cid, pattern in CHECKS:
        rx = re.compile(pattern)
        hits = []
        for path, text in files.items():
            for i, line in enumerate(text.splitlines(), 1):
                if rx.search(line):
                    hits.append({"file": path, "line": i, "text": line.strip()[:200]})
                    if len(hits) >= 3:
                        break
            if len(hits) >= 3:
                break
        results.append({"id": cid, "pattern": pattern,
                        "status": "FOUND" if hits else "NOT_FOUND", "hits": hits})

    md = ["# refs_report", "",
          "Generato automaticamente dalla CI. FOUND = confermato sul sorgente reale.",
          "NOT_FOUND = NON verificato: non implementare su questa ipotesi.", ""]
    if errors:
        md += ["## Download falliti", ""] + ["- %s: %s" % (k, v) for k, v in errors.items()] + [""]
    md += ["| check | stato | evidenza |", "|---|---|---|"]
    for r in results:
        ev = ""
        if r["hits"]:
            h = r["hits"][0]
            ev = "%s:%d `%s`" % (os.path.basename(h["file"]), h["line"], h["text"].replace("|", "/")[:90])
        md.append("| %s | %s | %s |" % (r["id"], r["status"], ev))
    md += ["", "## File analizzati", ""] + ["- " + p for p in sorted(files)]
    open(os.path.join(a.out, "refs_report.md"), "w", encoding="utf-8").write("\n".join(md))
    json.dump({"errors": errors, "results": results, "files": sorted(files)},
              open(os.path.join(a.out, "refs_report.json"), "w", encoding="utf-8"), indent=1)
    ok = sum(1 for r in results if r["status"] == "FOUND")
    print("\n==== RIEPILOGO: %d/%d confermati ====" % (ok, len(results)), flush=True)
    for r in results:
        print("%-26s %s" % (r["id"], r["status"]), flush=True)
    return 0

if __name__ == "__main__":
    sys.exit(main())