# ==============================================================================
# DASMO CYBER CAPTURE // REAL-TIME FULL-DUPLEX AUDIO ENGINE
# 1. PC System Audio -> Streamed wirelessly to Phone Speaker (WASAPI Loopback)
# 2. Phone Microphone -> Streamed to Windows Virtual Mic (48kHz PCM via VB-Cable)
# ==============================================================================

import os
import sys
import time
import socket
import threading
import queue
import ctypes
import struct
import base64
import urllib.request
import numpy as np

if hasattr(sys.stdout, 'reconfigure'):
    sys.stdout.reconfigure(encoding='utf-8', errors='replace', line_buffering=True)
if hasattr(sys.stderr, 'reconfigure'):
    sys.stderr.reconfigure(encoding='utf-8', errors='replace', line_buffering=True)

HAS_PYAUDIO_WPATCH = False
try:
    import pyaudiowpatch as pyaudio
    HAS_PYAUDIO_WPATCH = True
except ImportError:
    pass

HAS_SOUNDDEVICE = False
try:
    import sounddevice as sd
    HAS_SOUNDDEVICE = True
except ImportError:
    pass

if not HAS_PYAUDIO_WPATCH and not HAS_SOUNDDEVICE:
    print("[ERROR] Neither PyAudioWPatch nor sounddevice is installed.", flush=True)
    print("[*] Please run: pip install PyAudioWPatch sounddevice numpy", flush=True)
    sys.exit(1)

# Single shared PyAudio instance for the process
GLOBAL_PA = pyaudio.PyAudio() if HAS_PYAUDIO_WPATCH else None

RAW_ARG = sys.argv[1] if len(sys.argv) > 1 else "127.0.0.1"
if ":" in RAW_ARG:
    parts = RAW_ARG.split(":")
    PHONE_IP = parts[0].replace("http://", "").replace("https://", "").strip()
    PORT = int(parts[1].strip())
else:
    PHONE_IP = RAW_ARG.replace("http://", "").replace("https://", "").strip()
    PORT = 8080

SAMPLE_RATE = 48000
CHANNELS = 1
BLOCK_SIZE = 960  # 20.0ms chunk size at 48kHz (WebRTC standard for jitter resilience & low latency)

WS_MIC_URL = f"ws://{PHONE_IP}:{PORT}/ws/mic"
WS_SPEAKER_URL = f"ws://{PHONE_IP}:{PORT}/ws/speaker"
AUDIO_FEED_URL = f"http://{PHONE_IP}:{PORT}/audio_feed"

print("==========================================================", flush=True)
print("  [AUDIO] DASMO CYBER CAPTURE // WEBSOCKET AUDIO ENGINE   ", flush=True)
print(f"  Phone Target: {PHONE_IP}:{PORT}", flush=True)
print(f"  [1] PC System Audio  ==>  Mobile Phone Speaker ({WS_SPEAKER_URL})", flush=True)
print(f"  [2] Phone Microphone ==>  PC Virtual Mic ({WS_MIC_URL})", flush=True)
print(f"  Backend: {'PyAudioWPatch (Native WASAPI)' if HAS_PYAUDIO_WPATCH else 'sounddevice'}", flush=True)
print("==========================================================", flush=True)


def ws_handshake(sock, endpoint, host, port):
    key = base64.b64encode(os.urandom(16)).decode()
    req = (
        f"GET {endpoint} HTTP/1.1\r\n"
        f"Host: {host}:{port}\r\n"
        f"Upgrade: websocket\r\n"
        f"Connection: Upgrade\r\n"
        f"Sec-WebSocket-Key: {key}\r\n"
        f"Sec-WebSocket-Version: 13\r\n\r\n"
    )
    sock.sendall(req.encode('utf-8'))
    resp = b""
    while b"\r\n\r\n" not in resp:
        chunk = sock.recv(4096)
        if not chunk:
            raise ConnectionError("WS handshake: socket closed")
        resp += chunk
    if b"101 Switching Protocols" not in resp:
        raise ConnectionError(f"WS handshake failed: {resp[:200]}")


def send_ws_binary(sock, data):
    length = len(data)
    mask = os.urandom(4)
    if length < 126:
        header = bytearray([0x82, 0x80 | length])
    elif length <= 65535:
        header = bytearray([0x82, 0x80 | 126]) + struct.pack("!H", length)
    else:
        header = bytearray([0x82, 0x80 | 127]) + struct.pack("!Q", length)
    header.extend(mask)
    mask_bytes = (mask * ((length // 4) + 1))[:length]
    masked = np.bitwise_xor(np.frombuffer(data, dtype=np.uint8), np.frombuffer(mask_bytes, dtype=np.uint8)).tobytes()
    sock.sendall(header + masked)


def recv_exact(sock, n):
    buf = bytearray(n)
    view = memoryview(buf)
    pos = 0
    while pos < n:
        chunk_len = sock.recv_into(view[pos:])
        if not chunk_len:
            raise ConnectionError("Socket closed mid-frame")
        pos += chunk_len
    return bytes(buf)


def recv_ws_binary(sock):
    header = recv_exact(sock, 2)
    opcode = header[0] & 0x0F
    masked = (header[1] & 0x80) != 0
    payload_len = header[1] & 0x7F
    if payload_len == 126:
        payload_len = struct.unpack("!H", recv_exact(sock, 2))[0]
    elif payload_len == 127:
        payload_len = struct.unpack("!Q", recv_exact(sock, 8))[0]
    mask_key = recv_exact(sock, 4) if masked else None
    payload = recv_exact(sock, payload_len)
    if masked and mask_key and payload:
        mask_bytes = (mask_key * ((len(payload) // 4) + 1))[:len(payload)]
        payload = np.bitwise_xor(np.frombuffer(payload, dtype=np.uint8), np.frombuffer(mask_bytes, dtype=np.uint8)).tobytes()
    if opcode == 8:
        raise ConnectionError("Server sent WS close")
    return payload if opcode == 2 else None


# --- 1. PC Audio -> Phone Speaker (WASAPI System Loopback) ---
class PcToPhoneSpeakerTransmitter:
    def __init__(self, ip, port):
        self.ip = ip
        self.port = port
        self.audio_queue = queue.Queue(maxsize=30)  # ~600ms buffer capacity for flawless Wi-Fi jitter tolerance
        self.running = True
        self.current_device_name = "Detecting..."
        
        # Start dedicated network sender thread
        self.sender_thread = threading.Thread(target=self._network_sender_loop, daemon=True)
        self.sender_thread.start()
        
        # Start WASAPI capture thread
        self.capture_thread = threading.Thread(target=self._capture_loop, daemon=True)
        self.capture_thread.start()

    def _network_sender_loop(self):
        """Dedicated thread to send queued PCM chunks to phone without blocking audio callbacks."""
        while self.running:
            sock = None
            try:
                sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
                sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
                sock.settimeout(5)
                sock.connect((self.ip, self.port))
                sock.settimeout(None)

                is_ws = True
                try:
                    ws_handshake(sock, "/ws/speaker", self.ip, self.port)
                    print(f"[+] Connected to Phone Speaker via WebSocket (ws://{self.ip}:{self.port}/ws/speaker)", flush=True)
                except Exception:
                    is_ws = False
                    http_header = (
                        f"POST /speaker_feed HTTP/1.1\r\n"
                        f"Host: {self.ip}:{self.port}\r\n"
                        f"Content-Type: audio/x-raw; rate={SAMPLE_RATE}; channels=1; format=s16le\r\n"
                        f"Connection: keep-alive\r\n\r\n"
                    ).encode('utf-8')
                    sock.sendall(http_header)
                    print(f"[+] Connected to Phone Speaker via HTTP fallback ({self.ip}:{self.port}/speaker_feed)", flush=True)

                while self.running:
                    try:
                        chunk = self.audio_queue.get(timeout=0.2)
                    except queue.Empty:
                        continue

                    # Anti-lag sync: Only trim if extreme network stall occurred (>25 chunks = >500ms lag)
                    if self.audio_queue.qsize() > 25:
                        while self.audio_queue.qsize() > 5:
                            try:
                                self.audio_queue.get_nowait()
                            except Exception:
                                break

                    if is_ws:
                        send_ws_binary(sock, chunk)
                    else:
                        sock.sendall(chunk)

            except Exception as e:
                if self.running:
                    print(f"[*] Speaker relay notice: {e}", flush=True)
                    time.sleep(1.0)
            finally:
                if sock:
                    try:
                        sock.close()
                    except Exception:
                        pass

    def _capture_loop(self):
        """Captures system audio using WASAPI loopback, tracking active Windows default output."""
        try:
            ctypes.windll.ole32.CoInitialize(None)
        except Exception:
            pass

        while self.running:
            try:
                if HAS_PYAUDIO_WPATCH and GLOBAL_PA:
                    self._run_pyaudiowpatch_loop()
                elif HAS_SOUNDDEVICE:
                    self._run_sounddevice_loop()
            except Exception as e:
                if self.running:
                    print(f"[*] Loopback capture notice: {e}", flush=True)
                    time.sleep(2.0)

    def _run_pyaudiowpatch_loop(self):
        p = GLOBAL_PA
        loopback_dev = None
        try:
            cur_def = p.get_default_wasapi_loopback()
            # CRITICAL: Prevent self-capture feedback loop!
            # If default loopback is VB-Cable / Virtual Cable, DO NOT capture it,
            # because PhoneMicToPcReceiver injects phone mic into VB-Cable!
            if cur_def and not any(k in cur_def['name'].lower() for k in ['cable', 'virtual', 'vb-audio']):
                loopback_dev = cur_def
            elif cur_def:
                print(f"[!] Notice: Default loopback is virtual '{cur_def['name']}'. Searching for physical speakers...", flush=True)
        except Exception:
            pass

        if not loopback_dev:
            # First pass: find a physical loopback device (Speakers, Realtek, Headphones)
            for i in range(p.get_device_count()):
                try:
                    dev = p.get_device_info_by_index(i)
                    if dev.get('isLoopbackDevice'):
                        name_lower = dev['name'].lower()
                        if not any(k in name_lower for k in ['cable', 'virtual', 'vb-audio']):
                            loopback_dev = dev
                            break
                except Exception:
                    pass

        if not loopback_dev:
            # Fallback to any loopback device if no physical one found
            for i in range(p.get_device_count()):
                try:
                    dev = p.get_device_info_by_index(i)
                    if dev.get('isLoopbackDevice'):
                        loopback_dev = dev
                        break
                except Exception:
                    pass

        if not loopback_dev:
            print("[!] No WASAPI loopback device found. Waiting...", flush=True)
            time.sleep(3.0)
            return

        dev_channels = int(loopback_dev['maxInputChannels'])
        dev_rate = int(loopback_dev['defaultSampleRate'])
        self.current_device_name = loopback_dev['name']
        print(f"[+] Capturing PC system audio from: '{self.current_device_name}' ({dev_rate}Hz, {dev_channels}ch)", flush=True)

        last_rms_log = 0.0

        def callback(in_data, frame_count, time_info, status):
            nonlocal last_rms_log
            if not self.running or not in_data:
                return (None, pyaudio.paContinue)

            try:
                if dev_channels == 2:
                    pcm_stereo = np.frombuffer(in_data, dtype=np.int16).reshape(-1, 2)
                    mono_samples = ((pcm_stereo[:, 0].astype(np.int32) + pcm_stereo[:, 1].astype(np.int32)) // 2).astype(np.int16)
                elif dev_channels == 1:
                    mono_samples = np.frombuffer(in_data, dtype=np.int16)
                else:
                    pcm_all = np.frombuffer(in_data, dtype=np.int16).reshape(-1, dev_channels)
                    mono_samples = pcm_all[:, 0].astype(np.int16)

                # Dynamic resampling if hardware sample rate != 48000Hz (endpoint=False ensures continuous block transitions)
                if dev_rate != SAMPLE_RATE and len(mono_samples) > 0:
                    out_count = int(round(len(mono_samples) * SAMPLE_RATE / dev_rate))
                    mono_samples = np.interp(
                        np.linspace(0, len(mono_samples), out_count, endpoint=False),
                        np.arange(len(mono_samples)),
                        mono_samples
                    ).astype(np.int16)

                # Periodic volume logging so user knows audio is actively captured
                now = time.time()
                if now - last_rms_log > 3.0:
                    last_rms_log = now
                    max_amp = int(np.max(np.abs(mono_samples))) if len(mono_samples) > 0 else 0
                    if max_amp > 150:
                        print(f"[SPEAKER STREAMING] Audio active: Peak = {max_amp} / 32767 -> Playing on Phone", flush=True)

                pcm_bytes = mono_samples.tobytes()

                # High capacity buffer: allow up to 25 chunks (~500ms) without dropping to absorb network hiccups
                while self.audio_queue.qsize() >= 25:
                    try:
                        self.audio_queue.get_nowait()
                    except Exception:
                        break
                try:
                    self.audio_queue.put_nowait(pcm_bytes)
                except Exception:
                    pass
            except Exception:
                pass

            return (None, pyaudio.paContinue)

        stream = p.open(
            format=pyaudio.paInt16,
            channels=dev_channels,
            rate=dev_rate,
            input=True,
            input_device_index=loopback_dev['index'],
            frames_per_buffer=BLOCK_SIZE,
            stream_callback=callback
        )
        print("[SUCCESS] PC Audio is now streaming to your Mobile Phone Speaker!", flush=True)
        stream.start_stream()

        try:
            while self.running and stream.is_active():
                time.sleep(1.0)
                # Seamlessly re-attach if user switches Windows default output device
                try:
                    cur_def = p.get_default_wasapi_loopback()
                    if cur_def and cur_def['index'] != loopback_dev['index']:
                        if not any(k in cur_def['name'].lower() for k in ['cable', 'virtual', 'vb-audio']):
                            print(f"[*] Windows Sound Output changed to: '{cur_def['name']}'. Re-attaching capture...", flush=True)
                            break
                except Exception:
                    pass
        finally:
            try:
                stream.stop_stream()
                stream.close()
            except Exception:
                pass

    def _run_sounddevice_loop(self):
        loopback_dev = None
        for i, dev in enumerate(sd.query_devices()):
            name = dev['name'].lower()
            if dev['max_input_channels'] > 0 and 'loopback' in name and not any(k in name for k in ['cable', 'virtual', 'vb-audio']):
                loopback_dev = i
                break

        def audio_callback(indata, frames, time_info, status):
            if not self.running:
                return
            pcm_data = (np.clip(indata[:, 0], -1.0, 1.0) * 32767).astype(np.int16).tobytes()
            while self.audio_queue.qsize() >= 12:
                try:
                    self.audio_queue.get_nowait()
                except Exception:
                    break
            try:
                self.audio_queue.put_nowait(pcm_data)
            except Exception:
                pass

        stream_kwargs = {
            'samplerate': SAMPLE_RATE,
            'channels': 1,
            'dtype': 'float32',
            'blocksize': BLOCK_SIZE,
            'callback': audio_callback
        }
        if loopback_dev is not None:
            stream_kwargs['device'] = loopback_dev

        with sd.InputStream(**stream_kwargs):
            print("[SUCCESS] PC Audio is streaming to Mobile Phone Speaker (sounddevice)!", flush=True)
            while self.running:
                time.sleep(0.5)


# --- 2. Phone Microphone -> PC (Receiver & Virtual Cable Injector) ---
class PhoneMicToPcReceiver:
    def __init__(self, ip, port):
        self.ip = ip
        self.port = port
        self.running = True
        self.thread = threading.Thread(target=self._run, daemon=True)
        self.thread.start()

    def _run(self):
        try:
            ctypes.windll.ole32.CoInitialize(None)
        except Exception:
            pass

        while self.running:
            try:
                self._stream_mic()
            except Exception as e:
                print(f"[*] Phone mic reconnection notice: {e} - auto-reconnecting in 0.5s...", flush=True)
            time.sleep(0.5)

    def _stream_mic(self):
        cable_dev_index = None
        if HAS_PYAUDIO_WPATCH and GLOBAL_PA:
            p = GLOBAL_PA
            for i in range(p.get_device_count()):
                try:
                    d = p.get_device_info_by_index(i)
                    if d['maxOutputChannels'] > 0 and 'cable' in d['name'].lower() and 'wasapi' in p.get_host_api_info_by_index(d['hostApi'])['name'].lower():
                        cable_dev_index = d['index']
                        print(f"[+] Found WASAPI Virtual Cable Target: '{d['name']}'", flush=True)
                        break
                except Exception:
                    pass
            if cable_dev_index is None:
                for i in range(p.get_device_count()):
                    try:
                        d = p.get_device_info_by_index(i)
                        if d['maxOutputChannels'] > 0 and ('cable' in d['name'].lower() or 'virtual' in d['name'].lower()):
                            cable_dev_index = d['index']
                            print(f"[+] Found Virtual Cable Target: '{d['name']}'", flush=True)
                            break
                    except Exception:
                        pass

        # 1. Try WebSocket microphone stream first (dedicated /ws/mic channel)
        sock = None
        try:
            sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            sock.settimeout(4)
            sock.connect((self.ip, self.port))
            sock.settimeout(None)
            ws_handshake(sock, "/ws/mic", self.ip, self.port)
            print(f"[SUCCESS] Phone Microphone stream active via WebSocket ({WS_MIC_URL})", flush=True)
            self._play_ws_mic(sock, cable_dev_index)
            return
        except Exception as ws_err:
            if sock:
                try: sock.close()
                except Exception: pass

        # 2. Fallback to HTTP audio_feed stream
        req = urllib.request.Request(AUDIO_FEED_URL)
        req.add_header("User-Agent", "DASMO-CYBER-CAPTURE-Desktop")

        with urllib.request.urlopen(req, timeout=6) as resp:
            print("[SUCCESS] Phone Microphone stream is live via HTTP -> feeding into DASMO MIC.", flush=True)
            if HAS_PYAUDIO_WPATCH and GLOBAL_PA:
                self._play_with_pyaudiowpatch(resp, cable_dev_index)
            elif HAS_SOUNDDEVICE:
                self._play_with_sounddevice(resp)

    def _play_ws_mic(self, sock, dev_index):
        if HAS_PYAUDIO_WPATCH and GLOBAL_PA:
            p = GLOBAL_PA
            stream_kwargs = {
                'format': pyaudio.paInt16,
                'channels': 1,
                'rate': SAMPLE_RATE,
                'output': True,
                'frames_per_buffer': BLOCK_SIZE
            }
            if dev_index is not None:
                stream_kwargs['output_device_index'] = dev_index
            stream = p.open(**stream_kwargs)
            try:
                while self.running:
                    chunk = recv_ws_binary(sock)
                    if not chunk:
                        break
                    stream.write(chunk)
            finally:
                try:
                    stream.stop_stream()
                    stream.close()
                except Exception:
                    pass
        elif HAS_SOUNDDEVICE:
            stream_kwargs = {
                'samplerate': SAMPLE_RATE,
                'channels': 1,
                'dtype': 'int16'
            }
            if dev_index is not None:
                stream_kwargs['device'] = dev_index
            out_stream = sd.RawOutputStream(**stream_kwargs)
            out_stream.start()
            try:
                while self.running:
                    chunk = recv_ws_binary(sock)
                    if not chunk:
                        break
                    out_stream.write(chunk)
            finally:
                try:
                    out_stream.stop()
                    out_stream.close()
                except Exception:
                    pass

    def _play_with_pyaudiowpatch(self, resp, dev_index):
        p = GLOBAL_PA
        stream_kwargs = {
            'format': pyaudio.paInt16,
            'channels': 1,
            'rate': SAMPLE_RATE,
            'output': True,
            'frames_per_buffer': BLOCK_SIZE
        }
        if dev_index is not None:
            stream_kwargs['output_device_index'] = dev_index

        stream = p.open(**stream_kwargs)
        try:
            while self.running:
                chunk = resp.read(2048)
                if not chunk:
                    break
                stream.write(chunk)
        finally:
            try:
                stream.stop_stream()
                stream.close()
            except Exception:
                pass

    def _play_with_sounddevice(self, resp):
        cable_dev = None
        try:
            for i, dev in enumerate(sd.query_devices()):
                if dev['max_output_channels'] > 0 and ('cable' in dev['name'].lower() or 'virtual' in dev['name'].lower()):
                    cable_dev = i
                    break
        except Exception:
            pass

        stream_kwargs = {
            'samplerate': SAMPLE_RATE,
            'channels': 1,
            'dtype': 'int16'
        }
        if cable_dev is not None:
            stream_kwargs['device'] = cable_dev

        out_stream = sd.RawOutputStream(**stream_kwargs)
        out_stream.start()
        try:
            while self.running:
                chunk = resp.read(2048)
                if not chunk:
                    break
                out_stream.write(chunk)
        finally:
            try:
                out_stream.stop()
                out_stream.close()
            except Exception:
                pass


speaker_relay = PcToPhoneSpeakerTransmitter(PHONE_IP, PORT)
mic_receiver = PhoneMicToPcReceiver(PHONE_IP, PORT)

print("\n[*] DASMO Audio Bridge running. Audio synchronization active.", flush=True)
print("[*] Press Ctrl+C to stop.\n", flush=True)

try:
    while True:
        time.sleep(1)
except KeyboardInterrupt:
    print("\n[*] Stopping DASMO Audio Bridge...", flush=True)
    speaker_relay.running = False
    mic_receiver.running = False
    if GLOBAL_PA:
        try:
            GLOBAL_PA.terminate()
        except Exception:
            pass
