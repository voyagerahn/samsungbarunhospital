#!/usr/bin/env python3
"""
에뮬레이터 간 UDP 릴레이
사용법: python3 udp_relay.py
"""
import socket, subprocess, sys

RELAY_PORT   = 9999
NURSE_SERIAL = "emulator-5556"
ADB          = "/Users/ahnjaesung/Library/Android/sdk/platform-tools/adb"

def forward(payload: bytes):
    text = payload.decode("utf-8", errors="ignore").strip()
    safe = text.replace('"', '').replace('`', '').replace('$', '')
    cmd  = f'printf "%s" "{safe}" | nc -u -w1 127.0.0.1 {RELAY_PORT}'
    subprocess.run([ADB, "-s", NURSE_SERIAL, "shell", cmd], timeout=3, capture_output=True)
    print(f"[relay] → 간호사: {safe}")

def main():
    # emu redir 제거 (Mac 포트 9999 확보)
    subprocess.run([ADB, "-s", NURSE_SERIAL, "emu", "redir", "del", f"udp:{RELAY_PORT}"],
                   capture_output=True)

    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    sock.bind(("127.0.0.1", RELAY_PORT))
    print(f"[relay] 시작됨 (127.0.0.1:{RELAY_PORT}) — 종료: Ctrl+C")

    try:
        while True:
            data, addr = sock.recvfrom(1024)
            print(f"[relay] 수신 from {addr[0]}: {data!r}")
            forward(data)
    except KeyboardInterrupt:
        print("\n[relay] 종료")
    finally:
        sock.close()

if __name__ == "__main__":
    main()
