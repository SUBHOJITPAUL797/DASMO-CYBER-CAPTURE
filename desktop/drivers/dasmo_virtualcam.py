# ==============================================================================
# DASMO CYBER CAPTURE // ULTRA-LOW LATENCY REAL-TIME VIRTUAL CAMERA DRIVER
# Device Name: "DASMO CYBER CAPTURE"
# Latency: Sub-30ms Real-Time (Zero-Buffer Grabber)
# Compatible with: WhatsApp Desktop, Zoom, Microsoft Teams, Google Meet, OBS Studio
# ==============================================================================

import sys
import time
import threading
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

# Accept IP/Port from command line argument if provided
RAW_ARG = sys.argv[1] if len(sys.argv) > 1 else "127.0.0.1"
if ":" in RAW_ARG:
    parts = RAW_ARG.split(":")
    PHONE_IP = parts[0].replace("http://", "").replace("https://", "").strip()
    PORT = parts[1].strip()
else:
    PHONE_IP = RAW_ARG.replace("http://", "").replace("https://", "").strip()
    PORT = "8080"

STREAM_URL = f"http://{PHONE_IP}:{PORT}/video_feed"

print("==========================================================")
print("  🚀 DASMO CYBER CAPTURE // REAL-TIME DEVICE DRIVER       ")
print("  Device Registered: 'DASMO CYBER CAPTURE'                ")
print(f"  Streaming from: {STREAM_URL}")
print("==========================================================")

class FastRealtimeGrabber:
    """Zero-latency thread grabber that always drops stale buffer frames."""
    def __init__(self, url):
        self.url = url
        self.cap = cv2.VideoCapture(url, cv2.CAP_FFMPEG)
        self.cap.set(cv2.CAP_PROP_BUFFERSIZE, 1)
        self.latest_frame = None
        self.running = True
        self.lock = threading.Lock()
        self.has_first_frame = False

        self.thread = threading.Thread(target=self._update_loop, daemon=True)
        self.thread.start()

    def _update_loop(self):
        while self.running:
            if not self.cap.isOpened():
                time.sleep(0.5)
                self.cap = cv2.VideoCapture(self.url, cv2.CAP_FFMPEG)
                self.cap.set(cv2.CAP_PROP_BUFFERSIZE, 1)
                continue

            # Read and keep only the freshest frame
            ret, frame = self.cap.read()
            if ret and frame is not None:
                with self.lock:
                    self.latest_frame = frame
                    self.has_first_frame = True
            else:
                time.sleep(0.005)

    def read(self):
        with self.lock:
            return self.latest_frame is not None, self.latest_frame

    def release(self):
        self.running = False
        if self.cap:
            self.cap.release()

grabber = FastRealtimeGrabber(STREAM_URL)

# Wait up to 5s for first frame
for _ in range(50):
    if grabber.has_first_frame:
        break
    time.sleep(0.1)

ret, frame = grabber.read()
if not ret or frame is None:
    print(f"[!] Could not connect to {STREAM_URL}. Ensure phone app is active on same Wi-Fi.")
    grabber.release()
    sys.exit(1)

h, w, _ = frame.shape
print(f"[+] Video Stream Resolution: {w}x{h} @ Real-Time FPS")
print("[+] Registering Virtual Camera: 'DASMO CYBER CAPTURE'...")

def stream_camera(cam_instance):
    print(f"[SUCCESS] Virtual Camera active: '{cam_instance.device}'")
    print("[*] Open WhatsApp Desktop > Settings > Audio & Video > Camera")
    print(f"[*] Select: '{cam_instance.device}'")
    print("[*] Latency: Zero Buffer (<30ms)")
    print("[*] Press Ctrl+C to stop.")
    
    target_w = cam_instance.width
    target_h = cam_instance.height
    last_valid_frame = None

    while True:
        r, f = grabber.read()
        if r and f is not None:
            # Dynamic Resizing: If resolution or rotation changed, adapt smoothly without crashing pyvirtualcam
            cur_h, cur_w = f.shape[:2]
            if cur_w != target_w or cur_h != target_h:
                f = cv2.resize(f, (target_w, target_h), interpolation=cv2.INTER_LINEAR)
            last_valid_frame = f
            cam_instance.send(f)
        elif last_valid_frame is not None:
            # Hold last valid frame during momentary network hiccups
            cam_instance.send(last_valid_frame)
        else:
            time.sleep(0.005)
            continue
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
    grabber.release()
    print("[*] Closed cleanly.")
