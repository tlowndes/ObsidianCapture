"""Jarvis voice server for the Oracle box: the other end of the app's Jarvis tab.

  POST /voice   multipart: file (OGG/Opus recording), worker; header Authorization: Bearer <token>
                -> {"transcript", "reply", "audio_url", "sorted"?}
  POST /text    JSON {"text", "worker", "voice"?}: the same, typed instead of spoken
  Both take an optional "chat" id (a UUID from the app). Each chat is one Claude session: the first message starts
  it with --session-id, later ones --resume it, so Claude remembers the conversation until the app starts a new chat.
  GET  /audio/<id>.ogg   the spoken reply, same token
  GET  /done    finished background jobs: [{"id", "title", "detail"}]
  GET  /voices  the Piper voices in --voices-dir: [{"id", "label", "detail", "sample_url"}]
  GET  /voices/<id>/sample.ogg   a short sample of that voice
  The optional multipart field "voice" picks one of them for the reply; without it, --piper-voice is used.

Each request: speech to text (faster-whisper) -> `claude -p` in the worker's folder -> text to speech (Piper)
-> OGG/Opus (ffmpeg). Workers marked "background" (Research by default) answer "on it" straight away and
report on /done when Claude has finished.

A ramble (--ramble-words or more, 40 by default) is first offered to Claude as something to sort: if it is a
ramble rather than a question, the answer carries "sorted": {"tasks", "ideas", "note"}, where note is a short
line or {"title", "markdown"} (the ramble tidied into one note). The app shows Check and save and writes it to
the vault itself, so nothing here touches the vault for a ramble.

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

SORT_PROMPT = """You sort a spoken or typed ramble for Tom's Obsidian vault. Today is {today}.
First decide: is this a question or a request for you to do something now? Then answer only {{"ramble": false}}.
Otherwise it is a ramble to keep. Answer only one JSON object, no code fences:
{{"ramble": true,
  "reply": "one or two short spoken sentences saying what you found, UK English, no markdown",
  "tasks": [{{"text": "short imperative, no date words", "tag": "#home, #work, #jobs, #learning, #bill or null",
             "due": "YYYY-MM-DD or null, only when a day is said or implied"}}],
  "ideas": ["project ideas or things to explore later, as short titles"],
  "note": null, or one or two plain sentences for a short leftover, or for a longer ramble:
          {{"title": "a short title", "markdown": "the ramble as one cohesive note"}}}}
For the note: start with a one or two sentence summary, then ## headings that group the points, with short
bullets under each. Keep Tom's points, order and wording where you can; fix the rambling, not the meaning.
Don't invent anything, don't add advice, no top-level # heading (the title is the file name).
Leave tasks out of the note when they are already in "tasks". Use null and [] when there is nothing.

The ramble:
{text}"""

CONFIG = {}  # filled in by main()
AUDIO_DIR = Path(tempfile.gettempdir()) / "jarvis-audio"
done = []  # finished background jobs, newest last
lock = threading.Lock()
_whisper = None
CHATS_FILE = Path.home() / ".config" / "jarvis-chats.json"
UUID_RE = re.compile(r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")


def known_chats() -> set:
    try:
        return set(json.loads(CHATS_FILE.read_text()))
    except (OSError, ValueError):
        return set()


def remember_chat(chat: str):
    with lock:
        chats = known_chats()
        chats.add(chat)
        CHATS_FILE.parent.mkdir(parents=True, exist_ok=True)
        CHATS_FILE.write_text(json.dumps(sorted(chats)[-500:]))


# ---------- the three steps; tests swap these out ----------

def transcribe(ogg: Path) -> str:
    """Speech to text with faster-whisper (model loaded once, on first use)."""
    global _whisper
    if _whisper is None:
        from faster_whisper import WhisperModel
        _whisper = WhisperModel(CONFIG["whisper_model"], device="cpu", compute_type="int8")
    segments, _ = _whisper.transcribe(str(ogg), language="en", vad_filter=True)
    return " ".join(s.text.strip() for s in segments).strip()


def ask_claude(worker: str, text: str, spoken: bool = True, chat: str = "") -> str:
    """Runs the Claude CLI once, in the worker's folder; in [chat]'s session when there is one."""
    w = CONFIG["workers"][worker]
    cwd = Path(w["cwd"]).expanduser()
    cwd.mkdir(parents=True, exist_ok=True)
    cmd = [CONFIG["claude"], "-p", text, "--output-format", "text",
           "--append-system-prompt", w.get("prompt", "") + (SPOKEN if spoken else "")]
    chat = chat.lower() if UUID_RE.match(chat or "") else ""
    if chat:
        cmd += ["--resume", chat] if chat in known_chats() else ["--session-id", chat]
    elif w.get("continue", CONFIG["continue"]):
        cmd.insert(1, "--continue")
    if w.get("permission_mode"):
        cmd += ["--permission-mode", w["permission_mode"]]
    if w.get("allowed_tools"):
        cmd += ["--allowedTools", *w["allowed_tools"]]
    r = subprocess.run(cmd, cwd=cwd, capture_output=True, text=True, timeout=CONFIG["claude_timeout"])
    out = r.stdout.strip()
    if r.returncode != 0 and not out:
        raise RuntimeError(f"claude exited {r.returncode}: {r.stderr.strip()[:300]}")
    if chat:
        remember_chat(chat)
    return out


def voice_files() -> dict:
    """Voice id -> .onnx path, from --voices-dir (and --piper-voice)."""
    found = {}
    d = Path(CONFIG.get("voices_dir") or "").expanduser()
    if d.is_dir():
        for f in sorted(d.glob("*.onnx")):
            found[f.stem] = f
    if CONFIG.get("piper_voice"):
        f = Path(CONFIG["piper_voice"]).expanduser()
        found.setdefault(f.stem, f)
    return found


def voice_info(vid: str) -> dict:
    """en_GB-alan-medium -> Alan, "UK · medium"."""
    parts = vid.split("-")
    name = parts[1].split("_")[0].capitalize() if len(parts) > 1 else vid
    region = {"en_GB": "UK", "en_US": "US"}.get(parts[0], parts[0])
    if len(parts) > 1 and "northern_english" in parts[1]:
        name = "Northern English"
    elif len(parts) > 1 and "southern_english" in parts[1]:
        name = "Southern English"
    detail = " · ".join(p for p in [region, parts[2] if len(parts) > 2 else ""] if p)
    return {"id": vid, "label": name, "detail": detail, "sample_url": f"/voices/{vid}/sample.ogg"}


def speak(text: str, ogg: Path, voice: str = "") -> bool:
    """Text to speech with Piper, then OGG/Opus with ffmpeg. False when Piper isn't set up (text-only reply)."""
    model = voice_files().get(voice) or (Path(CONFIG["piper_voice"]) if CONFIG.get("piper_voice") else None)
    if not model or not shutil.which(CONFIG["piper"]) or not shutil.which("ffmpeg"):
        return False
    wav = ogg.with_suffix(".wav")
    clean = re.sub(r"[*_`#>\[\]]", "", text)
    subprocess.run([CONFIG["piper"], "--model", str(model), "--output_file", str(wav)],
                   input=clean, text=True, check=True, capture_output=True, timeout=120)
    subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", str(wav), "-c:a", "libopus", "-b:a", "32k", str(ogg)],
                   check=True, timeout=120)
    wav.unlink(missing_ok=True)
    return True


def sort_ramble(text: str):
    """Asks Claude to sort a ramble. Returns (reply, sorted) or None when it was a question or request."""
    cwd = Path(CONFIG["workers"]["obsidian" if "obsidian" in CONFIG["workers"] else "jarvis"]["cwd"]).expanduser()
    cwd.mkdir(parents=True, exist_ok=True)
    prompt = SORT_PROMPT.format(today=time.strftime("%A %Y-%m-%d"), text=text)
    r = subprocess.run([CONFIG["claude"], "-p", prompt, "--output-format", "text"],
                       cwd=cwd, capture_output=True, text=True, timeout=CONFIG["claude_timeout"])
    return parse_sorted(r.stdout)


def parse_sorted(out: str):
    """Claude's sort answer -> (reply, sorted), or None for "not a ramble" or an answer that isn't JSON."""
    m = re.search(r"\{.*\}", out or "", re.S)
    if not m:
        return None
    try:
        o = json.loads(m.group(0))
    except ValueError:
        return None
    if not o.get("ramble"):
        return None
    note = o.get("note")
    if isinstance(note, dict) and not str(note.get("markdown") or "").strip():
        note = None
    sorted_ = {"tasks": o.get("tasks") or [], "ideas": o.get("ideas") or [], "note": note or None}
    if not (sorted_["tasks"] or sorted_["ideas"] or sorted_["note"]):
        return None
    return (o.get("reply") or "Got it. Check it before I save.").strip(), sorted_


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

    def send_ogg(self, data: bytes):
        self.send_response(200)
        self.send_header("Content-Type", "audio/ogg")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

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
        if self.path == "/voices":
            self.send_json(200, [voice_info(v) for v in voice_files()])
            return
        m = re.fullmatch(r"/voices/([A-Za-z0-9_.-]+)/sample\.ogg", self.path)
        if m and m.group(1) in voice_files():
            out = AUDIO_DIR / f"sample-{m.group(1)}.ogg"
            AUDIO_DIR.mkdir(parents=True, exist_ok=True)
            if not out.exists():
                try:
                    speak("Hello Tom, I'm Jarvis. This is how I sound.", out, m.group(1))
                except Exception as e:  # noqa: BLE001
                    self.send_json(500, {"error": f"sample failed: {e}"})
                    return
            if out.exists():
                self.send_ogg(out.read_bytes())
            else:
                self.send_json(404, {"error": "speech is off"})
            return
        m = re.fullmatch(r"/audio/([a-f0-9]{12})\.ogg", self.path)
        if m and (AUDIO_DIR / f"{m.group(1)}.ogg").exists():
            self.send_ogg((AUDIO_DIR / f"{m.group(1)}.ogg").read_bytes())
            return
        self.send_json(405 if self.path == "/voice" else 404, {"error": "POST /voice"})

    def do_POST(self):
        if not self.authorised():
            return
        if self.path not in ("/voice", "/text"):
            self.send_json(404, {"error": "not found"})
            return
        length = int(self.headers.get("Content-Length", "0"))
        if length > 25 * 1024 * 1024:
            self.send_json(413, {"error": "too big"})
            return
        body = self.rfile.read(length)
        rid = uuid.uuid4().hex[:12]
        AUDIO_DIR.mkdir(parents=True, exist_ok=True)
        tidy_audio()

        if self.path == "/text":
            try:
                j = json.loads(body or b"{}")
            except ValueError:
                self.send_json(400, {"error": "send JSON: text, worker, voice"})
                return
            heard = str(j.get("text") or "").strip()
            worker = str(j.get("worker") or "jarvis").strip()
            voice = str(j.get("voice") or "").strip()
            chat = str(j.get("chat") or "").strip()
            if not heard:
                self.send_json(400, {"error": "no text"})
                return
        else:
            parts = multipart(body, self.headers.get("Content-Type", ""))
            audio = parts.get("file") or parts.get("audio") or b""
            worker = (parts.get("worker") or b"jarvis").decode().strip()
            voice = (parts.get("voice") or b"").decode().strip()
            chat = (parts.get("chat") or b"").decode().strip()
            if not audio:
                self.send_json(400, {"error": "no file part"})
                return
            rec = AUDIO_DIR / f"in-{rid}.ogg"
            rec.write_bytes(audio)
            try:
                heard = transcribe(rec)
            except Exception as e:  # noqa: BLE001
                self.send_json(500, {"error": f"speech to text failed: {e}"})
                return
            finally:
                rec.unlink(missing_ok=True)
        if worker not in CONFIG["workers"]:
            worker = "jarvis"
        print(f"[{rid}] {worker}: {heard!r}", flush=True)
        if not heard:
            self.send_json(200, {"transcript": "", "reply": "Sorry, I didn't catch that.", "audio_url": None})
            return

        sorted_ = None
        reply = None
        if len(heard.split()) >= CONFIG["ramble_words"] and not CONFIG["workers"][worker].get("background"):
            try:
                found = sort_ramble(heard)
            except Exception as e:  # noqa: BLE001 - fall back to a normal answer
                print(f"[{rid}] sorting failed: {e}", flush=True)
                found = None
            if found:
                reply, sorted_ = found

        if reply is not None:
            pass
        elif CONFIG["workers"][worker].get("background"):
            threading.Thread(target=run_background, args=(rid, worker, heard), daemon=True).start()
            reply = "On it. I'll let you know when it's done."
        else:
            try:
                reply = ask_claude(worker, heard, chat=chat)
            except subprocess.TimeoutExpired:
                reply = "That took too long, so I stopped. Try asking Research instead, it works in the background."
            except Exception as e:  # noqa: BLE001
                self.send_json(502, {"error": str(e)})
                return

        audio_url = None
        try:
            if speak(reply, AUDIO_DIR / f"{rid}.ogg", voice):
                audio_url = f"/audio/{rid}.ogg"
        except Exception as e:  # noqa: BLE001 - a text reply is still useful
            print(f"[{rid}] speech failed: {e}", flush=True)
        out = {"transcript": heard, "reply": reply, "audio_url": audio_url}
        if sorted_:
            out["sorted"] = sorted_
        self.send_json(200, out)


def main():
    ap = argparse.ArgumentParser(description="Jarvis voice server")
    ap.add_argument("--port", type=int, default=int(os.environ.get("JARVIS_PORT", "8765")))
    ap.add_argument("--workers", help="JSON file that replaces the default workers (see README)")
    ap.add_argument("--whisper-model", default=os.environ.get("JARVIS_WHISPER", "base.en"))
    ap.add_argument("--piper-voice", default=os.environ.get("JARVIS_PIPER_VOICE", ""), help="path to a Piper .onnx voice")
    ap.add_argument("--voices-dir", default=os.environ.get("JARVIS_VOICES", str(Path(__file__).parent / "voices")),
                    help="folder of Piper .onnx voices offered in the app's voice picker")
    ap.add_argument("--piper", default=os.environ.get("JARVIS_PIPER", "piper"))
    ap.add_argument("--claude", default=os.environ.get("JARVIS_CLAUDE", "claude"))
    ap.add_argument("--claude-timeout", type=int, default=int(os.environ.get("JARVIS_CLAUDE_TIMEOUT", "120")))
    ap.add_argument("--ramble-words", type=int, default=int(os.environ.get("JARVIS_RAMBLE_WORDS", "40")),
                    help="messages this long are offered to Claude to sort into tasks, ideas and a note (0 = never)")
    ap.add_argument("--continue", dest="cont", action="store_true", help="each worker carries on its last conversation")
    a = ap.parse_args()
    token = os.environ.get("JARVIS_TOKEN", "")
    if len(token) < 20:
        raise SystemExit("Set JARVIS_TOKEN to a long random string (at least 20 characters), e.g. openssl rand -hex 24")
    workers = json.loads(Path(a.workers).read_text()) if a.workers else DEFAULT_WORKERS
    CONFIG.update(token=token, workers=workers, whisper_model=a.whisper_model, piper_voice=a.piper_voice, voices_dir=a.voices_dir,
                  piper=a.piper, claude=a.claude, claude_timeout=a.claude_timeout,
                  ramble_words=a.ramble_words if a.ramble_words > 0 else 10**9, **{"continue": a.cont})
    print(f"Jarvis on http://127.0.0.1:{a.port} · workers: {', '.join(workers)}"
          f" · speech: {'Piper' if a.piper_voice else 'off (text only)'}"
          f" · voices: {', '.join(voice_files()) or 'none'}", flush=True)
    ThreadingHTTPServer(("127.0.0.1", a.port), Handler).serve_forever()


if __name__ == "__main__":
    main()
