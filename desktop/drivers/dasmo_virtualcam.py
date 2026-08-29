# ==============================================================================
# DASMO CYBER CAPTURE // NATIVE WINDOWS VIRTUAL CAMERA & AUDIO BRIDGE
# Device Name: "DASMO CYBER CAPTURE"
# Compatible with: WhatsApp Desktop, Zoom, Microsoft Teams, Google Meet, OBS Studio
# ==============================================================================

import sys
import time
import urllib.request
import numpy as np

try:
    import cv2
except ImportError:
    print("[ERROR] OpenCV not installed. Run: pip install opencv-python")
    sys.exit(1)

try:
    import pyvirtualcam
except ImportError:
    print("[ERROR] pyvirtualcam not installed. Run: pip install pyvirtualcam")
    sys.exit(1)

# Accept IP from command line argument if provided, otherwise default to localhost
PHONE_IP = sys.argv[1] if len(sys.argv) > 1 else "127.0.0.1"
STREAM_URL = f"http://{PHONE_IP}:8080/video_feed"

print("==========================================================")
print("  🚀 DASMO CYBER CAPTURE // DIRECT DESKTOP DEVICE DRIVER   ")
print("  Device Registered: 'DASMO CYBER CAPTURE'                ")
print(f"  Streaming from: {STREAM_URL}")
print("==========================================================")

cap = cv2.VideoCapture(STREAM_URL)
if not cap.isOpened():
    print(f"[!] Could not connect to {STREAM_URL}. Ensure phone is streaming on the same Wi-Fi.")
    sys.exit(1)

ret, frame = cap.read()
if not ret or frame is None:
    print("[!] Failed to read initial frame from DASMO Cyber Stream.")
    sys.exit(1)

h, w, _ = frame.shape
print(f"[+] Video Stream Resolution: {w}x{h} @ 30 FPS")
print("[+] Registering Virtual Camera: 'DASMO CYBER CAPTURE'...")

def stream_camera(cam_instance):
    print(f"[SUCCESS] Virtual Camera active: '{cam_instance.device}'")
    print("[*] Open WhatsApp Desktop > Settings > Audio/Video > Camera")
    print(f"[*] Select: '{cam_instance.device}'")
    print("[*] Press Ctrl+C to stop.")
    while True:
        r, f = cap.read()
        if not r or f is None:
            time.sleep(0.01)
            continue
        cam_instance.send(f)
        cam_instance.sleep_until_next_frame()

try:
    try:
        # Register virtual camera named "DASMO CYBER CAPTURE"
        with pyvirtualcam.Camera(width=w, height=h, fps=30, device="DASMO CYBER CAPTURE", fmt=pyvirtualcam.PixelFormat.BGR) as cam:
            stream_camera(cam)
    except Exception as e:
        print(f"[*] Default device fallback notice: {e}")
        with pyvirtualcam.Camera(width=w, height=h, fps=30, fmt=pyvirtualcam.PixelFormat.BGR) as cam:
            stream_camera(cam)
except KeyboardInterrupt:
    print("\n[*] Stopping DASMO Cyber Capture Desktop Bridge...")
except Exception as fatal_e:
    print(f"[ERROR] {fatal_e}")
finally:
    cap.release()
    print("[*] Closed cleanly.")
