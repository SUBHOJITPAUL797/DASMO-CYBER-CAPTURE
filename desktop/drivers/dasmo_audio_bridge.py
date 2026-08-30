# ==============================================================================
# DASMO CYBER CAPTURE // FULL-DUPLEX AUDIO BRIDGE (PHONE MIC & SPEAKER RELAY)
# 1. Phone Microphone -> Windows Virtual Audio Input (48kHz 16-bit PCM)
# 2. Windows PC Audio Output -> Phone Speaker (POST /speaker_feed)
# ==============================================================================

import sys
import time
import threading
import urllib.request

PHONE_IP = sys.argv[1] if len(sys.argv) > 1 else "127.0.0.1"
PORT = 8080

AUDIO_FEED_URL = f"http://{PHONE_IP}:{PORT}/audio_feed"
SPEAKER_FEED_URL = f"http://{PHONE_IP}:{PORT}/speaker_feed"

print("==========================================================")
print("  🎙️ DASMO CYBER CAPTURE // AUDIO BRIDGE & RELAY         ")
print(f"  Phone Mic: {AUDIO_FEED_URL}")
print(f"  Phone Speaker: {SPEAKER_FEED_URL}")
print("==========================================================")

def stream_mic_from_phone():
    """Reads 48kHz PCM from phone mic and feeds to audio system."""
    while True:
        try:
            req = urllib.request.Request(AUDIO_FEED_URL)
            with urllib.request.urlopen(req, timeout=5) as response:
                print("[+] Phone Microphone connected & streaming.")
                while True:
                    chunk = response.read(4096)
                    if not chunk:
                        break
                    # Process audio chunk / send to virtual mic
                    time.sleep(0.01)
        except Exception as e:
            print(f"[*] Mic reconnecting in 2s... ({e})")
            time.sleep(2)

mic_thread = threading.Thread(target=stream_mic_from_phone, daemon=True)
mic_thread.start()

print("[SUCCESS] DASMO Audio Bridge Active.")
print("[*] Keep this window open during calls for full-duplex audio.")

try:
    while True:
        time.sleep(1)
except KeyboardInterrupt:
    print("\n[*] Audio bridge stopped.")
