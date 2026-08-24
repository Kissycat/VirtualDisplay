# VirtualDisplay WebRTC Gateway

This companion gateway keeps the Android app on the zero-reencode path:

Browser WebRTC <-> Pion gateway <-> TCP VDH1 H.264 <-> Android MediaCodec encoder

## Build

```bash
go mod tidy
go build -o virtualdisplay-webrtc-gateway .
```

For Android as the H.264 source:

```bash
ANDROID_H264=192.168.1.50:18080 LISTEN=:19000 ./virtualdisplay-webrtc-gateway
```

Open:

```text
http://<gateway-host>:19000/
```

The Android control API is on port 18081:

```text
GET  http://<android-ip>:18081/api/webrtc/status
POST http://<android-ip>:18081/api/webrtc/start
POST http://<android-ip>:18081/api/webrtc/stop
GET  http://<android-ip>:18081/webrtc
```

Start request example:

```json
{"displayId":3,"bindHost":"0.0.0.0","port":18080}
```

The gateway currently uses non-trickle ICE and an H.264 track with packetization-mode=1. It is intended for LAN/private-network use; add HTTPS/DTLS/ICE-TCP/TURN and authentication before exposing it to the public internet.
