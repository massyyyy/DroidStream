# PROTOCOL.md (M0)

STATO: tutte le voci sono IPOTESI finche' docs/refs_report.md (prodotto dalla CI)
non le marca FOUND. Regola: niente implementazione su ipotesi non confermate.

## Architettura del trasporto (da confermare)
- discovery mDNS: _nvstream._tcp
- HTTP  TCP 47989: /serverinfo, /applist
- HTTPS TCP 47984: pairing con client certificate
- RTSP  TCP 48010: OPTIONS/DESCRIBE/SETUP/ANNOUNCE/PLAY, SDP, rikey/rikeyid
- UDP 47998 video (RTP + FEC), 47999 controllo (ENet), 48000 audio (Opus)

## Riferimenti normativi
- moonlight-common-c: src/Limelight.h, src/Limelight-internal.h (struct pacchetti),
  ControlStream.c, RtspParser.c, RtpFecQueue.c, VideoDepacketizer.c
- Sunshine: src/nvhttp.cpp (pairing/serverinfo), src/rtsp.cpp, src/stream.cpp, src/input.cpp
- FEC: nanors. ENet: fork bundlato in moonlight-common-c (ABI incompatibile con upstream).

## Come si verifica
tools/verify_refs.py scarica i sorgenti e cerca costanti/phrase, producendo
docs/refs_report.md con file:riga. Si esegue ad ogni build CI.

## Vietato
Inventare campi. Se un check e' NOT_FOUND, si allarga la ricerca o si cattura traffico
Wireshark tra Sunshine reale e Moonlight, e si aggiorna questo file.