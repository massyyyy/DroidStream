# STATE.md

AGGIORNAMENTO:
- Risoluzione dinamica IP client in LaunchHandler, GameStreamHttp(s)Server, RtspServer e ScreenCapture (rimosso IP cablato fisso).
- Allineamento RTP payload type = 97 sia in RTSP SDP sia nel pacchettizzatore nativo C++.
- Header pacchetti video conforme a Moonlight (`NV_VIDEO_PACKET` 16 byte + `VIDEO_SHORT_FRAME_HEADER` 8 byte su SOF).
- Canale di controllo e input ENet attivo con dispatching degli eventi Moonlight (mouse, tocchi, tasti) tramite `InputManager` compatibile Android 6.0+ (minSdk 23).
- Gestione rilascio risorse e stop sessione ripuliti.

COMPATIBILITÀ:
- minSdk 23 (Android 6.0 Marshmallow e superiori).
- Nessuna dipendenza da AndroidX.
- Root shell persistente per dispatch input a bassa latenza su Android 6+.
