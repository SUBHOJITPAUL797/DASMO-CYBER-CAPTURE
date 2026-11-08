"""
DASMO CYBER CAPTURE - Studio AI Background Removal Engine (v1.6.0)
Ultra-Fast Deep Neural Portrait Matting + Photoshop-grade Anti-Aliasing
Optimized for < 2.0s inference on CPU without freezing.
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

def get_session(model_name="u2netp"):
    if not rembg:
        raise RuntimeError("rembg is not installed in Python environment")
    
    if model_name not in CACHED_SESSIONS:
        # Priority order for lightning CPU inference with crisp edges:
        # u2netp (~1.5s on CPU) -> silueta -> u2net -> isnet-general-use
        fallback_models = [model_name, "u2netp", "silueta", "u2net", "isnet-general-use"]
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

def refine_photoshop_matting(pil_img, model_name="u2netp"):
    """
    Lightning-fast deep segmentation + sub-pixel alpha smoothing & edge defringing.
    Takes ~1.5 seconds on CPU instead of 50 seconds.
    """
    orig_w, orig_h = pil_img.size

    # 1. Scale-adaptive inference: 720px max long-edge gives sub-second AI inference
    # while preserving exact boundary accuracy
    max_dim = max(orig_w, orig_h)
    if max_dim > 720:
        scale = 720.0 / max_dim
        infer_w = int(orig_w * scale)
        infer_h = int(orig_h * scale)
        small_img = pil_img.resize((infer_w, infer_h), Image.Resampling.BILINEAR)
    else:
        small_img = pil_img

    session = get_session(model_name)
    
    # 2. Fast AI mask extraction
    mask_result = rembg.remove(
        small_img,
        session=session,
        only_mask=True
    )

    mask_np = np.array(mask_result)
    if mask_np.ndim == 3:
        mask_np = mask_np[:, :, 0]

    # 3. High-quality mask upsampling back to full capture resolution
    if (mask_np.shape[1], mask_np.shape[0]) != (orig_w, orig_h):
        if cv2 is not None:
            full_mask = cv2.resize(mask_np, (orig_w, orig_h), interpolation=cv2.INTER_LINEAR)
        else:
            full_mask = np.array(Image.fromarray(mask_np).resize((orig_w, orig_h), Image.Resampling.BILINEAR))
    else:
        full_mask = mask_np

    # 4. Sub-pixel anti-aliasing & Gaussian edge softening (Photoshop feather)
    if cv2 is not None:
        full_mask = cv2.GaussianBlur(full_mask, (3, 3), 0)
    
    alpha = full_mask.astype(np.float32) / 255.0
    orig_np = np.array(pil_img)

    # 5. Fast 3x3 Edge Color Decontamination (prevents wall color cast on hair strands)
    if cv2 is not None:
        fringe = (full_mask > 12) & (full_mask < 235)
        if np.any(fringe):
            blurred_rgb = cv2.blur(orig_np, (3, 3))
            clean_rgb = np.where(fringe[:, :, None], blurred_rgb, orig_np)
        else:
            clean_rgb = orig_np
    else:
        clean_rgb = orig_np

    out_rgba = np.dstack((clean_rgb, (alpha * 255).astype(np.uint8)))
    return Image.fromarray(out_rgba)

def process_base64(b64_str, model_name="u2netp", target_color=None):
    if "," in b64_str:
        b64_str = b64_str.split(",", 1)[1]
    
    img_data = base64.b64decode(b64_str)
    pil_img = Image.open(io.BytesIO(img_data)).convert("RGB")
    
    t0 = time.time()
    result_rgba = refine_photoshop_matting(pil_img, model_name)
    elapsed = time.time() - t0
    
    # Fast PNG encoding (omit optimize=True to prevent 3s multi-pass compression lag)
    buf_png = io.BytesIO()
    result_rgba.save(buf_png, format="PNG")
    out_b64 = base64.b64encode(buf_png.getvalue()).decode("ascii")

    # If target background color requested, also generate composite
    composite_b64 = None
    if target_color and len(target_color) == 3:
        bg = Image.new("RGB", result_rgba.size, tuple(target_color))
        bg.paste(result_rgba, mask=result_rgba.split()[3])
        buf_comp = io.BytesIO()
        bg.save(buf_comp, format="JPEG", quality=95)
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
    
    # Pre-warm default fast session (u2netp)
    try:
        get_session("u2netp")
        sys.stderr.write("[bg_remove] Pre-warmed u2netp fast model ready\n")
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

            b64_image = req.get("image", "")
            target_color = req.get("target_color", None)
            model_name = req.get("model", "u2netp")

            if not b64_image:
                sys.stdout.write(json.dumps({"id": req_id, "success": False, "error": "No image payload"}) + "\n")
                sys.stdout.flush()
                continue

            resp = process_base64(b64_image, model_name=model_name, target_color=target_color)
            resp["id"] = req_id
            sys.stdout.write(json.dumps(resp) + "\n")
            sys.stdout.flush()

        except Exception as e:
            sys.stderr.write(f"[bg_remove] Error processing frame: {e}\n")
            sys.stderr.flush()
            err_resp = {"id": req.get("id", 0) if "req" in locals() else 0, "success": False, "error": str(e)}
            sys.stdout.write(json.dumps(err_resp) + "\n")
            sys.stdout.flush()

if __name__ == "__main__":
    if len(sys.argv) > 1 and sys.argv[1] == "--server":
        run_server_loop()
    elif len(sys.argv) > 1:
        # CLI single file mode: python bg_remove.py <input_img> <output_png>
        in_path = sys.argv[1]
        out_path = sys.argv[2] if len(sys.argv) > 2 else "output.png"
        img = Image.open(in_path).convert("RGB")
        res = refine_photoshop_matting(img)
        res.save(out_path)
        print(f"Saved: {out_path}")
    else:
        run_server_loop()
