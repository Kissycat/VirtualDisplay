#!/usr/bin/env bash
set -euo pipefail
mkdir -p dist
GOOS=linux GOARCH=arm64 go build -trimpath -ldflags='-s -w' -o dist/virtualdisplay-webrtc-gateway-linux-arm64 .
GOOS=linux GOARCH=amd64 go build -trimpath -ldflags='-s -w' -o dist/virtualdisplay-webrtc-gateway-linux-amd64 .
GOOS=windows GOARCH=amd64 go build -trimpath -ldflags='-s -w' -o dist/virtualdisplay-webrtc-gateway-windows-amd64.exe .
