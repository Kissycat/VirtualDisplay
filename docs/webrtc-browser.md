# 浏览器 WebRTC 输出

## 端口

- `18080`: Android 已编码 H.264 的 VDH1 TCP 数据口；**不是浏览器 HTTP/WebRTC 口**。
- `18081`: Android WebRTC 控制 HTTP API。
- `19000`: 独立 WebRTC Gateway 默认端口，浏览器打开该端口即可观看。

## Android API

### 查询

`GET /api/webrtc/status`

### 启动

`POST /api/webrtc/start`

```json
{"displayId":3,"bindHost":"0.0.0.0","port":18080}
```

### 停止

`POST /api/webrtc/stop`

## 浏览器

Android 负责 MediaCodec/H.264 和控制；Gateway 负责 H.264 RTP/WebRTC、SDP 和 ICE。因此 Android 不需要再进行第二次 H.264 编码。

启动 Gateway 后打开：

`http://<gateway-host>:19000/`

也可以打开 Android 的 `/webrtc` 页面，再追加：

`?gateway=http://<gateway-host>:19000`

> 直接访问 Android `http://<android-ip>:18080` 不能播放，因为 18080 是 VDH1 原始 H.264 传输协议，不是 HTTP/WebRTC。
