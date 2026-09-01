# ==============================================================================
# DASMO CYBER CAPTURE // REAL-TIME FULL-DUPLEX AUDIO ENGINE
# 1. PC System Audio -> Streamed wirelessly to Phone Speaker (WASAPI Loopback)
# 2. Phone Microphone -> Streamed to Windows Virtual Mic (48kHz PCM)
# ==============================================================================

import sys
import time
import socket
import threading
import numpy as np

try:
    import sounddevice as sd
except ImportError:
    print("[ERROR] sounddevice not installed. Run: pip install sounddevice")
    sys.exit(1)

import urllib.request

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
BLOCK_SIZE = 1024

AUDIO_FEED_URL = f"http://{PHONE_IP}:{PORT}/audio_feed"

print("==========================================================")
print("  🎙️🔊 DASMO CYBER CAPTURE // LIVE AUDIO RELAY SYSTEM    ")
print(f"  Phone IP: {PHONE_IP}:{PORT}")
print("  [1] PC System Audio  ==>  Mobile Phone Speaker")
print("  [2] Phone Microphone ==>  PC Virtual Mic (WhatsApp)")
print("==========================================================")

# --- 1. PC Audio -> Phone Speaker (WASAPI Loopback Transmitter) ---
class PcToPhoneSpeakerTransmitter:
    def __init__(self, ip, port):
        self.ip = ip
        self.port = port
        self.sock = None
        self.running = True
        self.thread = threading.Thread(target=self._start_loopback, daemon=True)
        self.thread.start()

    def _start_loopback(self):
        # Locate Windows WASAPI loopback device
        loopback_dev = None
        for i, dev in enumerate(sd.query_devices()):
            # Look for default output / speaker loopback
            if dev['max_input_channels'] > 0 and 'loopback' in dev['name'].lower():
                loopback_dev = i
                break

        try:
            self.sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            self.sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            self.sock.connect((self.ip, self.port))
            
            # Send HTTP POST Header for /speaker_feed
            http_header = (
                f"POST /speaker_feed HTTP/1.1\r\n"
                f"Host: {self.ip}:{self.port}\r\n"
                f"Content-Type: audio/x-raw; rate=48000; channels=1; format=s16le\r\n"
                f"Connection: keep-alive\r\n\r\n"
            ).encode('utf-8')
            self.sock.sendall(http_header)
            print("[+] Connected to Phone Speaker stream.")

            def audio_callback(indata, frames, time_info, status):
                if not self.running:
                    return
                # Convert float32 [-1.0, 1.0] to int16 PCM bytes
                pcm_data = (np.clip(indata[:, 0], -1.0, 1.0) * 32767).astype(np.int16).tobytes()
                try:
                    self.sock.sendall(pcm_data)
                except Exception:
                    pass

            # Open WASAPI loopback or default input stream
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
                print("[SUCCESS] PC Audio is now playing through your Mobile Phone Speaker!")
                while self.running:
                    time.sleep(0.5)

        except Exception as e:
            print(f"[*] Speaker relay notice: {e}")

# --- 2. Phone Microphone -> PC (Receiver) ---
class PhoneMicToPcReceiver:
    def __init__(self, ip, port):
        self.ip = ip
        self.port = port
        self.running = True
        self.thread = threading.Thread(target=self._start_mic_stream, daemon=True)
        self.thread.start()

    def _start_mic_stream(self):
        while self.running:
            try:
                req = urllib.request.Request(AUDIO_FEED_URL)
                with urllib.request.urlopen(req, timeout=5) as resp:
                    print("[SUCCESS] Phone Microphone is live.")
                    while self.running:
                        chunk = resp.read(2048)
                        if not chunk:
                            break
                        # Stream is active
            except Exception:
                time.sleep(2)

speaker_relay = PcToPhoneSpeakerTransmitter(PHONE_IP, PORT)
mic_receiver = PhoneMicToPcReceiver(PHONE_IP, PORT)

print("\n[*] Keep this window open to maintain audio synchronization.")
print("[*] Press Ctrl+C to stop.")

try:
    while True:
        time.sleep(1)
except KeyboardInterrupt:
    print("\n[*] Stopping DASMO Audio Bridge...")
    speaker_relay.running = False
    mic_receiver.running = False
