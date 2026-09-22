"""
DASMO CYBER CAPTURE - Studio AI Background Removal Engine
Uses deep human segmentation models (u2net_human_seg / silueta / isnet-general-use)
to produce studio-grade transparent PNGs for passport, KYC, and government portal live photos.
"""

import sys
import os
import io
import time
import json
import base64
from PIL import Image

try:
    import rembg
except ImportError:
    rembg = None

# Global cached sessions
CACHED_SESSIONS = {}

def get_session(model_name="u2net_human_seg"):
    if not rembg:
        raise RuntimeError("rembg is not installed in Python environment")
    
    if model_name not in CACHED_SESSIONS:
        # Try requested model, with fallback chain
        fallback_models = [model_name, "u2net_human_seg", "silueta", "u2netp", "isnet-general-use"]
        session = None
        for m in fallback_models:
            try:
                session = rembg.new_session(m)
                CACHED_SESSIONS[m] = session
                model_name = m
                break
            except Exception as e:
                sys.stderr.write(f"[bg_remove] Failed loading model {m}: {e}\n")
        
        if not session:
            # Fallback to default rembg session
            session = rembg.new_session()
            CACHED_SESSIONS["default"] = session
    
    return CACHED_SESSIONS.get(model_name) or CACHED_SESSIONS.get("default")

def remove_background_image(pil_img, model_name="u2net_human_seg"):
    """
    Takes a PIL Image (RGB or RGBA) and returns a transparent RGBA PIL Image with background removed.
    """
    session = get_session(model_name)
    # post_process_mask=True applies morphological smoothing to eliminate speckles
    result = rembg.remove(
        pil_img,
        session=session,
        post_process_mask=True,
        alpha_matting=True,
        alpha_matting_foreground_threshold=240,
        alpha_matting_background_threshold=10,
        alpha_matting_erode_size=10
    )
    return result

def process_base64(b64_str, model_name="u2net_human_seg"):
    # Strip data URL header if present
    if "," in b64_str:
        b64_str = b64_str.split(",", 1)[1]
    
    img_data = base64.b64decode(b64_str)
    pil_img = Image.open(io.BytesIO(img_data)).convert("RGB")
    
    # Process
    t0 = time.time()
    result_img = remove_background_image(pil_img, model_name)
    elapsed = time.time() - t0
    
    # Encode output to PNG base64
    buf = io.BytesIO()
    result_img.save(buf, format="PNG", optimize=True)
    out_b64 = base64.b64encode(buf.getvalue()).decode("ascii")
    
    return {
        "success": True,
        "image": f"data:image/png;base64,{out_b64}",
        "width": result_img.width,
        "height": result_img.height,
        "time_sec": round(elapsed, 3)
    }

def run_server_loop():
    """
    Persistent server loop communicating via JSON lines on stdin/stdout.
    Keeps model loaded in RAM for rapid ~1-2s inference on subsequent requests.
    """
    sys.stderr.write("[bg_remove] Studio AI Background Removal Engine started (daemon mode)\n")
    sys.stderr.flush()
    
    # Pre-warm default model
    try:
        get_session("u2net_human_seg")
        sys.stderr.write("[bg_remove] Pre-warmed u2net_human_seg model ready\n")
        sys.stderr.flush()
    except Exception as e:
        sys.stderr.write(f"[bg_remove] Pre-warm warning: {e}\n")
        sys.stderr.flush()

    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            req = json.loads(line)
            action = req.get("action", "remove_bg")
            req_id = req.get("id", 0)

            if action == "ping":
                sys.stdout.write(json.dumps({"id": req_id, "status": "ready"}) + "\n")
                sys.stdout.flush()
                continue
            
            if action == "exit":
                sys.stdout.write(json.dumps({"id": req_id, "status": "exiting"}) + "\n")
                sys.stdout.flush()
                break

            if action == "remove_bg":
                img_b64 = req.get("image", "")
                model = req.get("model", "u2net_human_seg")
                res = process_base64(img_b64, model)
                res["id"] = req_id
                sys.stdout.write(json.dumps(res) + "\n")
                sys.stdout.flush()
            else:
                sys.stdout.write(json.dumps({"id": req_id, "success": False, "error": f"Unknown action: {action}"}) + "\n")
                sys.stdout.flush()

        except Exception as err:
            sys.stderr.write(f"[bg_remove error] {err}\n")
            sys.stderr.flush()
            sys.stdout.write(json.dumps({"id": req.get("id", 0) if 'req' in locals() else 0, "success": False, "error": str(err)}) + "\n")
            sys.stdout.flush()

def main():
    if len(sys.argv) > 1:
        if sys.argv[1] == "--server":
            run_server_loop()
            return
        elif sys.argv[1] == "--test":
            print("[bg_remove] Running self-test...")
            test_img = Image.new("RGB", (320, 240), color=(200, 100, 50))
            res = remove_background_image(test_img, "u2net_human_seg")
            print(f"[bg_remove] Self-test success! Result size: {res.size}, mode: {res.mode}")
            return
        elif sys.argv[1] == "--file" and len(sys.argv) >= 4:
            in_path = sys.argv[2]
            out_path = sys.argv[3]
            model = sys.argv[4] if len(sys.argv) > 4 else "u2net_human_seg"
            img = Image.open(in_path)
            res = remove_background_image(img, model)
            res.save(out_path)
            print(f"Saved: {out_path}")
            return

    # Default to reading JSON from stdin and writing JSON to stdout
    try:
        raw_input = sys.stdin.read()
        req = json.loads(raw_input)
        img_b64 = req.get("image", "")
        model = req.get("model", "u2net_human_seg")
        res = process_base64(img_b64, model)
        sys.stdout.write(json.dumps(res))
    except Exception as e:
        sys.stdout.write(json.dumps({"success": False, "error": str(e)}))

if __name__ == "__main__":
    main()
