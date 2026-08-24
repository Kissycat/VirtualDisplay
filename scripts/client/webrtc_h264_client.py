#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Minimal client for VirtualDisplay's optional WebRTC H.264 bridge.

It validates the VDH1 protocol and can dump H.264 payloads directly to a file.
The payload stays in the same encoded form received from the Android side.
"""

import argparse
import socket
import struct
import time


MAGIC = b"VDH1"
TYPE_FORMAT = 1
TYPE_FRAME = 2
FLAG_CONFIG = 1
FLAG_KEYFRAME = 2


def recv_exact(sock: socket.socket, size: int) -> bytes:
    data = bytearray()
    while len(data) < size:
        chunk = sock.recv(size - len(data))
        if not chunk:
            raise ConnectionError(f"connection closed while reading {size} bytes")
        data.extend(chunk)
    return bytes(data)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("host")
    parser.add_argument("port", type=int)
    parser.add_argument("--seconds", type=float, default=10.0)
    parser.add_argument("--output")
    args = parser.parse_args()

    with socket.create_connection((args.host, args.port), timeout=5.0) as sock:
        sock.settimeout(5.0)
        header = recv_exact(sock, 20)
        magic, version, display_id, width, height = struct.unpack(">4siiii", header)
        if magic != MAGIC:
            raise RuntimeError(f"invalid magic: {magic!r}")
        if version != 1:
            raise RuntimeError(f"unsupported protocol version: {version}")

        print(f"display={display_id} {width}x{height} protocol=v{version}")

        output = open(args.output, "wb") if args.output else None
        started = time.monotonic()
        frames = 0
        bytes_received = 0
        keyframes = 0

        try:
            while time.monotonic() - started < args.seconds:
                msg_type = recv_exact(sock, 1)[0]
                if msg_type == TYPE_FORMAT:
                    width, height = struct.unpack(">ii", recv_exact(sock, 8))
                    print(f"format={width}x{height}")
                    continue

                if msg_type != TYPE_FRAME:
                    raise RuntimeError(f"unknown message type: {msg_type}")

                pts_us, flags, size = struct.unpack(">qii", recv_exact(sock, 16))
                if size < 0 or size > 8 * 1024 * 1024:
                    raise RuntimeError(f"invalid frame size: {size}")
                payload = recv_exact(sock, size)
                frames += 1
                bytes_received += size
                if flags & FLAG_KEYFRAME:
                    keyframes += 1
                if output:
                    output.write(payload)

                if frames % 60 == 0:
                    elapsed = max(time.monotonic() - started, 1e-6)
                    print(f"frames={frames} fps={frames/elapsed:.1f} bitrate={(bytes_received*8/elapsed)/1e6:.2f}Mbps pts={pts_us}")
        finally:
            if output:
                output.close()

    elapsed = max(time.monotonic() - started, 1e-6)
    print(f"done: frames={frames}, keyframes={keyframes}, fps={frames/elapsed:.1f}, bitrate={(bytes_received*8/elapsed)/1e6:.2f}Mbps")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
