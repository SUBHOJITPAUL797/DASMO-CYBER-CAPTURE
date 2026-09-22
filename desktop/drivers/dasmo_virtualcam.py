# ==============================================================================
# DASMO CYBER CAPTURE // ULTRA-LOW LATENCY REAL-TIME VIRTUAL CAMERA DRIVER
# Transport: Dedicated WebSocket binary JPEG (/ws/video)
# Engine:    Hardware-accelerated SIMD decoding (simplejpeg / libjpeg-turbo)
# Anti-Lag:  Socket buffer draining (select.select) — 0 queued stale frames
# Framerate: True 60.0 FPS clock synchronization into DirectShow
# Device:    "DASMO CAMERA" -> WhatsApp / Zoom / Teams / Google Meet / OBS
# ==============================================================================

import os
import sys
import time
import threading
import struct
import socket
import select
import base64
import urllib.request
import numpy as np

if hasattr(sys.stdout, 'reconfigure'):
    sys.stdout.reconfigure(encoding='utf-8', errors='replace', line_buffering=True)
if hasattr(sys.stderr, 'reconfigure'):
    sys.stderr.reconfigure(encoding='utf-8', errors='replace', line_buffering=True)

HAS_SIMPLEJPEG = False
try:
    import simplejpeg
    HAS_SIMPLEJPEG = True
except ImportError:
    pass

try:
    import cv2
except ImportError:
    print("[ERROR] OpenCV not installed. Run: pip install opencv-python", flush=True)
    sys.exit(1)

try:
    import pyvirtualcam
except ImportError:
    print("[ERROR] pyvirtualcam not installed. Run: pip install pyvirtualcam", flush=True)
    sys.exit(1)

RAW_ARG = sys.argv[1] if len(sys.argv) > 1 else "127.0.0.1"
if ":" in RAW_ARG:
    parts = RAW_ARG.split(":")
    PHONE_IP = parts[0].replace("http://", "").replace("https://", "").strip()
    PORT = int(parts[1].strip())
else:
    PHONE_IP = RAW_ARG.replace("http://", "").replace("https://", "").strip()
    PORT = 8080

TARGET_FPS = int(sys.argv[2]) if len(sys.argv) > 2 else 60
RAW_BG_MODE = sys.argv[3].lower() if len(sys.argv) > 3 else "raw"
RAW_BG_COLOR = sys.argv[4] if len(sys.argv) > 4 else "255,255,255"

INIT_COLOR = (255, 255, 255)
try:
    c_parts = [int(x.strip()) for x in RAW_BG_COLOR.split(",")]
    if len(c_parts) == 3:
        INIT_COLOR = (c_parts[0], c_parts[1], c_parts[2])
except Exception:
    pass

INIT_MODE = "color" if RAW_BG_MODE in ["color", "white", "blue", "red"] else "raw"

WS_URL   = f"ws://{PHONE_IP}:{PORT}/ws/video"
HTTP_URL = f"http://{PHONE_IP}:{PORT}/video_feed"

print("==========================================================", flush=True)
print("  [CAM] DASMO CYBER CAPTURE // 60 FPS WEBSOCKET DRIVER   ", flush=True)
print(f"  Phone Target: {PHONE_IP}:{PORT}", flush=True)
print(f"  Stream URL:   {WS_URL}", flush=True)
print(f"  Target Clock: {TARGET_FPS} FPS (Ultra-Smooth Zero-Lag)", flush=True)
print(f"  SIMD Engine:  {'simplejpeg (libjpeg-turbo direct RGB)' if HAS_SIMPLEJPEG else 'OpenCV fallback'}", flush=True)
print(f"  AI Background: {'ACTIVE (' + str(INIT_COLOR) + ')' if INIT_MODE == 'color' else 'RAW (Ready)'}", flush=True)
print("==========================================================", flush=True)


class AIBgSegmenter:
    """High-Definition Studio Matting & AI Segmentation Engine for Portal Live Photos & Virtual Webcam."""
    def __init__(self, initial_mode="raw", initial_color=(255, 255, 255)):
        self.net = None
        self.input_dims = (256, 256)
        driver_dir = os.path.dirname(os.path.abspath(__file__))
        
        # Prioritize 256x256 square portrait model for 2x finer facial & hair resolution
        model_candidates = [
            (os.path.join(driver_dir, "selfie_segmentation.tflite"), (256, 256)),
            (os.path.join(driver_dir, "..", "src", "renderer", "vendor", "mediapipe", "selfie_segmentation.tflite"), (256, 256)),
            (os.path.join(driver_dir, "selfie_segmentation_landscape.tflite"), (256, 144)),
            (os.path.join(driver_dir, "..", "src", "renderer", "vendor", "mediapipe", "selfie_segmentation_landscape.tflite"), (256, 144)),
        ]
        
        for mp, dims in model_candidates:
            if os.path.exists(mp):
                try:
                    self.net = cv2.dnn.readNetFromTFLite(mp)
                    self.input_dims = dims
                    print(f"[+] Loaded Studio AI Segmentation Engine: {os.path.basename(mp)} {dims}", flush=True)
                    break
                except Exception as e:
                    print(f"[!] Could not load TFLite model {mp}: {e}", flush=True)

        self.lock = threading.Lock()
        self.bg_mode = initial_mode
        self.bg_color = initial_color
        self.latest_raw_frame = None
        self.latest_alpha = None
        self.latest_inv_alpha = None
        self.latest_mask_dims = (0, 0)
        self.running = True

        if self.net is not None:
            self.worker_thread = threading.Thread(target=self._inference_loop, daemon=True)
            self.worker_thread.start()
        else:
            print("[!] AI background model not found. Running in standard raw mode.", flush=True)

    def set_mode(self, mode, color=None):
        with self.lock:
            self.bg_mode = mode
            if color is not None:
                self.bg_color = color
        print(f"[CAM] Virtual Camera BG set to: {mode} {self.bg_color if mode == 'color' else ''}", flush=True)

    def update_raw_frame(self, frame):
        with self.lock:
            self.latest_raw_frame = frame

    def _inference_loop(self):
        while self.running:
            with self.lock:
                mode = self.bg_mode
                frame = self.latest_raw_frame

            if mode != "color" or frame is None or self.net is None:
                time.sleep(0.015)
                continue

            try:
                h, w = frame.shape[:2]
                target_w, target_h = self.input_dims

                # Letterbox to preserve human body & head proportions without squishing
                scale = min(target_w / w, target_h / h)
                nw, nh = max(1, int(w * scale)), max(1, int(h * scale))
                scaled = cv2.resize(frame, (nw, nh), interpolation=cv2.INTER_LINEAR)

                canvas = np.zeros((target_h, target_w, 3), dtype=np.uint8)
                dx = (target_w - nw) // 2
                dy = (target_h - nh) // 2
                canvas[dy:dy+nh, dx:dx+nw] = scaled

                # Feed RGB normalized image into MediaPipe TFLite
                blob = cv2.dnn.blobFromImage(canvas, 1.0 / 255.0, (target_w, target_h), swapRB=False)
                self.net.setInput(blob)
                out = self.net.forward()
                prob_small = out[0, 0] # Continuous float32 [0.0, 1.0]

                # Unpad back to original aspect ratio
                prob_cropped = prob_small[dy:dy+nh, dx:dx+nw]

                # Sub-pixel continuous probability upscaling with bicubic interpolation
                prob_full = cv2.resize(prob_cropped, (w, h), interpolation=cv2.INTER_CUBIC)
                prob_full = np.clip(prob_full, 0.0, 1.0)

                # Smoothstep Hermite sigmoid curve for razor-sharp, natural human edges:
                # 0.52 to 0.78 cleanly separates person from background chairs & shadows
                edge0, edge1 = 0.52, 0.78
                t = np.clip((prob_full - edge0) / (edge1 - edge0), 0.0, 1.0)
                alpha = (t * t * (3.0 - 2.0 * t)).astype(np.float32)

                # Morphological noise & island removal:
                # Retain only the main connected person component, eliminating background chair parts
                core = (prob_full > 0.60).astype(np.uint8)
                num_labels, labels, stats, _ = cv2.connectedComponentsWithStats(core, connectivity=8)
                if num_labels > 1:
                    areas = stats[1:, cv2.CC_STAT_AREA]
                    largest_label = 1 + np.argmax(areas)
                    person_mask = (labels == largest_label).astype(np.uint8)
                    kernel = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (15, 15))
                    person_mask = cv2.dilate(person_mask, kernel).astype(np.float32)
                    alpha = alpha * person_mask

                # Antialiasing Gaussian feathering on alpha (smooth sub-pixel transition)
                alpha = cv2.GaussianBlur(alpha, (5, 5), 0)
                inv_alpha = 1.0 - alpha

                with self.lock:
                    self.latest_alpha = alpha
                    self.latest_inv_alpha = inv_alpha
                    self.latest_mask_dims = (w, h)
            except Exception as e:
                time.sleep(0.04)

            time.sleep(0.015)

    def apply_background(self, frame):
        with self.lock:
            mode = self.bg_mode
            color = self.bg_color
            alpha = self.latest_alpha
            inv_alpha = self.latest_inv_alpha
            mask_dims = self.latest_mask_dims

        if mode != "color" or alpha is None:
            return frame

        h, w = frame.shape[:2]
        if mask_dims != (w, h):
            alpha = cv2.resize(alpha, (w, h), interpolation=cv2.INTER_LINEAR)
            inv_alpha = 1.0 - alpha

        bg_frame = np.full((h, w, 3), color, dtype=np.uint8)
        # Native OpenCV SIMD linear alpha blending: true 60 FPS sub-pixel quality!
        return cv2.blendLinear(frame, bg_frame, alpha, inv_alpha)



ai_segmenter = AIBgSegmenter(initial_mode=INIT_MODE, initial_color=INIT_COLOR)


def stdin_control_loop(segmenter):
    """Listens for dynamic background color commands from desktop companion UI."""
    while True:
        try:
            line = sys.stdin.readline()
            if not line:
                break
            line = line.strip()
            if not line:
                continue
            parts = line.split()
            if parts[0] == "BG_MODE":
                if parts[1] == "raw":
                    segmenter.set_mode("raw")
                elif parts[1] == "color" and len(parts) >= 5:
                    r = int(parts[2])
                    g = int(parts[3])
                    b = int(parts[4])
                    segmenter.set_mode("color", (r, g, b))
        except Exception:
            time.sleep(0.1)


stdin_thread = threading.Thread(target=stdin_control_loop, args=(ai_segmenter,), daemon=True)
stdin_thread.start()


def decode_jpeg_to_rgb(jpeg_bytes):
    """Decodes JPEG directly into RGB numpy uint8 array with zero intermediate overhead."""
    if HAS_SIMPLEJPEG:
        try:
            return simplejpeg.decode_jpeg(jpeg_bytes, colorspace='RGB')
        except Exception:
            pass
    arr = np.frombuffer(jpeg_bytes, dtype=np.uint8)
    bgr = cv2.imdecode(arr, cv2.IMREAD_COLOR)
    if bgr is not None:
        return cv2.cvtColor(bgr, cv2.COLOR_BGR2RGB)
    return None


class WebSocketJpegGrabber:
    def __init__(self, ip, port):
        self.ip = ip
        self.port = port
        self.latest_frame = None
        self.lock = threading.Lock()
        self.running = True
        self.has_first_frame = False
        self.connected = False
        self.thread = threading.Thread(target=self._run, daemon=True)
        self.thread.start()

    def _ws_handshake(self, sock):
        key = base64.b64encode(os.urandom(16)).decode()
        req = (
            f"GET /ws/video HTTP/1.1\r\n"
            f"Host: {self.ip}:{self.port}\r\n"
            f"Upgrade: websocket\r\n"
            f"Connection: Upgrade\r\n"
            f"Sec-WebSocket-Key: {key}\r\n"
            f"Sec-WebSocket-Version: 13\r\n"
            f"\r\n"
        )
        sock.sendall(req.encode())
        resp = b""
        while b"\r\n\r\n" not in resp:
            chunk = sock.recv(4096)
            if not chunk:
                raise ConnectionError("WS handshake: server closed")
            resp += chunk
        if b"101 Switching Protocols" not in resp:
            raise ConnectionError(f"WS handshake failed: {resp[:200]}")

    def _recv_exact(self, sock, n):
        buf = b""
        while len(buf) < n:
            chunk = sock.recv(n - len(buf))
            if not chunk:
                raise ConnectionError("Socket closed mid-frame")
            buf += chunk
        return buf

    def _read_ws_frame(self, sock):
        header = self._recv_exact(sock, 2)
        opcode = header[0] & 0x0F
        masked = (header[1] & 0x80) != 0
        payload_len = header[1] & 0x7F
        if payload_len == 126:
            payload_len = struct.unpack("!H", self._recv_exact(sock, 2))[0]
        elif payload_len == 127:
            payload_len = struct.unpack("!Q", self._recv_exact(sock, 8))[0]
        mask_key = self._recv_exact(sock, 4) if masked else None
        payload = bytearray(self._recv_exact(sock, payload_len))
        if masked and mask_key:
            for i in range(len(payload)):
                payload[i] ^= mask_key[i % 4]
        if opcode == 8:
            raise ConnectionError("Server sent WS close frame")
        return bytes(payload) if opcode == 2 else None

    def _run(self):
        while self.running:
            sock = None
            try:
                sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
                sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
                sock.settimeout(5)
                sock.connect((self.ip, self.port))
                sock.settimeout(15)
                self._ws_handshake(sock)
                self.connected = True
                print(f"[+] WebSocket connected: {self.ip}:{self.port}/ws/video")
                while self.running:
                    payload = self._read_ws_frame(sock)
                    if payload and len(payload) > 100:
                        # ANTI-LAG ZERO-QUEUE CONFLATION:
                        # If more frames are already buffered in the OS socket queue due to Wi-Fi jitter,
                        # drain older frames immediately and decode only the freshest frame!
                        while True:
                            rlist, _, _ = select.select([sock], [], [], 0)
                            if not rlist:
                                break
                            try:
                                fresher = self._read_ws_frame(sock)
                                if fresher and len(fresher) > 100:
                                    payload = fresher
                                else:
                                    break
                            except Exception:
                                break

                        rgb_frame = decode_jpeg_to_rgb(payload)
                        if rgb_frame is not None:
                            with self.lock:
                                self.latest_frame = rgb_frame
                                self.has_first_frame = True
            except Exception as e:
                self.connected = False
                if self.running:
                    print(f"[*] WS video notice ({type(e).__name__}: {e}) - auto-reconnecting in 0.5s...", flush=True)
                    time.sleep(0.5)
            finally:
                if sock:
                    try: sock.close()
                    except: pass

    def read(self):
        with self.lock:
            f = self.latest_frame
            return f is not None, f

    def release(self):
        self.running = False


class MjpegJpegGrabber:
    def __init__(self, url):
        self.url = url
        self.latest_frame = None
        self.lock = threading.Lock()
        self.running = True
        self.has_first_frame = False
        self.thread = threading.Thread(target=self._run, daemon=True)
        self.thread.start()

    def _run(self):
        while self.running:
            try:
                req = urllib.request.Request(self.url)
                req.add_header("User-Agent", "DASMO-CYBER-CAPTURE-Desktop")
                with urllib.request.urlopen(req, timeout=6) as resp:
                    print("[+] MJPEG fallback connected", flush=True)
                    buf = b""
                    while self.running:
                        chunk = resp.read(8192)
                        if not chunk:
                            break
                        buf += chunk
                        while True:
                            start = buf.find(b'\xff\xd8')
                            if start == -1:
                                buf = b""
                                break
                            end = buf.find(b'\xff\xd9', start + 2)
                            if end == -1:
                                buf = buf[start:]
                                break
                            jpeg_data = buf[start:end + 2]
                            buf = buf[end + 2:]
                            rgb_frame = decode_jpeg_to_rgb(jpeg_data)
                            if rgb_frame is not None:
                                with self.lock:
                                    self.latest_frame = rgb_frame
                                    self.has_first_frame = True
            except Exception as e:
                if self.running:
                    print(f"[*] MJPEG fallback reconnecting ({e})...", flush=True)
                    time.sleep(1.0)

    def read(self):
        with self.lock:
            f = self.latest_frame
            return f is not None, f

    def release(self):
        self.running = False


print("[*] Connecting via WebSocket (zero green-screen mode)...")
grabber = WebSocketJpegGrabber(PHONE_IP, PORT)
for _ in range(50):
    if grabber.has_first_frame:
        break
    time.sleep(0.1)

if not grabber.has_first_frame:
    print("[!] WebSocket: no frame. Trying manual MJPEG fallback...")
    grabber.release()
    grabber = MjpegJpegGrabber(HTTP_URL)
    for _ in range(60):
        if grabber.has_first_frame:
            break
        time.sleep(0.1)

ret, initial_frame = grabber.read()
if not ret or initial_frame is None:
    print(f"[ERROR] No frame from {PHONE_IP}:{PORT}. Is DASMO app running?")
    grabber.release()
    sys.exit(1)

h, w = initial_frame.shape[:2]
print(f"[+] Stream: {w}x{h}")
print("[+] Launching Windows Virtual Camera (DASMO CAMERA)...")


def stream_loop(cam_instance):
    print(f"\n[SUCCESS] Virtual Camera ACTIVE: '{cam_instance.device}' @ {TARGET_FPS} FPS", flush=True)
    print("[*] WhatsApp / Zoom / Teams / OBS -> Settings -> Camera", flush=True)
    print(f"[*]   Select: '{cam_instance.device}'", flush=True)
    print(f"[*] Pure 60 FPS zero-lag video active!\n", flush=True)
    target_w = cam_instance.width
    target_h = cam_instance.height
    last_valid_frame = None
    while True:
        r, f = grabber.read()
        if r and f is not None:
            if f.shape[1] != target_w or f.shape[0] != target_h:
                f = cv2.resize(f, (target_w, target_h), interpolation=cv2.INTER_LINEAR)

            # Feed frame to AI segmentation worker
            ai_segmenter.update_raw_frame(f)

            # Apply live background replacement (White / Blue / Custom)
            f = ai_segmenter.apply_background(f)

            last_valid_frame = f
            cam_instance.send(f)
        elif last_valid_frame is not None:
            cam_instance.send(last_valid_frame)
        else:
            time.sleep(0.002)
            continue
        cam_instance.sleep_until_next_frame()


launched = False
for device_name in ["DASMO CAMERA", "DASMO CYBER CAPTURE", "Unity Video Capture", None]:
    if launched:
        break
    try:
        kwargs = {'width': w, 'height': h, 'fps': TARGET_FPS, 'fmt': pyvirtualcam.PixelFormat.RGB}
        if device_name:
            kwargs['device'] = device_name
        with pyvirtualcam.Camera(**kwargs) as cam:
            launched = True
            stream_loop(cam)
    except KeyboardInterrupt:
        launched = True
        break
    except Exception as e:
        print(f"[*] '{device_name}' unavailable ({type(e).__name__}), trying next...", flush=True)
        continue

if not launched:
    print("\n[ERROR] No virtual camera backend found.")
    print("[*] Fix: Run 'install_dasmo_camera.bat' as Administrator, or install OBS Studio.")

grabber.release()
print("[*] DASMO Virtual Camera closed.")
