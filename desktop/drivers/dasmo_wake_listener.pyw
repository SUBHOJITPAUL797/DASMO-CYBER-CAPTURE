"""
DASMO CYBER CAPTURE - Background Auto-Wake Watchdog Service
Silently listens for UDP broadcast wake packets from the DASMO Android App on port 8089.
If the Desktop software is not currently running, automatically launches it on the Windows desktop!
Uses ~10MB RAM, 0% CPU.
"""
import socket
import json
import subprocess
import os
import sys
import time

WAKE_PORT = 8089

def is_desktop_running():
    try:
        # Check tasklist for DASMO CYBER CAPTURE.exe
        output = subprocess.check_output(
            'tasklist /FI "IMAGENAME eq DASMO CYBER CAPTURE.exe" /NH',
            shell=True,
            text=True,
            creationflags=0x08000000 # CREATE_NO_WINDOW
        )
        if "DASMO CYBER CAPTURE.exe" in output:
            return True
    except Exception:
        pass
    return False

def find_desktop_executable():
    candidates = [
        r"C:\Program Files\DASMO CYBER CAPTURE\DASMO CYBER CAPTURE.exe",
        os.path.expandvars(r"%LOCALAPPDATA%\Programs\dasmo-cyber-capture-desktop\DASMO CYBER CAPTURE.exe"),
        os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "dist-msi", "win-unpacked", "DASMO CYBER CAPTURE.exe"),
        os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "dist", "win-unpacked", "DASMO CYBER CAPTURE.exe"),
    ]
    for p in candidates:
        if os.path.exists(p):
            return p
    return None

def launch_desktop():
    exe = find_desktop_executable()
    if exe:
        try:
            subprocess.Popen(
                [exe],
                creationflags=0x00000008 | 0x00000200, # DETACHED_PROCESS | CREATE_NEW_PROCESS_GROUP
                close_fds=True
            )
            return True
        except Exception:
            pass
    return False

def main():
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)

    try:
        sock.bind(('0.0.0.0', WAKE_PORT))
    except Exception as e:
        time.sleep(2)
        try:
            sock.bind(('0.0.0.0', WAKE_PORT))
        except Exception:
            sys.exit(0)

    last_launch_time = 0

    while True:
        try:
            data, addr = sock.recvfrom(4096)
            if not data:
                continue

            text = data.decode('utf-8', errors='ignore').strip()
            is_wake = "WAKE_DESKTOP" in text or "DASMO" in text

            now = time.time()
            if is_wake and (now - last_launch_time > 5.0):
                if not is_desktop_running():
                    last_launch_time = now
                    launch_desktop()
        except Exception:
            time.sleep(0.5)

if __name__ == '__main__':
    main()
