The Gradle prepareWebRtcGateway task generates the ABI-specific executable here.
The APK never executes this asset directly. GatewayProcessController copies it to
/data/local/tmp/virtualdisplay-webrtc-gateway using Shizuku/root and runs it there.
