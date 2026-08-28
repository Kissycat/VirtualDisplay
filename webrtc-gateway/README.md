# VirtualDisplay WebRTC Gateway

This companion gateway keeps the Android app on the zero-reencode path:

Browser WebRTC <-> Pion gateway <-> scrcpy daemon ROLE_VIDEO <-> Android MediaCodec encoder

## Build

```bash
go mod tidy
go build -o virtualdisplay-webrtc-gateway .
```

The gateway is a scrcpy daemon client. It requires an explicit virtual display id:

```bash
DAEMON_ADDR=127.0.0.1:27183 \
DAEMON_TOKEN=your-token \
DISPLAY_ID=3 \
LISTEN=:19000 \
./virtualdisplay-webrtc-gateway
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
