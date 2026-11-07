"""Jarvis voice server for the Oracle box: the other end of the app's Jarvis tab.

  POST /voice   multipart: file (OGG/Opus recording), worker; header Authorization: Bearer <token>
                -> {"transcript", "reply", "audio_url"}
  GET  /audio/<id>.ogg   the spoken reply, same token
  GET  /done    finished background jobs: [{"id", "title", "detail"}]

Each request: speech to text (faster-whisper) -> `claude -p` in the worker's folder -> text to speech (Piper)
-> OGG/Opus (ffmpeg). Workers marked "background" (Research by default) answer "on it" straight away and
report on /done when Claude has finished.

Listens on 127.0.0.1 only. Put it on your tailnet with HTTPS:  tailscale serve --bg 8765
Setup: see README.md next to this file.
"""
import argparse
import json
import os
import re
import shutil
import subprocess
import tempfile
import threading
import time
import uuid
from email.parser import BytesParser
from email.policy import HTTP
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

HOME = Path.home()

# Each worker runs Claude in its own folder with its own instructions. Override any of this with --workers.
DEFAULT_WORKERS = {
    "jarvis": {"cwd": str(HOME / "jarvis"), "prompt": "You are Jarvis, Tom's general assistant."},
    "coding": {"cwd": str(HOME / "code"), "prompt": "You help Tom with code in this folder."},
    "obsidian": {"cwd": str(HOME / "vault"), "prompt": "This folder is Tom's Obsidian vault. Add or find tasks and notes in it."},
    "research": {"cwd": str(HOME / "research"), "prompt": "Research what Tom asks and write up what you find.", "background": True},
    "odd-jobs": {"cwd": str(HOME / "jarvis"), "prompt": "Help Tom with whatever he asks."},
}

SPOKEN = (
    " Your reply is read aloud on Tom's phone: answer in two or three short spoken sentences, UK English,"
    " no markdown, lists, code or links."
)

CONFIG = {}  # filled in by main()
AUDIO_DIR = Path(tempfile.gettempdir()) / "jarvis-audio"
done = []  # finished background jobs, newest last
lock = threading.Lock()
_whisper = None


# ---------- the three steps; tests swap these out ----------

def transcribe(ogg: Path) -> str:
    """Speech to text with faster-whisper (model loaded once, on first use)."""
    global _whisper
    if _whisper is None:
        from faster_whisper import WhisperModel
        _whisper = WhisperModel(CONFIG["whisper_model"], device="cpu", compute_type="int8")
    segments, _ = _whisper.transcribe(str(ogg), language="en", vad_filter=True)
    return " ".join(s.text.strip() for s in segments).strip()


def ask_claude(worker: str, text: str, spoken: bool = True) -> str:
    """Runs the Claude CLI once, in the worker's folder."""
    w = CONFIG["workers"][worker]
    cwd = Path(w["cwd"]).expanduser()
    cwd.mkdir(parents=True, exist_ok=True)
    cmd = [CONFIG["claude"], "-p", text, "--output-format", "text",
           "--append-system-prompt", w.get("prompt", "") + (SPOKEN if spoken else "")]
    if w.get("continue", CONFIG["continue"]):
        cmd.insert(1, "--continue")
    if w.get("permission_mode"):
        cmd += ["--permission-mode", w["permission_mode"]]
    if w.get("allowed_tools"):
        cmd += ["--allowedTools", *w["allowed_tools"]]
    r = subprocess.run(cmd, cwd=cwd, capture_output=True, text=True, timeout=CONFIG["claude_timeout"])
    out = r.stdout.strip()
    if r.returncode != 0 and not out:
        raise RuntimeError(f"claude exited {r.returncode}: {r.stderr.strip()[:300]}")
    return out


def speak(text: str, ogg: Path) -> bool:
    """Text to speech with Piper, then OGG/Opus with ffmpeg. False when Piper isn't set up (text-only reply)."""
    if not CONFIG.get("piper_voice") or not shutil.which(CONFIG["piper"]) or not shutil.which("ffmpeg"):
        return False
    wav = ogg.with_suffix(".wav")
    clean = re.sub(r"[*_`#>\[\]]", "", text)
    subprocess.run([CONFIG["piper"], "--model", CONFIG["piper_voice"], "--output_file", str(wav)],
                   input=clean, text=True, check=True, capture_output=True, timeout=120)
    subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", str(wav), "-c:a", "libopus", "-b:a", "32k", str(ogg)],
                   check=True, timeout=120)
    wav.unlink(missing_ok=True)
    return True


# ---------- HTTP ----------

def multipart(body: bytes, content_type: str) -> dict:
    msg = BytesParser(policy=HTTP).parsebytes(b"Content-Type: " + content_type.encode() + b"\r\n\r\n" + body)
    return {p.get_param("name", header="content-disposition"): p.get_payload(decode=True) for p in msg.iter_parts()}


def tidy_audio():
    """Drops audio older than a day."""
    cutoff = time.time() - 86400
    for f in AUDIO_DIR.glob("*"):
        if f.stat().st_mtime < cutoff:
            f.unlink(missing_ok=True)


def run_background(job_id: str, worker: str, text: str):
    try:
        result = ask_claude(worker, text, spoken=False)
        title = f"{worker.capitalize()} finished"
    except Exception as e:  # noqa: BLE001 - report any failure on the Done line
        result, title = f"It failed: {e}", f"{worker.capitalize()} failed"
    out = Path(CONFIG["workers"][worker]["cwd"]).expanduser() / "jarvis-results" / f"{time.strftime('%Y-%m-%d %H%M')} {job_id}.md"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(f"# {text}\n\n{result}\n", encoding="utf-8")
    with lock:
        done.append({"id": job_id, "title": title, "detail": result[:1500]})
        del done[:-20]
    print(f"[{job_id}] background {worker} done -> {out}", flush=True)


class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        print(f"{self.address_string()} {fmt % args}", flush=True)

    def send_json(self, code: int, obj):
        out = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(out)))
        self.end_headers()
        self.wfile.write(out)

    def authorised(self) -> bool:
        if self.headers.get("Authorization") == "Bearer " + CONFIG["token"]:
            return True
        self.send_json(401, {"error": "bad token"})
        return False

    def do_GET(self):
        if not self.authorised():
            return
        if self.path == "/done":
            with lock:
                self.send_json(200, list(done))
            return
        m = re.fullmatch(r"/audio/([a-f0-9]{12})\.ogg", self.path)
        if m and (AUDIO_DIR / f"{m.group(1)}.ogg").exists():
            data = (AUDIO_DIR / f"{m.group(1)}.ogg").read_bytes()
            self.send_response(200)
            self.send_header("Content-Type", "audio/ogg")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)
            return
        self.send_json(405 if self.path == "/voice" else 404, {"error": "POST /voice"})

    def do_POST(self):
        if not self.authorised():
            return
        if self.path != "/voice":
            self.send_json(404, {"error": "not found"})
            return
        length = int(self.headers.get("Content-Length", "0"))
        if length > 25 * 1024 * 1024:
            self.send_json(413, {"error": "recording too big"})
            return
        parts = multipart(self.rfile.read(length), self.headers.get("Content-Type", ""))
        audio = parts.get("file") or parts.get("audio") or b""
        worker = (parts.get("worker") or b"jarvis").decode().strip()
        if not audio:
            self.send_json(400, {"error": "no file part"})
            return
        if worker not in CONFIG["workers"]:
            worker = "jarvis"

        rid = uuid.uuid4().hex[:12]
        AUDIO_DIR.mkdir(parents=True, exist_ok=True)
        tidy_audio()
        rec = AUDIO_DIR / f"in-{rid}.ogg"
        rec.write_bytes(audio)
        try:
            heard = transcribe(rec)
        except Exception as e:  # noqa: BLE001
            self.send_json(500, {"error": f"speech to text failed: {e}"})
            return
        finally:
            rec.unlink(missing_ok=True)
        print(f"[{rid}] {worker}: {heard!r}", flush=True)
        if not heard:
            self.send_json(200, {"transcript": "", "reply": "Sorry, I didn't catch that.", "audio_url": None})
            return

        if CONFIG["workers"][worker].get("background"):
            threading.Thread(target=run_background, args=(rid, worker, heard), daemon=True).start()
            reply = "On it. I'll let you know when it's done."
        else:
            try:
                reply = ask_claude(worker, heard)
            except subprocess.TimeoutExpired:
                reply = "That took too long, so I stopped. Try asking Research instead, it works in the background."
            except Exception as e:  # noqa: BLE001
                self.send_json(502, {"error": str(e)})
                return

        audio_url = None
        try:
            if speak(reply, AUDIO_DIR / f"{rid}.ogg"):
                audio_url = f"/audio/{rid}.ogg"
        except Exception as e:  # noqa: BLE001 - a text reply is still useful
            print(f"[{rid}] speech failed: {e}", flush=True)
        self.send_json(200, {"transcript": heard, "reply": reply, "audio_url": audio_url})


def main():
    ap = argparse.ArgumentParser(description="Jarvis voice server")
    ap.add_argument("--port", type=int, default=int(os.environ.get("JARVIS_PORT", "8765")))
    ap.add_argument("--workers", help="JSON file that replaces the default workers (see README)")
    ap.add_argument("--whisper-model", default=os.environ.get("JARVIS_WHISPER", "base.en"))
    ap.add_argument("--piper-voice", default=os.environ.get("JARVIS_PIPER_VOICE", ""), help="path to a Piper .onnx voice")
    ap.add_argument("--piper", default=os.environ.get("JARVIS_PIPER", "piper"))
    ap.add_argument("--claude", default=os.environ.get("JARVIS_CLAUDE", "claude"))
    ap.add_argument("--claude-timeout", type=int, default=int(os.environ.get("JARVIS_CLAUDE_TIMEOUT", "120")))
    ap.add_argument("--continue", dest="cont", action="store_true", help="each worker carries on its last conversation")
    a = ap.parse_args()
    token = os.environ.get("JARVIS_TOKEN", "")
    if len(token) < 20:
        raise SystemExit("Set JARVIS_TOKEN to a long random string (at least 20 characters), e.g. openssl rand -hex 24")
    workers = json.loads(Path(a.workers).read_text()) if a.workers else DEFAULT_WORKERS
    CONFIG.update(token=token, workers=workers, whisper_model=a.whisper_model, piper_voice=a.piper_voice,
                  piper=a.piper, claude=a.claude, claude_timeout=a.claude_timeout, **{"continue": a.cont})
    print(f"Jarvis on http://127.0.0.1:{a.port} · workers: {', '.join(workers)}"
          f" · speech: {'Piper' if a.piper_voice else 'off (text only)'}", flush=True)
    ThreadingHTTPServer(("127.0.0.1", a.port), Handler).serve_forever()


if __name__ == "__main__":
    main()
