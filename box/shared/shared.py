#!/usr/bin/env python3
"""Shared household list for Flight Deck (extras-v1, idea 17).

One small private page, served from the Oracle box, for someone who doesn't use Obsidian or the app.
It can only read and write two notes in the vault, and nothing else:

  01 - Inbox/Shopping list.md   "- [ ] item" lines. Items added here end in " 👥".
  99 - Meta/Chores.md           "- 🛁 Clean bathroom · every 7 · done 2026-11-03". Done here adds "· by <name>".

It also writes 99 - Meta/Shared list.md so the app knows the page is on.
The page lives under a secret token: https://<host>/<TOKEN>/ . Anything else is a 404.
Standard library only. Settings in ~/shared/.env: VAULT, TOKEN, optional PORT (8787), PARTNER (partner), OWNER (Tom).
"""
import hmac
import html
import json
import os
import re
import sys
import tempfile
import threading
from datetime import date, datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

HOME = Path(os.environ.get("SHARED_HOME", Path.home() / "shared"))
SHOPPING = "01 - Inbox/Shopping list.md"
CHORES = "99 - Meta/Chores.md"
STATE = "99 - Meta/Shared list.md"
MARK = "👥"
MAX_TEXT = 120
LOCK = threading.Lock()

TICK = re.compile(r"^\s*[-*]\s+\[([ xX])]\s+(.+)$")
CHORE = re.compile(r"^\s*[-*]\s+(.+?)\s*$")


def env():
    f = HOME / ".env"
    if f.exists():
        for line in f.read_text().splitlines():
            if "=" in line and not line.strip().startswith("#"):
                k, v = line.split("=", 1)
                os.environ.setdefault(k.strip(), v.strip().strip('"'))
    missing = [k for k in ("VAULT", "TOKEN") if not os.environ.get(k)]
    if missing:
        sys.exit("Missing in .env: " + ", ".join(missing))
    if len(os.environ["TOKEN"]) < 24:
        sys.exit("TOKEN should be at least 24 characters: python3 -c 'import secrets; print(secrets.token_urlsafe(24))'")


def vault():
    return Path(os.environ["VAULT"]).expanduser()


def read(rel):
    p = vault() / rel
    try:
        return p.read_text(encoding="utf-8")
    except FileNotFoundError:
        return ""


def write(rel, text):
    """Atomic write, only ever to one of the three notes above."""
    assert rel in (SHOPPING, CHORES, STATE)
    p = vault() / rel
    p.parent.mkdir(parents=True, exist_ok=True)
    fd, tmp = tempfile.mkstemp(dir=p.parent, prefix=".shared-", suffix=".tmp")
    with os.fdopen(fd, "w", encoding="utf-8", newline="\n") as f:
        f.write(text)
    os.replace(tmp, p)


def clean_text(s):
    s = re.sub(r"[\r\n\t]+", " ", str(s or "")).strip()
    s = s.replace("[[", "").replace("]]", "")
    return s[:MAX_TEXT].strip()


def bare(item):
    return item.rstrip().removesuffix(MARK).rstrip()


# ---- shopping ----

def shopping():
    out = []
    for line in read(SHOPPING).split("\n"):
        m = TICK.match(line)
        if m and m.group(1) == " ":
            raw = m.group(2).strip()
            out.append({"text": bare(raw), "partner": raw.endswith(MARK)})
    return out


def add_item(text):
    text = clean_text(text)
    if not text:
        return
    lines = read(SHOPPING).split("\n")
    if any(bare(i["text"]).lower() == text.lower() for i in shopping()):
        return
    while lines and not lines[-1].strip():
        lines.pop()
    if not lines:
        lines = ["# Shopping list", ""]
    lines.append(f"- [ ] {text} {MARK}")
    write(SHOPPING, "\n".join(lines) + "\n")


def tick_item(text):
    text = clean_text(text).lower()
    lines = read(SHOPPING).split("\n")
    for n, line in enumerate(lines):
        m = TICK.match(line)
        if m and m.group(1) == " " and bare(m.group(2).strip()).lower() == text:
            lines[n] = line.replace("[ ]", "[x]", 1)
            write(SHOPPING, "\n".join(lines))
            return


# ---- chores ----

def parse_chore(line):
    m = CHORE.match(line)
    if not m or m.group(1).startswith("["):
        return None
    parts = [p.strip() for p in m.group(1).split("·") if p.strip()]
    if not parts:
        return None
    every, done, by = 7, None, None
    for p in parts[1:]:
        if e := re.fullmatch(r"every\s+(\d+)(?:\s*days?)?", p, re.I):
            every = max(1, min(365, int(e.group(1))))
        elif d := re.fullmatch(r"done\s+(\d{4}-\d{2}-\d{2})", p, re.I):
            try:
                done = date.fromisoformat(d.group(1))
            except ValueError:
                pass
        elif b := re.fullmatch(r"by\s+(\S.*)", p, re.I):
            by = b.group(1).strip()
    label = parts[0]
    first, _, rest = label.partition(" ")
    emoji, name = (first, rest.strip()) if rest and not any(c.isalnum() for c in first) else ("", label)
    return {"label": label, "name": name, "emoji": emoji, "every": every, "done": done, "by": by} if name else None


def chore_line(c):
    s = f"- {c['label']} · every {c['every']}"
    if c["done"]:
        s += f" · done {c['done'].isoformat()}"
    if c["by"]:
        s += f" · by {c['by']}"
    return s


def chores():
    today = date.today()
    out = []
    for line in read(CHORES).split("\n"):
        c = parse_chore(line)
        if not c:
            continue
        age = (today - c["done"]).days if c["done"] else None
        fill = (age / c["every"]) if age is not None else 1.0
        since = "not yet" if age is None else "today" if age <= 0 else "1 day" if age == 1 else f"{age} days"
        out.append({"label": c["label"], "name": c["name"], "since": since, "every": c["every"], "fill": round(fill, 2), "by": c["by"]})
    return sorted(out, key=lambda c: -c["fill"])


def chore_done(name, who):
    name = clean_text(name).lower()
    lines = read(CHORES).split("\n")
    for n, line in enumerate(lines):
        c = parse_chore(line)
        if c and c["name"].lower() == name:
            c["done"], c["by"] = date.today(), who
            lines[n] = chore_line(c)
            write(CHORES, "\n".join(lines))
            return


def mark_state():
    write(STATE, "---\ntype: shared-list\nupdated: " + datetime.now().strftime("%Y-%m-%dT%H:%M:%S") + "\n---\n\n"
          "Written by the Oracle box's shared page (box/shared). It only reads and writes [[Shopping list]] and [[Chores]].\n")


# ---- page ----

PAGE = """<!doctype html><html lang="en-GB"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><meta name="robots" content="noindex">
<meta name="apple-mobile-web-app-capable" content="yes"><meta name="theme-color" content="#14161c">
<title>Our shopping</title><style>
:root{--bg:#f6f5f2;--card:#fff;--ink:#1d1d1f;--muted:#6e6e73;--line:#e6e4df;--accent:#5b5bd6;--warn:#c2410c}
@media (prefers-color-scheme:dark){:root{--bg:#14161c;--card:#1d2028;--ink:#f2f2f4;--muted:#9a9aa5;--line:#2c2f39;--accent:#8b8bf0;--warn:#fb923c}}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--ink);font:16px/1.4 system-ui,-apple-system,sans-serif}
main{max-width:520px;margin:0 auto;padding:20px 16px 48px}h1{font-size:26px;margin:4px 0 2px}h2{font-size:15px;color:var(--muted);margin:28px 0 8px;font-weight:600}
.sub{color:var(--muted);font-size:13px}.card{background:var(--card);border:1px solid var(--line);border-radius:14px;overflow:hidden}
form{display:flex;gap:8px;margin:16px 0 0}input{flex:1;font:inherit;padding:12px 14px;border-radius:12px;border:1px solid var(--line);background:var(--card);color:var(--ink)}
button{font:inherit;border:0;border-radius:12px;padding:0 16px;background:var(--accent);color:#fff;font-weight:600;cursor:pointer}
.row{display:flex;align-items:center;gap:12px;padding:13px 14px;border-top:1px solid var(--line)}.row:first-child{border-top:0}
.box{width:22px;height:22px;border-radius:7px;border:2px solid var(--muted);flex:none;background:none;padding:0}
.grow{flex:1;min-width:0}.tag{font-size:12px;color:var(--muted)}.done{background:none;color:var(--accent);border:1px solid var(--line);padding:6px 12px;font-size:14px}
.bar{height:4px;border-radius:2px;background:var(--line);margin-top:6px;overflow:hidden}.bar i{display:block;height:100%;background:var(--accent)}
.late .bar i{background:var(--warn)}.empty{padding:16px 14px;color:var(--muted)}
</style></head><body><main>
<h1>Our shopping</h1><div class="sub">Shared by __OWNER__ · updates in a few seconds</div>
<form id="add"><input id="t" maxlength="120" placeholder="Add an item…" autocomplete="off" enterkeyhint="done"><button>Add</button></form>
<h2>Shopping</h2><div class="card" id="shop"></div>
<h2>Chores</h2><div class="card" id="chores"></div>
</main><script>
const base=location.pathname.replace(/\\/?$/,'/');
const esc=s=>s.replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
async function post(p,b){await fetch(base+p,{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify(b)});load()}
async function load(){
  let d;try{d=await (await fetch(base+'api',{cache:'no-store'})).json()}catch(e){return}
  const s=document.getElementById('shop');
  s.innerHTML=d.shopping.length?d.shopping.map(i=>`<div class="row"><button class="box" aria-label="Got it" data-t="${esc(i.text)}"></button><div class="grow">${esc(i.text)}</div>${i.partner?'<span class="tag">you</span>':''}</div>`).join(''):'<div class="empty">Nothing on the list</div>';
  s.querySelectorAll('.box').forEach(b=>b.onclick=()=>post('tick',{text:b.dataset.t}));
  const c=document.getElementById('chores');
  c.innerHTML=d.chores.length?d.chores.map(x=>`<div class="row ${x.fill>=1.25?'late':''}"><div class="grow">${esc(x.label)}<div class="tag">${esc(x.since)} · every ${x.every}${x.by?' · '+esc(x.by)+' did it last':''}</div><div class="bar"><i style="width:${Math.min(100,Math.round(x.fill*100))}%"></i></div></div><button class="done" data-n="${esc(x.name)}">Done</button></div>`).join(''):'<div class="empty">No chores yet</div>';
  c.querySelectorAll('.done').forEach(b=>b.onclick=()=>post('done',{name:b.dataset.n}));
}
document.getElementById('add').onsubmit=e=>{e.preventDefault();const t=document.getElementById('t');if(t.value.trim()){post('add',{text:t.value});t.value=''}};
load();setInterval(()=>{if(!document.hidden)load()},5000);document.addEventListener('visibilitychange',()=>{if(!document.hidden)load()});
</script></body></html>"""


class Handler(BaseHTTPRequestHandler):
    server_version = "shared"

    def log_message(self, fmt, *args):  # no access log: nothing kept about who visits
        pass

    def route(self):
        token = os.environ["TOKEN"]
        parts = self.path.split("?", 1)[0].strip("/").split("/")
        if not parts or not hmac.compare_digest(parts[0].encode(), token.encode()):
            return None
        return "/".join(parts[1:])

    def send(self, code, body=b"", kind="text/plain; charset=utf-8"):
        self.send_response(code)
        self.send_header("Content-Type", kind)
        self.send_header("Cache-Control", "no-store")
        self.send_header("Referrer-Policy", "no-referrer")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        r = self.route()
        if r is None:
            return self.send(404, b"Not found")
        if r == "":
            page = PAGE.replace("__OWNER__", html.escape(os.environ.get("OWNER", "Tom")))
            return self.send(200, page.encode(), "text/html; charset=utf-8")
        if r == "api":
            with LOCK:
                data = {"shopping": shopping(), "chores": chores()}
            return self.send(200, json.dumps(data, ensure_ascii=False).encode(), "application/json; charset=utf-8")
        self.send(404, b"Not found")

    def do_POST(self):
        r = self.route()
        if r not in ("add", "tick", "done"):
            return self.send(404, b"Not found")
        n = int(self.headers.get("Content-Length") or 0)
        if n > 2048:
            return self.send(413, b"Too big")
        try:
            body = json.loads(self.rfile.read(n) or b"{}")
        except ValueError:
            return self.send(400, b"Bad request")
        who = os.environ.get("PARTNER", "partner")
        with LOCK:
            if r == "add":
                add_item(body.get("text"))
            elif r == "tick":
                tick_item(body.get("text"))
            else:
                chore_done(body.get("name"), who)
            mark_state()
        self.send(204)


def main():
    env()
    port = int(os.environ.get("PORT", "8787"))
    host = os.environ.get("HOST", "127.0.0.1")
    with LOCK:
        mark_state()
    print(f"Shared list on http://{host}:{port}/<TOKEN>/", flush=True)
    ThreadingHTTPServer((host, port), Handler).serve_forever()


if __name__ == "__main__":
    main()
