#!/usr/bin/env python3
"""Daily Brief for Flight Deck (daily-brief-v1). Runs on the Oracle box at 06:15 from a systemd timer.

Writes "99 - Meta/Daily Brief.md" in the vault: the morning email as a page the app shows and reads out, with the
TL;DR news picked from the day's TLDR emails (Design, AI, Tech, Security). Obsidian Sync carries it to the phone and PC.
The app writes its own (without the news) from 06:45 if this one isn't there, so a missed run never leaves a gap.

Read-only everywhere except that one note: the vault is only read, and mail is read through Proton Mail Bridge with
IMAP EXAMINE and BODY.PEEK, so nothing is marked read, moved or deleted. Standard library only.

Settings come from ~/brief/.env (chmod 600): ANTHROPIC_API_KEY, VAULT, LAT, LON, and for the news BRIDGE_USER and
BRIDGE_PASS (the Bridge password). Optional: BRIDGE_HOST, BRIDGE_PORT, NEWS_FOLDER (INBOX), NEWS_FROM
(tldrnewsletter.com), MODEL (claude-sonnet-5-5), NEWS_MODEL (claude-haiku-5-5), TZ_NAME (Europe/London).

    python3 brief.py            write today's brief unless one the box wrote, or one Tom has opened, is already there
    python3 brief.py --force    write it again anyway
    python3 brief.py --dry-run  print it instead of saving
"""
import datetime as dt
import email
import email.header
import email.utils
import html
import imaplib
import json
import os
import re
import ssl
import sys
import tempfile
import urllib.request
from pathlib import Path

try:
    from zoneinfo import ZoneInfo
except ImportError:  # Python < 3.9
    ZoneInfo = None

HOME = Path(os.environ.get("BRIEF_HOME", Path.home() / "brief"))
NOTE = "99 - Meta/Daily Brief.md"
QUICK_TASK = "01 - Inbox/⚡ Quick Task.md"
SKIP = ("99 - Meta", "01 - Inbox/Read Later")
JOBS = "02 - Area/Work/Job Hunt/Applications"
CONFIG = "99 - Meta/capture-config.json"
EVENTS = "99 - Meta/Events.md"
EMAIL = "99 - Meta/Email.md"
DAY_START, DAY_END = dt.time(9, 0), dt.time(18, 0)
APPS_WEEK = 3
NEWS_CATS = ("Design", "AI", "Tech", "Security")
NEWS_PER = 3
ISSUE_CHARS = 14000
CHAPTERS = ["Opening & today", "Weather & travel", "Three things", "Coming up", "Roles", "Bills & inbox", "The TL;DR", "Hard stop"]


def env():
    f = HOME / ".env"
    if f.exists():
        for line in f.read_text().splitlines():
            if "=" in line and not line.strip().startswith("#"):
                k, v = line.split("=", 1)
                os.environ.setdefault(k.strip(), v.strip().strip('"'))
    missing = [k for k in ("ANTHROPIC_API_KEY", "VAULT") if not os.environ.get(k)]
    if missing:
        sys.exit("Missing in .env: " + ", ".join(missing))


def tz():
    name = os.environ.get("TZ_NAME", "Europe/London")
    return ZoneInfo(name) if ZoneInfo else None


def read(vault, rel):
    try:
        return (vault / rel).read_text(encoding="utf-8").replace("\r", "")
    except (FileNotFoundError, IsADirectoryError):
        return None


def frontmatter(text):
    m = re.match(r"^---\n(.*?)\n---", text or "", re.S)
    out = {}
    for line in (m.group(1).splitlines() if m else []):
        if ":" in line:
            k, v = line.split(":", 1)
            out[k.strip()] = v.strip().strip('"').strip("'")
    return out


def day_label(d):
    return d.strftime("%a ") + str(d.day)


def hours(m):
    return f"{m}m" if m < 60 else f"{m // 60}h" if m % 60 == 0 else f"{m // 60}h {m % 60}m"


# ---- Vault: tasks, jobs, events, inbox ----

DUE = re.compile(r"📅\s*(\d{4}-\d{2}-\d{2})")
TAG = re.compile(r"(?<!\S)#([\w/-]+)")
MARKERS = re.compile(r"(📅|⏳|🛫|➕|✅|🔁|⏱|🔺|⏫|🔼|🔽|⏬)\s*[^📅⏳🛫➕✅🔁⏱🔺⏫🔼🔽⏬#]*")


def plain(text):
    t = MARKERS.sub("", text)
    t = TAG.sub("", t)
    return re.sub(r"\s+", " ", t).strip()


def tasks(vault):
    """Open tasks: every `- [ ]` in Quick Task, and `#task` lines elsewhere (like the app). (text, due, tags, bill, minutes)."""
    out = []
    for p in vault.rglob("*.md"):
        rel = p.relative_to(vault).as_posix()
        if any(part.startswith(".") for part in rel.split("/")) or any(rel == s or rel.startswith(s + "/") for s in SKIP):
            continue
        try:
            lines = p.read_text(encoding="utf-8").replace("\r", "").split("\n")
        except Exception:
            continue
        quick = rel == QUICK_TASK
        fence, i = False, 0
        if lines and lines[0].strip() == "---":
            end = next((n for n in range(1, len(lines)) if lines[n].strip() == "---"), None)
            if end:
                i = end + 1
        for line in lines[i:]:
            s = line.strip()
            if s.startswith("```") or s.startswith("~~~"):
                fence = not fence
                continue
            m = re.match(r"^[-*] \[ \] (.+)$", s)
            if fence or not m or (not quick and "#task" not in s):
                continue
            body = m.group(1)
            due = DUE.search(body)
            mins = re.search(r"⏱\s*(\d+)\s*m", body)
            tags = [t.lower() for t in TAG.findall(body)]
            out.append({"text": plain(body), "due": dt.date.fromisoformat(due.group(1)) if due else None, "tags": tags,
                        "bill": "bill" in tags, "min": int(mins.group(1)) if mins else None, "raw": body})
    seen, uniq = set(), []
    for t in out:
        k = (t["text"].lower(), t["due"])
        if k not in seen:
            seen.add(k)
            uniq.append(t)
    return uniq


def jobs(vault):
    out = []
    folder = vault / JOBS
    for p in (folder.glob("*.md") if folder.exists() else []):
        fm = frontmatter(p.read_text(encoding="utf-8", errors="replace").replace("\r", ""))
        if fm.get("type", "").lower() != "application":
            continue
        def date(k):
            try:
                return dt.date.fromisoformat(fm.get(k, "")[:10])
            except ValueError:
                return None
        out.append({"company": fm.get("company", ""), "role": fm.get("role", ""), "status": fm.get("status") or "Shortlisted",
                    "next": date("follow_up"), "applied": date("applied"), "fit": fm.get("fit"),
                    "path": p.relative_to(vault).as_posix()})
    return out


def parse_ics_time(value, params, zone):
    """(datetime in local time, all_day)."""
    if "VALUE=DATE" in params or re.fullmatch(r"\d{8}", value):
        return dt.datetime.strptime(value[:8], "%Y%m%d"), True
    t = dt.datetime.strptime(value[:15], "%Y%m%dT%H%M%S")
    if value.endswith("Z"):
        t = t.replace(tzinfo=dt.timezone.utc)
        return (t.astimezone(zone) if zone else t.astimezone()).replace(tzinfo=None), False
    m = re.search(r"TZID=([^;:]+)", params)
    if m and ZoneInfo:
        try:
            return t.replace(tzinfo=ZoneInfo(m.group(1))).astimezone(zone).replace(tzinfo=None), False
        except Exception:
            pass
    return t, False


def ics_events(text, zone, start, end):
    """Events between start and end (dates), with simple repeats (DAILY, WEEKLY, MONTHLY, YEARLY; INTERVAL, COUNT, UNTIL)."""
    text = re.sub(r"\r?\n[ \t]", "", text.replace("\r\n", "\n"))
    out = []
    for block in re.findall(r"BEGIN:VEVENT\n(.*?)END:VEVENT", text, re.S):
        props = {}
        for line in block.split("\n"):
            if ":" not in line:
                continue
            k, v = line.split(":", 1)
            name, _, params = k.partition(";")
            props.setdefault(name.upper(), (params, v))
        if "DTSTART" not in props or props.get("STATUS", ("", ""))[1].upper() == "CANCELLED":
            continue
        s, all_day = parse_ics_time(props["DTSTART"][1], props["DTSTART"][0], zone)
        e = parse_ics_time(props["DTEND"][1], props["DTEND"][0], zone)[0] if "DTEND" in props else s + (dt.timedelta(days=1) if all_day else dt.timedelta(hours=1))
        title = props.get("SUMMARY", ("", "(No title)"))[1].replace("\\,", ",").replace("\\;", ";").replace("\\n", " ").strip()
        place = props.get("LOCATION", ("", ""))[1].replace("\\,", ",").strip() or None
        length = e - s
        starts = [s]
        rule = props.get("RRULE")
        if rule:
            r = dict(p.split("=", 1) for p in rule[1].split(";") if "=" in p)
            freq, every = r.get("FREQ", ""), int(r.get("INTERVAL", "1") or 1)
            count = int(r["COUNT"]) if "COUNT" in r else None
            until = parse_ics_time(r["UNTIL"], "", zone)[0] if "UNTIL" in r else None
            cur, n = s, 1
            while cur.date() <= end and n < 1000 and (count is None or n < count):
                if freq == "DAILY":
                    cur += dt.timedelta(days=every)
                elif freq == "WEEKLY":
                    cur += dt.timedelta(weeks=every)
                elif freq == "MONTHLY":
                    y, mo = divmod(cur.month - 1 + every, 12)
                    try:
                        cur = cur.replace(year=cur.year + y, month=mo + 1)
                    except ValueError:
                        break
                elif freq == "YEARLY":
                    try:
                        cur = cur.replace(year=cur.year + every)
                    except ValueError:
                        break
                else:
                    break
                if until and cur > until:
                    break
                n += 1
                starts.append(cur)
        for st in starts:
            en = st + length
            if en.date() >= start and st.date() <= end:
                out.append({"title": title, "start": st, "end": en, "all_day": all_day, "place": place})
    return out


def events(vault, today, zone):
    out = []
    cfg = read(vault, CONFIG)
    try:
        cals = json.loads(cfg).get("calendars", []) if cfg else []
    except ValueError:
        cals = []
    for c in cals:
        if not c.get("enabled", True) or not c.get("url"):
            continue
        try:
            url = c["url"].replace("webcal://", "https://")
            with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "FlightDeckBox/1"}), timeout=30) as r:
                out += ics_events(r.read().decode("utf-8", "replace"), zone, today, today + dt.timedelta(days=7))
        except Exception as e:
            print("Calendar failed:", c.get("name"), e, file=sys.stderr)
    for line in (read(vault, EVENTS) or "").split("\n"):
        m = re.match(r"^- ○ (\d{4}-\d{2}-\d{2})(?: (\d{1,2}:\d{2}))?(?: (\d+h(?:\d+m)?|\d+m))? (.+?)(?: 📍 (.+?))?(?: — (.+))?$", line.strip())
        if not m:
            continue
        d = dt.date.fromisoformat(m.group(1))
        if m.group(2):
            h, mi = map(int, m.group(2).split(":"))
            st = dt.datetime.combine(d, dt.time(h, mi))
            ln = m.group(3) or "1h"
            mins = sum(int(x) * (60 if u == "h" else 1) for x, u in re.findall(r"(\d+)([hm])", ln))
            out.append({"title": m.group(4), "start": st, "end": st + dt.timedelta(minutes=mins or 60), "all_day": False, "place": m.group(5)})
        else:
            st = dt.datetime.combine(d, dt.time())
            out.append({"title": m.group(4), "start": st, "end": st + dt.timedelta(days=1), "all_day": True, "place": m.group(5)})
    return out


def on(e, d):
    s, e2 = e["start"], e["end"]
    return s < dt.datetime.combine(d + dt.timedelta(days=1), dt.time()) and (e2 > dt.datetime.combine(d, dt.time()) or s.date() == d)


def free_minutes(evs, d):
    start, end = dt.datetime.combine(d, DAY_START), dt.datetime.combine(d, DAY_END)
    busy, cursor = 0, start
    for e in sorted((e for e in evs if not e["all_day"] and e["end"] > start and e["start"] < end), key=lambda e: e["start"]):
        s, f = max(e["start"], cursor), min(e["end"], end)
        if f > s:
            busy += int((f - s).total_seconds() // 60)
            cursor = f
    return max(0, int((end - start).total_seconds() // 60) - busy)


def inbox(vault):
    """Emails that need Tom (reply, bill, job), from the digest the mailbox script writes, minus ones he's handled."""
    text = read(vault, EMAIL) or ""
    state = dict(re.findall(r"^- (\S+) = (\S+)\s*$", read(vault, "99 - Meta/Email state.md") or "", re.M))
    today = dt.date.today().isoformat()
    def handled(i):
        s = state.get(i)
        return s in ("done", "dismissed") or (s or "").startswith("snooze:") and s[7:] > today
    out = []
    for m in re.finditer(r"<!--mail:(\{.*\})-->", text):
        try:
            j = json.loads(m.group(1))
        except ValueError:
            continue
        if j.get("kind") in ("reply", "bill", "job") and not handled(j.get("id")):
            out.append(j)
    return out


# ---- Weather ----

def weather(today):
    lat, lon = os.environ.get("LAT"), os.environ.get("LON")
    if not lat or not lon:
        return None
    url = ("https://api.open-meteo.com/v1/forecast?latitude=%s&longitude=%s&hourly=temperature_2m,precipitation_probability,weather_code"
           "&daily=temperature_2m_max,temperature_2m_min,sunset,sunrise&timezone=auto&forecast_days=1") % (lat, lon)
    try:
        with urllib.request.urlopen(url, timeout=30) as r:
            j = json.loads(r.read())
    except Exception as e:
        print("Weather failed:", e, file=sys.stderr)
        return None
    d, h = j["daily"], j["hourly"]
    hourly = ", ".join("%s %d° %d%%" % (t[11:16], round(temp), p or 0) for t, temp, p in
                       zip(h["time"], h["temperature_2m"], h["precipitation_probability"]) if 7 <= int(t[11:13]) <= 21)
    return {"high": round(d["temperature_2m_max"][0]), "low": round(d["temperature_2m_min"][0]),
            "sunset": d["sunset"][0][11:16], "sunrise": d["sunrise"][0][11:16], "hourly": hourly}


# ---- News from the TLDR emails ----

def text_with_links(msg):
    """Plain text of an issue with each link kept as 'title [url]', so Claude can pick articles and their links."""
    htmls, plains = [], []
    for part in msg.walk() if msg.is_multipart() else [msg]:
        if part.get_content_maintype() == "multipart" or part.get_filename():
            continue
        try:
            body = part.get_payload(decode=True).decode(part.get_content_charset() or "utf-8", "replace")
        except Exception:
            continue
        if part.get_content_type() == "text/html":
            htmls.append(body)
        elif part.get_content_type() == "text/plain":
            plains.append(body)
    if htmls:
        h = re.sub(r"(?is)<(style|script).*?</\1>", " ", "\n".join(htmls))
        h = re.sub(r'(?is)<a [^>]*href="([^"]+)"[^>]*>(.*?)</a>', lambda m: " %s [%s] " % (re.sub(r"<[^>]+>", "", m.group(2)).strip(), m.group(1)), h)
        out = html.unescape(re.sub(r"<[^>]+>", " ", h))
    else:
        out = "\n".join(plains)
    return re.sub(r"\s+", " ", out).strip()[:ISSUE_CHARS]


def category(sender, subject):
    s = (sender + " " + subject).lower()
    if "design" in s:
        return "Design"
    if "infosec" in s or "security" in s:
        return "Security"
    if re.search(r"\bai\b", s):
        return "AI"
    return "Tech"


def tldr_issues(today):
    """The newest TLDR issue of each kind, from the last 3 days (so weekends get Friday's). Read-only."""
    if not os.environ.get("BRIDGE_USER") or not os.environ.get("BRIDGE_PASS"):
        return {}, None
    host = os.environ.get("BRIDGE_HOST", "127.0.0.1")
    port = int(os.environ.get("BRIDGE_PORT", "1143"))
    ctx = ssl.create_default_context()
    if host in ("127.0.0.1", "localhost"):
        ctx.check_hostname = False
        ctx.verify_mode = ssl.CERT_NONE
    imap = imaplib.IMAP4(host, port)
    imap.starttls(ctx)
    imap.login(os.environ["BRIDGE_USER"], os.environ["BRIDGE_PASS"])
    since = (today - dt.timedelta(days=3)).strftime("%d-%b-%Y")
    issues = {}
    try:
        ok, _ = imap.select('"%s"' % os.environ.get("NEWS_FOLDER", "INBOX"), readonly=True)  # EXAMINE
        if ok != "OK":
            return {}, None
        ok, data = imap.uid("SEARCH", None, "SINCE", since, "FROM", os.environ.get("NEWS_FROM", "tldrnewsletter.com"))
        for uid in (data[0].split() if ok == "OK" and data and data[0] else []):
            ok, parts = imap.uid("FETCH", uid, "(BODY.PEEK[])")
            raw = next((p[1] for p in parts if isinstance(p, tuple)), None) if ok == "OK" else None
            if not raw:
                continue
            msg = email.message_from_bytes(raw)
            sender = str(email.header.make_header(email.header.decode_header(msg.get("From") or "")))
            subject = str(email.header.make_header(email.header.decode_header(msg.get("Subject") or "")))
            try:
                when = email.utils.parsedate_to_datetime(msg.get("Date")).astimezone().replace(tzinfo=None)
            except Exception:
                continue
            cat = category(sender, subject)
            if cat not in issues or when > issues[cat]["when"]:
                issues[cat] = {"when": when, "subject": subject, "text": text_with_links(msg)}
    finally:
        try:
            imap.logout()
        except Exception:
            pass
    newest = max((i["when"].date() for i in issues.values()), default=None)
    return issues, newest


def pick_news(issues):
    """Claude picks NEWS_PER stories from each issue: [{cat, title, url, line}]."""
    picks = []
    for cat in NEWS_CATS:
        issue = issues.get(cat)
        if not issue:
            continue
        system = (f"You pick news for Tom, a designer job hunting for in-house or remote design roles who uses AI tools a lot. "
                  f"From this TLDR {cat} issue, pick the {NEWS_PER} stories most useful or interesting to him. Skip sponsors and ads. "
                  "Reply with JSON only, no code fences: [{\"title\": short title, \"url\": the story's link exactly as given in [brackets], "
                  "\"line\": one plain UK English line, under 18 words, on why it matters to him}]. No em dashes.")
        try:
            reply, _ = claude(os.environ.get("NEWS_MODEL", "claude-haiku-5-5"), system, issue["subject"] + "\n\n" + issue["text"], 900)
            m = re.search(r"\[.*\]", reply, re.S)
            for p in (json.loads(m.group(0)) if m else [])[:NEWS_PER]:
                if p.get("title") and p.get("url"):
                    picks.append({"cat": cat, "title": clean(p["title"]), "url": p["url"].strip(), "line": clean(p.get("line", ""))})
        except Exception as e:
            print("News pick failed:", cat, e, file=sys.stderr)
    return picks


def clean(s):
    return re.sub(r"\s+", " ", str(s).replace("|", "/")).strip()


# ---- Claude ----

def claude(model, system, user, max_tokens):
    body = json.dumps({"model": model, "max_tokens": max_tokens, "system": system, "messages": [{"role": "user", "content": user}]}).encode()
    req = urllib.request.Request("https://api.anthropic.com/v1/messages", body, {
        "x-api-key": os.environ["ANTHROPIC_API_KEY"], "anthropic-version": "2023-06-01", "content-type": "application/json"})
    with urllib.request.urlopen(req, timeout=180) as r:
        out = json.loads(r.read())
    return "".join(b.get("text", "") for b in out.get("content", [])), out.get("usage", {})


SYSTEM = """You are Jarvis, writing Tom's Daily Brief: a page he reads in his app and a version read out loud.
Tom is a designer job hunting for in-house or remote design roles, building his portfolio. Plain UK English, warm, calm, a bit of humour, no corporate words, no em dashes.
Reply in exactly this format and nothing else:

## Opening
One sentence on the shape of the day.

## Weather
9° / 4° · Short headline | One or two sentences: the rain story, sunset, and what to wear or when to walk.

## Three things
- Area | low or medium or high | Task title, short | Why today, then "First step: ..."
(exactly three lines, picked from the candidate tasks; Area is a short word like Job hunt, Portfolio, Home, Admin)

## Hard stop
HH:MM | One sentence: when to stop work today and why (sunset, evening plans).

## Audio
### Opening & today
### Weather & travel
### Three things
### Coming up
### Roles
### Bills & inbox
### The TL;DR
### Hard stop
(under each heading, 2 to 5 spoken lines, one or two sentences each; leave out a heading with nothing to say; about 750 words in all.
For the TL;DR, say each story's title and why it matters, never a link.
Spoken style: no emoji, no markdown, no lists, times like "nine forty", money like "eighty-four pounds".)

## Short
5 to 8 spoken lines, about 250 words: the weather first, then the first event, the three things, anything due, a kind line to finish."""


# ---- Building the note ----

def base(now, all_tasks, evs, job_list, mail):
    """The sections straight from the vault, as the app's DailyBrief.base does."""
    today = now.date()
    todays = [e for e in evs if on(e, today)]
    overdue = sum(1 for t in all_tasks if t["due"] and t["due"] < today and not t["bill"])
    open_jobs = [j for j in job_list if j["status"].lower() not in ("rejected", "withdrawn")]
    monday = today - dt.timedelta(days=today.weekday())
    applied = sum(1 for j in job_list if j["applied"] and monday <= j["applied"] <= today)
    stats = [("free time", hours(free_minutes(todays, today))),
             ("roles to apply", str(sum(1 for j in open_jobs if j["status"].lower() == "shortlisted"))),
             ("waiting on you", str(len(mail) + overdue)),
             ("apps this wk", f"{applied}/{APPS_WEEK}")]
    timed = sorted((e for e in todays if not e["all_day"]), key=lambda e: e["start"])
    rows = [("All day", e["title"]) for e in todays if e["all_day"]] + \
           [(f"{e['start']:%H:%M}–{e['end']:%H:%M}", e["title"] + (f" · {e['place']}" if e["place"] else "")) for e in timed]
    if not timed:
        rows.append((f"{DAY_START:%H:%M}–{DAY_END:%H:%M}", "Free all day ✨ Nothing in the calendar."))
    coming = []
    for n in range(1, 7):
        d = today + dt.timedelta(days=n)
        ev = sorted((e for e in evs if on(e, d)), key=lambda e: (not e["all_day"], e["start"]))
        items = [f"All day: {e['title']}" if e["all_day"] else f"{e['start']:%H:%M} {e['title']}" for e in ev]
        items += [t["text"] for t in all_tasks if t["due"] == d and not t["bill"]]
        if items:
            coming.append((day_label(d), " · ".join(items[:3])))
    roles = []
    for j in sorted(open_jobs, key=lambda j: j["next"] or dt.date.max)[:4]:
        st = j["status"].lower()
        tag = "follow up" if j["next"] and j["next"] <= today else "interview" if st == "interview" else "to apply" if st == "shortlisted" else st
        detail = [x for x in (j["status"] if tag != st else None,
                              f"applied {day_label(j['applied'])}" if j["applied"] else None,
                              ("chase today" if j["next"] <= today else f"next {day_label(j['next'])}") if j["next"] else None,
                              f"fit {j['fit']}/5" if j["fit"] else None) if x]
        roles.append((tag, " · ".join(x for x in (j["role"], j["company"]) if x), " · ".join(detail), j["path"]))
    bills = []
    for t in sorted((t for t in all_tasks if t["bill"] and t["due"] and t["due"] < today + dt.timedelta(days=7)), key=lambda t: t["due"])[:4]:
        bills.append(" · ".join([t["text"], "late" if t["due"] < today else day_label(t["due"])]))
    bills += [f"✉ {(m.get('subject') or m.get('summary') or '')[:70]} · {m.get('from', '')}" for m in mail[:3]]
    return {"stats": stats, "today": rows, "coming": coming, "roles": roles, "bills": bills}


def prompt(now, b, w, all_tasks, news):
    today = now.date()
    out = [f"Today is {today:%A} {today.day} {today:%B}. It's {now:%H:%M}.",
           "Stats: " + ", ".join(f"{v} {k}" for k, v in b["stats"])]
    if w:
        out.append(f"Weather: {w['high']}° high, {w['low']}° low. Sunrise {w['sunrise']}, sunset {w['sunset']}. Hourly (time, temp, rain chance): {w['hourly']}")
    out.append("Today's calendar:")
    out += [f"- {a} {t}" for a, t in b["today"]]
    if b["coming"]:
        out.append("Coming up:")
        out += [f"- {a}: {t}" for a, t in b["coming"]]
    if b["roles"]:
        out.append("Job applications:")
        out += [f"- {r[1]} ({r[0]}) {r[2]}" for r in b["roles"]]
    if b["bills"]:
        out.append("Bills and email:")
        out += [f"- {x}" for x in b["bills"]]
    if news:
        out.append("Today's TL;DR picks:")
        out += [f"- {n['cat']}: {n['title']} ({n['line']})" for n in news]
    out.append("Candidate tasks (due or late first):")
    cands = sorted((t for t in all_tasks if not t["bill"]), key=lambda t: (t["due"] is None, t["due"] or dt.date.max))[:10]
    for t in cands:
        due = "" if not t["due"] else f" (late since {day_label(t['due'])})" if t["due"] < today else " (due today)" if t["due"] == today else f" (due {day_label(t['due'])})"
        out.append(f"- {t['text']}{due}" + (f" ~{t['min']}m" if t["min"] else "") + " " + " ".join("#" + x for x in t["tags"]))
    return "\n".join(out)


def sections(text):
    out, cur = {}, None
    for line in text.replace("\r", "").split("\n"):
        if line.startswith("## "):
            cur = out.setdefault(line[3:].strip(), [])
        elif cur is not None:
            cur.append(line)
    return out


def render(now, b, w, reply, news, writer="box"):
    r = sections(re.sub(r"^```\w*|```$", "", reply.strip()))
    def items(name):
        return [l.strip().lstrip("-•").strip() for l in r.get(name, []) if l.strip()]
    out = ["---", f"date: {now.date().isoformat()}", f"writer: {writer}", f'made: "{now:%H:%M}"', f"news: {'true' if news else 'false'}",
           "heard: false", "opened: false", "---"]
    def sec(name, lines):
        if lines:
            out.extend(["", f"## {name}"] + lines)
    sec("Opening", [" ".join(items("Opening"))] if items("Opening") else [])
    sec("Stats", [f"- {k} | {v}" for k, v in b["stats"]])
    sec("Today", [f"- {a} | {t}" for a, t in b["today"]])
    weather_line = next(iter(items("Weather")), None)
    if not weather_line and w:
        weather_line = f"{w['high']}° / {w['low']}° · Today | Sunset {w['sunset']}."
    sec("Weather", [weather_line] if weather_line else [])
    sec("Three things", ["- " + l for l in items("Three things")[:3] if l.count("|") >= 2])
    sec("Coming up", [f"- {a} | {t}" for a, t in b["coming"]])
    sec("Roles", [f"- {t} | {title} | {d} | {p}" for t, title, d, p in b["roles"]])
    sec("Bills & inbox", [f"- {x}" for x in b["bills"]])
    sec("TL;DR", [f"- {n['cat']} | {n['title']} | {n['url']} | {n['line']}" for n in news])
    sec("Hard stop", items("Hard stop")[:1])
    audio = [l.strip() for l in r.get("Audio", []) if l.strip()]
    sec("Audio", audio)
    sec("Short", items("Short"))
    return "\n".join(out) + "\n"


def write(vault, text):
    path = vault / NOTE
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, tmp = tempfile.mkstemp(dir=path.parent, suffix=".tmp")
    with os.fdopen(fd, "w", encoding="utf-8") as f:
        f.write(text)
    os.replace(tmp, path)  # one write, so Sync never sees half a note


def main():
    env()
    force, dry = "--force" in sys.argv, "--dry-run" in sys.argv
    vault = Path(os.environ["VAULT"]).expanduser()
    zone = tz()
    now = dt.datetime.now(zone).replace(tzinfo=None) if zone else dt.datetime.now()
    today = now.date()
    fm = frontmatter(read(vault, NOTE) or "")
    if not force and fm.get("date") == today.isoformat() and (fm.get("writer") == "box" or fm.get("opened") == "true"):
        print("Today's brief is already there.")
        return
    all_tasks = tasks(vault)
    evs = events(vault, today, zone)
    b = base(now, all_tasks, evs, jobs(vault), inbox(vault))
    w = weather(today)
    try:
        issues, newest = tldr_issues(today)
    except Exception as e:
        print("Bridge failed, no news today:", e, file=sys.stderr)
        issues, newest = {}, None
    news = pick_news(issues)
    if news and newest and newest < today:
        print(f"News from {newest:%A}'s issues.")
    reply, use = claude(os.environ.get("MODEL", "claude-sonnet-5-5"), SYSTEM, prompt(now, b, w, all_tasks, news), 4000)
    text = render(now, b, w, reply, news)
    if dry:
        print(text)
        return
    # The app may have written one while this ran: only replace it if Tom hasn't opened it yet.
    fm = frontmatter(read(vault, NOTE) or "")
    if not force and fm.get("date") == today.isoformat() and fm.get("opened") == "true":
        print("Tom has opened today's brief already; leaving it.")
        return
    write(vault, text)
    print(f"Wrote {NOTE}: {len(news)} news picks, {use.get('output_tokens', 0)} tokens out.")


if __name__ == "__main__":
    main()
