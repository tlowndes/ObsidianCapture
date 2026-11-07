"""A stand-in for the Jarvis voice server, so the app's Jarvis tab can be tested before the Oracle box is ready.

Speaks the same contract as the real one:
  POST /voice   multipart: file (OGG/Opus recording), worker; header Authorization: Bearer <token>
                -> {"transcript", "reply", "audio_url"}
  GET  /audio/<id>.ogg   same token; plays your own recording back
  GET  /done    finished background jobs (a fake one appears 20 s after you talk to Research)

It only listens on 127.0.0.1. Put it on your tailnet with HTTPS:
  python tools/fake-jarvis/fake_jarvis.py --token test-token
  tailscale serve --bg 8765
Then in the app: Settings > Jarvis > Address = <this-pc>.<tailnet>.ts.net, Token = test-token.
Python 3.9+, standard library only.
"""
import argparse
import json
import threading
import time
import uuid
from email.parser import BytesParser
from email.policy import HTTP
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

REPLIES = {
    "jarvis": "Fake Jarvis here. I got your recording and I'm playing it back to you.",
    "coding": "Fake Coding worker. In the real thing I'd hand this to the Claude CLI in the repo.",
    "obsidian": "Fake Obsidian worker. I'd add that to the vault for real.",
    "research": "Fake Research worker. I'll pretend to look into it and tell you when I'm done.",
    "odd-jobs": "Fake Odd jobs worker. Noted.",
}

audio = {}  # id -> bytes
done = []  # finished jobs
lock = threading.Lock()


def multipart(body, content_type):
    msg = BytesParser(policy=HTTP).parsebytes(b"Content-Type: " + content_type.encode() + b"\r\n\r\n" + body)
    parts = {}
    for part in msg.iter_parts():
        name = part.get_param("name", header="content-disposition")
        parts[name] = part.get_payload(decode=True)
    return parts


class Handler(BaseHTTPRequestHandler):
    token = ""

    def send_json(self, code, obj):
        out = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(out)))
        self.end_headers()
        self.wfile.write(out)

    def authorised(self):
        if self.headers.get("Authorization") == "Bearer " + self.token:
            return True
        self.send_json(401, {"error": "bad token"})
        return False

    def do_GET(self):
        if not self.authorised():
            return
        if self.path == "/done":
            with lock:
                self.send_json(200, list(done))
        elif self.path.startswith("/audio/") and self.path.endswith(".ogg"):
            data = audio.get(self.path[len("/audio/"):-len(".ogg")])
            if data is None:
                self.send_json(404, {"error": "no such audio"})
                return
            self.send_response(200)
            self.send_header("Content-Type", "audio/ogg")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)
        else:
            self.send_json(405 if self.path == "/voice" else 404, {"error": "POST /voice"})

    def do_POST(self):
        if not self.authorised():
            return
        if self.path != "/voice":
            self.send_json(404, {"error": "not found"})
            return
        body = self.rfile.read(int(self.headers.get("Content-Length", "0")))
        parts = multipart(body, self.headers.get("Content-Type", ""))
        rec = parts.get("file") or parts.get("audio") or b""
        worker = (parts.get("worker") or b"jarvis").decode().strip()
        if not rec:
            self.send_json(400, {"error": "no file part"})
            return
        aid = uuid.uuid4().hex[:12]
        audio[aid] = rec
        print(f"/voice  worker={worker}  audio={len(rec)} bytes  ogg={rec[:4] == b'OggS'}", flush=True)
        if worker == "research":
            def finish():
                time.sleep(20)
                with lock:
                    done.append({"id": aid, "title": "Research finished (fake)", "detail": "A pretend background job, to test the Done line."})
            threading.Thread(target=finish, daemon=True).start()
        self.send_json(200, {
            "transcript": f"(fake) {len(rec):,} bytes of audio for {worker}",
            "reply": REPLIES.get(worker, REPLIES["jarvis"]),
            "audio_url": f"/audio/{aid}.ogg",
        })


def main():
    ap = argparse.ArgumentParser(description="Fake Jarvis voice server")
    ap.add_argument("--token", required=True, help="bearer token the app must send")
    ap.add_argument("--port", type=int, default=8765)
    a = ap.parse_args()
    Handler.token = a.token
    print(f"Fake Jarvis on http://127.0.0.1:{a.port}  (run: tailscale serve --bg {a.port})", flush=True)
    ThreadingHTTPServer(("127.0.0.1", a.port), Handler).serve_forever()


if __name__ == "__main__":
    main()
