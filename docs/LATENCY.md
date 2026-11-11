# LATENCY.md (M0) - budget preliminare, NON misurato

Host: DIG-L01 (SD435, 720p, Wi-Fi 2.4 GHz only). Client: Realme GT7 (Android 16).
Preset target: 720p30 4-8 Mbps; fallback 540p30; 720p60 solo sperimentale.

| stadio | target | come si misura |
|---|---|---|
| cattura | 8-16 ms | timestamp VirtualDisplay vs encoder output |
| encode | 15-30 ms | MediaCodec input/output PTS delta |
| packetize+FEC | <2 ms | clock nativo (M0 misura gia' il costo del clock) |
| airtime 2.4 GHz | 5-30 ms + jitter | ping/statistiche client |
| jitter buffer | 5-15 ms | statistiche Moonlight |
| decode+render | 8-20 ms | statistiche Moonlight |
| input (uinput) | <5 ms | log timestamp iniezione |

Obiettivo complessivo: <=110 ms glass-to-glass, stretch <=80 ms.
Metodo di misura utente: fotocamera 240 fps che inquadra un timestamp in ms
mostrato su host e client; differenza dei due valori nel fotogramma.
Nessun numero qui sopra e' una promessa: sono target da confermare.