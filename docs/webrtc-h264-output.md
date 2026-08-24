# WebRTC H.264 输出接口

## 设计目标

视频输出不再重新创建 MediaCodec，也不从 Surface 做二次采集。
`ROLE_VIDEO -> ScrcpyFrameReader -> MediaCodec` 仍然是唯一主链路；WebRTC 输出只是挂在编码包读取点的可选旁路。

因此：

- 默认不开启 WebRTC 输出时，额外开销为一个空订阅集合判断，不会复制 H.264 数据。
- 开启后，每个编码包只复制一次，所有外部客户端共享这一份数据。
- 单个客户端网络慢时只丢它自己的旧帧，不会阻塞 MediaCodec 输入线程。
- WebRTC 网关收到的仍然是 H.264 编码数据，无需 Android 侧再次编码。

> 注意：这个接口本身不是浏览器 `RTCPeerConnection`/WHIP/WHEP 信令服务。
> 它是专门给 WebRTC Gateway（例如 Pion、mediasoup、GStreamer 等）提供已编码 H.264 输入的接口。
> Gateway 负责 H.264 RTP packetization、ICE、DTLS、SRTP 和 WebRTC signaling。

## 启动方式

通过 `IDisplayRepository`：

```kotlin
// 先让指定 display 建立视频流；可使用现有 setDisplaySurface()，也可以保持 Surface=null 运行纯后台视频。
repository.startWebRtcH264Output(
    displayId = 3,
    bindHost = "0.0.0.0",
    port = 18080
)

// 停止输出
repository.stopWebRtcH264Output()
```

当前实现要求 `displayId` 已经是 `currentStreamingDisplayId`。
这样可以避免 WebRTC 输出接口偷偷创建或切换视频流，从而影响现有预览性能。

## TCP 协议

连接成功后，服务端发送：

```text
magic       4B   ASCII "VDH1"
version     4B   int32 BE，当前 1
displayId   4B   int32 BE
width       4B   int32 BE
height      4B   int32 BE
```

随后发送两类消息：

### 分辨率更新

```text
type        1B   = 1
width       4B   int32 BE
height      4B   int32 BE
```

### H.264 编码帧

```text
type        1B   = 2
ptsUs       8B   int64 BE
flags       4B   int32 BE
size        4B   int32 BE
payload     size B
```

`flags`：

```text
bit 0 = codec-config（SPS/PPS 等配置帧）
bit 1 = key-frame（关键帧）
```

payload 直接来自 scrcpy H.264 编码包，保持原编码数据，不增加 Annex-B 转换层。

## 推荐的 WebRTC 架构

```text
Android VirtualDisplay
        |
        | ROLE_VIDEO
        v
Scrcpy H.264 encoder
        |
        +------> MediaCodec decoder ------> 本地 Surface 预览
        |
        +------> WebRtcH264TcpEndpoint ----> Pion / Gateway ----> RTP/H264
                                                           |
                                                           +--> WebRTC Browser
```

这样 WebRTC 输出不会再增加第二个编码器，也不会和本地预览竞争编码资源。

## 客户端验证

仓库提供 `scripts/client/webrtc_h264_client.py`，可直接检查握手、分辨率和帧速率，并把 payload 导出为裸 H.264。

```bash
python3 scripts/client/webrtc_h264_client.py 192.168.1.10 18080 --output out.h264 --seconds 10
```

然后可用 FFmpeg 验证：

```bash
ffplay -f h264 -framerate 60 out.h264
```

## 性能注意事项

WebRTC 输出默认是关闭的。打开后，额外成本主要是一次编码帧内存复制以及 TCP 写出；客户端队列固定为 4 帧，并采用丢旧帧策略，避免网络抖动将压力传回视频采集线程。

如果后续需要真正的公网 WebRTC，可以在 Gateway 上实现：

`VDH1 -> H264 NAL -> RTP H264 payloader -> RTCP/ICE/DTLS/SRTP -> RTCPeerConnection`

而不需要修改 Android 侧的 MediaCodec 采集链。
