#!/usr/bin/env python3
"""verify_refs v2 - scarica i sorgenti di riferimento e produce:
   refs_report.md          tabella dei check (regressione)
   refs_report.dump.md     ESTRATTI VERBATIM per implementare M1
   refs_report.pairing.md  estratti del pairing per M2
"""
import argparse, io, json, os, re, sys, tarfile, urllib.request

REPOS = {
    "moonlight-common-c": ("moonlight-stream/moonlight-common-c", "master"),
    "sunshine": ("LizardByte/Sunshine", "master"),
    "moonlight-android": ("moonlight-stream/moonlight-android", "master"),
}

CHECKS = [
    ("tcp_https_47984", r"47984"), ("tcp_http_47989", r"47989"), ("tcp_rtsp_48010", r"48010"),
    ("udp_video_47998", r"47998"), ("udp_control_47999", r"47999"), ("udp_audio_48000", r"48000"),
    ("pair_getservercert", r"getservercert"), ("pair_clientchallenge", r"clientchallenge"),
    ("pair_serverchallengeresp", r"serverchallengeresp"),
    ("pair_clientpairingsecret", r"clientpairingsecret"), ("pair_pairchallenge", r"pairchallenge"),
    ("rtsp_rikey", r"rikey"), ("serverinfo", r"serverinfo"), ("applist", r"applist"),
    ("fec_nanors", r"nanors"), ("fec_percentage", r"[Ff]ec[Pp]ercentage"),
    ("enet", r"enet_host_create|enet_host_service"), ("mdns_nvstream", r"nvstream"),
]


def short(path):
    parts = path.split("/")
    return "/".join(parts[-2:]) if len(parts) > 1 else path


def keep(path):
    p = path.replace("\\", "/")
    if p.endswith(".gitmodules") or p.endswith(".java"):
        return True
    if "/src/" in p and p.endswith((".c", ".h", ".cpp", ".hpp")):
        return True
    return False


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


def body(files, filename, signature, count=150):
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
            print("  ERRORE:", errors[name], flush=True)

    results = []
    for cid, pattern in CHECKS:
        hits = grep(files, pattern, cap=1)
        ok = not hits[0].startswith("(nessuna")
        results.append({"id": cid, "status": "FOUND" if ok else "NOT_FOUND",
                        "evidence": hits[0] if ok else ""})

    md = ["# refs_report", ""]
    if errors:
        md += ["## Download falliti", ""] + ["- %s: %s" % (k, v) for k, v in errors.items()] + [""]
    md += ["| check | stato | evidenza |", "|---|---|---|"]
    for r in results:
        md.append("| %s | %s | %s |" % (r["id"], r["status"], r["evidence"].replace("|", "/")[:120]))
    open(os.path.join(a.out, "refs_report.md"), "w", encoding="utf-8").write("\n".join(md))
    json.dump({"errors": errors, "results": results},
              open(os.path.join(a.out, "refs_report.json"), "w", encoding="utf-8"), indent=1)

    dump = ["# refs_report.dump - estratti per M1", ""]
    dump.append(block("PORT INDEX (moonlight-common-c)", grep(files, r"ML_PORT_INDEX", cap=40)))
    dump.append(block("PORT NUMERICHE in Sunshine",
                      grep(files, r"\b(4798[0-9]|4799[0-9]|4800[0-9]|4801[0-9])\b", cap=40, only="Sunshine")))
    dump.append(block("MDNS / DISCOVERY",
                      grep(files, r"nvstream|SERVICE_TYPE|NsdManager|registerService|resolveService|_tcp", cap=60)))
    dump.append(block("SERVERINFO (Sunshine)", body(files, "nvhttp.cpp", "void serverinfo(", 150)))
    dump.append(block("APPLIST (Sunshine)", body(files, "nvhttp.cpp", "void applist(", 55)))
    dump.append(block("ROUTE HTTP (Sunshine)",
                      grep(files, r'resource\[|server\.resource|"/[a-z]', cap=35, only="nvhttp.cpp")))
    inv = sorted(short(p) for p in files if "moonlight-common-c" in p and "/src/" in p)
    dump.append(block("INVENTARIO moonlight-common-c/src", inv))
    open(os.path.join(a.out, "refs_report.dump.md"), "w", encoding="utf-8").write("\n".join(dump))

    pair = ["# refs_report.pairing - estratti per M2", ""]
    pair.append(block("DISPATCHER PHRASE", grep(files, r"phrase", cap=60, only="nvhttp.cpp")))
    pair.append(block("HANDLER getservercert", body(files, "nvhttp.cpp", "void getservercert(", 45)))
    pair.append(block("HANDLER clientchallenge", body(files, "nvhttp.cpp", "void clientchallenge(", 45)))
    open(os.path.join(a.out, "refs_report.pairing.md"), "w", encoding="utf-8").write("\n".join(pair))

    ok = sum(1 for r in results if r["status"] == "FOUND")
    print("\n==== check: %d/%d FOUND ====" % (ok, len(results)), flush=True)
    print("file analizzati:", len(files), flush=True)
    print("scritti: refs_report.md, refs_report.dump.md, refs_report.pairing.md", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
