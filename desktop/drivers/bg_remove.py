"""
DASMO CYBER CAPTURE - Studio AI Background Removal Engine (v1.6.0)
Deep Neural Portrait Matting + Photoshop-grade Edge Defringing & Anti-Aliasing
Supports: isnet-general-use, birefnet-portrait, silueta, u2netp
"""

import sys
import os
import io
import time
import json
import base64
import numpy as np
from PIL import Image

try:
    import cv2
except ImportError:
    cv2 = None

try:
    import rembg
except ImportError:
    rembg = None

CACHED_SESSIONS = {}

def get_session(model_name="isnet-general-use"):
    if not rembg:
        raise RuntimeError("rembg is not installed in Python environment")
    
    if model_name not in CACHED_SESSIONS:
        # Priority order: isnet-general-use -> birefnet-portrait -> silueta -> default
        fallback_models = [model_name, "isnet-general-use", "birefnet-portrait", "silueta", "u2netp"]
        session = None
        for m in fallback_models:
            try:
                session = rembg.new_session(m)
                CACHED_SESSIONS[m] = session
                model_name = m
                sys.stderr.write(f"[bg_remove] Successfully loaded session for: {m}\n")
                sys.stderr.flush()
                break
            except Exception as e:
                sys.stderr.write(f"[bg_remove] Model {m} not ready: {e}\n")
                sys.stderr.flush()
        
        if not session:
            session = rembg.new_session()
            CACHED_SESSIONS["default"] = session
    
    return CACHED_SESSIONS.get(model_name) or CACHED_SESSIONS.get("default")

def refine_photoshop_matting(pil_img, model_name="isnet-general-use"):
    """
    Runs deep segmentation + Photoshop-grade edge decontamination (defringe)
    and sub-pixel alpha smoothing.
    """
    session = get_session(model_name)
    
    # 1. Deep AI segmentation
    raw_rgba = rembg.remove(
        pil_img,
        session=session,
        post_process_mask=True
    )

    if cv2 is None:
        return raw_rgba

    # 2. Convert to NumPy for edge defringing & feathering
    img_np = np.array(raw_rgba)
    if img_np.ndim != 3 or img_np.shape[2] != 4:
        return raw_rgba

    rgb = img_np[:, :, :3]
    alpha = img_np[:, :, 3]

    # Anti-alias alpha channel with 3x3 Gaussian smoothing
    alpha_float = alpha.astype(np.float32) / 255.0
    alpha_smooth = cv2.GaussianBlur(alpha_float, (3, 3), 0)
    alpha_smooth = np.clip(alpha_smooth, 0.0, 1.0)

    # 3. Photoshop Defringe: inpaint/neutralize color spill on semi-transparent borders
    # (Removes room/chair color cast on hair strands and shirt shoulders)
    fringe_mask = (alpha > 8) & (alpha < 235)
    if np.any(fringe_mask):
        inpaint_mask = ((alpha <= 220) & (alpha > 0)).astype(np.uint8)
        clean_rgb = cv2.inpaint(rgb, inpaint_mask, 3, cv2.INPAINT_TELEA)
    else:
        clean_rgb = rgb

    out_rgba = np.dstack((clean_rgb, (alpha_smooth * 255).astype(np.uint8)))
    return Image.fromarray(out_rgba)

def process_base64(b64_str, model_name="isnet-general-use", target_color=None):
    if "," in b64_str:
        b64_str = b64_str.split(",", 1)[1]
    
    img_data = base64.b64decode(b64_str)
    pil_img = Image.open(io.BytesIO(img_data)).convert("RGB")
    
    t0 = time.time()
    result_rgba = refine_photoshop_matting(pil_img, model_name)
    elapsed = time.time() - t0
    
    # Encode transparent PNG
    buf_png = io.BytesIO()
    result_rgba.save(buf_png, format="PNG", optimize=True)
    out_b64 = base64.b64encode(buf_png.getvalue()).decode("ascii")

    # If target background color requested, also generate composite
    composite_b64 = None
    if target_color and len(target_color) == 3:
        bg = Image.new("RGB", result_rgba.size, tuple(target_color))
        bg.paste(result_rgba, mask=result_rgba.split()[3])
        buf_comp = io.BytesIO()
        bg.save(buf_comp, format="JPEG", quality=96)
        composite_b64 = f"data:image/jpeg;base64,{base64.b64encode(buf_comp.getvalue()).decode('ascii')}"

    return {
        "success": True,
        "image": f"data:image/png;base64,{out_b64}",
        "composite": composite_b64,
        "width": result_rgba.width,
        "height": result_rgba.height,
        "time_sec": round(elapsed, 3),
        "model": model_name
    }

def run_server_loop():
    sys.stderr.write("[bg_remove] Studio AI Background Removal Engine ready (daemon mode)\n")
    sys.stderr.flush()
    
    # Pre-warm isnet-general-use session
    try:
        get_session("isnet-general-use")
        sys.stderr.write("[bg_remove] Pre-warmed isnet-general-use model ready\n")
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
                model = req.get("model", "isnet-general-use")
                color = req.get("color", None)
                res = process_base64(img_b64, model, color)
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
            test_img = Image.new("RGB", (320, 240), color=(180, 80, 40))
            res = refine_photoshop_matting(test_img, "isnet-general-use")
            print(f"[bg_remove] Self-test success! Result size: {res.size}, mode: {res.mode}")
            return
        elif sys.argv[1] == "--file" and len(sys.argv) >= 4:
            in_path = sys.argv[2]
            out_path = sys.argv[3]
            model = sys.argv[4] if len(sys.argv) > 4 else "isnet-general-use"
            img = Image.open(in_path)
            res = refine_photoshop_matting(img, model)
            res.save(out_path)
            print(f"Saved: {out_path}")
            return

    try:
        raw_input = sys.stdin.read()
        req = json.loads(raw_input)
        img_b64 = req.get("image", "")
        model = req.get("model", "isnet-general-use")
        color = req.get("color", None)
        res = process_base64(img_b64, model, color)
        sys.stdout.write(json.dumps(res))
    except Exception as e:
        sys.stdout.write(json.dumps({"success": False, "error": str(e)}))

if __name__ == "__main__":
    main()
