# DroidStream (M0)

Host GameStream per Android: rende lo schermo del telefono controllabile da
Moonlight ufficiale. minSdk 23 (Android 6.0). Kotlin + C++ (NDK). GPL-3.0.

M0 = scheletro + diagnostica + build CI. Nessuno streaming ancora.

Build: GitHub Actions produce l'APK (artifact droidstream-m0).
Verifica protocollo: tools/verify_refs.py -> docs/refs_report.md.
Documenti: docs/PROTOCOL.md, docs/LATENCY.md, docs/STATE.md, docs/GITHUB_GUIDE_IT.md.