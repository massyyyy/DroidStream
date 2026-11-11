# STATE.md

FATTO (M0):
- scheletro app Kotlin minSdk 23, senza AndroidX
- foreground service + wakeLock + wifiLock, log su file, diagnostica codec/wifi/display/root
- modulo C++ NDK con misura reale del costo di clock_gettime
- CI GitHub Actions: build APK + verify_refs.py + artifact
- bootstrap PowerShell

VERIFICATO: nulla sul dispositivo. Nessun sorgente di protocollo letto direttamente.
DA VERIFICARE: tutte le ipotesi in PROTOCOL.md -> refs_report.md della CI.
DA VERIFICARE: installazione e avvio su DIG-L01 (Android 6).
NON FATTO: ricerca prior-art (strumenti di rete non disponibili in questa sessione).

DECISIONI: solo host; client Moonlight ufficiale; root-first (uinput) per latenza;
720p30 su 2.4 GHz; niente AndroidX in M0; niente gradle wrapper (CI usa Gradle pinnato).

PROSSIMO PASSO (M1): discovery mDNS + /serverinfo + /applist, solo dopo refs_report.