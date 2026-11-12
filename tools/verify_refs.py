#!/usr/bin/env python3
"""verify_refs v3 - estratti mirati per M2b (pairing completo)."""
import argparse, io, os, re, sys, tarfile, urllib.request

REPOS = {
    "moonlight-common-c": ("moonlight-stream/moonlight-common-c", "master"),
    "sunshine": ("LizardByte/Sunshine", "master"),
    "moonlight-android": ("moonlight-stream/moonlight-android", "master"),
}

def short(path):
    parts = path.split("/")
    return "/".join(parts[-2:]) if len(parts) > 1 else path

def keep(path):
    p = path.replace("\\", "/")
    if p.endswith("PairingManager.java"):
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
    out = ["# refs_report.pairing2 - estratti per M2b", ""]
    if errors:
        out += ["## Download falliti", ""] + ["- %s: %s" % (k, v) for k, v in errors.items()] + [""]
    out.append(block("DISPATCHER pair()", body(files, "nvhttp.cpp", "void pair(", 140)))
    out.append(block("serverchallengeresp", body(files, "nvhttp.cpp", "void serverchallengeresp(", 70)))
    out.append(block("clientpairingsecret", body(files, "nvhttp.cpp", "void clientpairingsecret(", 70)))
    out.append(block("PIN e fail_pair", grep(files, r"fail_pair|async_insert_pin|insert_pin|pin\b", cap=50, only="nvhttp.cpp")))
    out.append(block("crypto: chiavi e firme", grep(files, r"gen_aes_key|signature\(|::hash\(|sha256|SHA256", cap=50, only="crypto")))
    out.append(block("PairingManager (lato client Moonlight)", body(files, "PairingManager.java", "class PairingManager", 430)))
    open(os.path.join(a.out, "refs_report.pairing2.md"), "w", encoding="utf-8").write("\n".join(out))
    print("scritto refs_report.pairing2.md", flush=True)
    return 0

if __name__ == "__main__":
    sys.exit(main())
