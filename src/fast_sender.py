#!/usr/bin/env python3
import sys
import socket
import os
import fcntl

def main():
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 12345

    # Clamp stdin pipe buffer size to 4096 bytes (~23ms) to eliminate 64KB pipe bufferbloat
    try:
        F_SETPIPE_SZ = 1031
        fcntl.fcntl(0, F_SETPIPE_SZ, 4096)
    except Exception:
        pass

    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)

    try:
        s.setsockopt(socket.SOL_SOCKET, socket.SO_SNDBUF, 4096)
    except Exception:
        pass

    try:
        s.connect(("127.0.0.1", port))
    except Exception as e:
        sys.exit(1)

    chunk_size = 2048  # ~11.6ms of 16-bit 44.1kHz stereo

    try:
        while True:
            data = os.read(0, chunk_size)
            if not data:
                break
            s.sendall(data)
    except (BrokenPipeError, ConnectionResetError, KeyboardInterrupt):
        pass
    finally:
        try:
            s.close()
        except Exception:
            pass

if __name__ == "__main__":
    main()

