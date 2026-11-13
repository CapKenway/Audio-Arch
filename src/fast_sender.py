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

    s = None
    connected = False
    for _ in range(10):
        try:
            sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            try:
                sock.setsockopt(socket.SOL_SOCKET, socket.SO_SNDBUF, 4096)
            except Exception:
                pass
            sock.connect(("127.0.0.1", port))
            s = sock
            connected = True
            break
        except Exception:
            try:
                sock.close()
            except Exception:
                pass
            import time
            time.sleep(0.3)

    if not connected or s is None:
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

