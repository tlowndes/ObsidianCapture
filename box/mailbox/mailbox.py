#!/usr/bin/env python3
"""Proton inbox digest for Flight Deck (proton-inbox-v1). Runs on the Oracle box every 5 minutes from a systemd timer.

Reads Starred and the Vault label through Proton Mail Bridge, READ-ONLY (IMAP EXAMINE and BODY.PEEK), asks
Claude Fast what each new email needs, and writes "99 - Meta/Email.md" in the vault. Senders Tom hid in Settings > Inbox
are skipped. The one thing it sends: calendar invites Tom asked for ("99 - Meta/Inbox/calendar-out.md"), emailed through
Bridge SMTP to his own address only, so Proton Mail shows Yes / Maybe / No and Yes adds it to Proton Calendar. Obsidian Sync carries it to the
phone and PC. It checks only as often as Settings > Inbox says ("99 - Meta/Email settings.md"), or straight away after
"Check now". Standard library only.

Settings come from ~/mailbox/.env (chmod 600): BRIDGE_USER, BRIDGE_PASS (the Bridge password, not your Proton one),
ANTHROPIC_API_KEY, VAULT (the vault folder). Optional: BRIDGE_HOST, BRIDGE_PORT, BRIDGE_SMTP_PORT (1025), FOLDERS, MODEL,
CAL_TO (where invites go; default BRIDGE_USER), CAL_ORGANIZER (who the invite is from; default CAL_TO).
"""
import datetime as dt
import email
import email.header
import email.utils
import imaplib
import json
import os
import re
import smtplib
import ssl
import uuid
from email.mime.multipart import MIMEMultipart
from email.mime.text import MIMEText
import sys
import tempfile
import urllib.request
from pathlib import Path

HOME = Path(os.environ.get("MAILBOX_HOME", Path.home() / "mailbox"))
DIGEST = "99 - Meta/Email.md"
OLD_DIGEST = "01 - Inbox/Email.md"  # where it lived before 1.38; removed after the first write here
SETTINGS = "99 - Meta/Email settings.md"
CALENDAR = "99 - Meta/Inbox/calendar-out.md"
KEEP_DAYS = 30
LOOK_BACK_DAYS = 14
BODY_CHARS = 1500
PRICE = {"in": 1.0 / 1e6, "out": 5.0 / 1e6}  # USD per token, Claude Haiku
EMOJI = {"reply": "↩", "bill": "£", "job": "💼", "delivery": "📦", "receipt": "🧾", "read": "📄"}


def env():
    f = HOME / ".env"
    if f.exists():
        for line in f.read_text().splitlines():
            if "=" in line and not line.strip().startswith("#"):
                k, v = line.split("=", 1)
                os.environ.setdefault(k.strip(), v.strip().strip('"'))
    need = ["BRIDGE_USER", "BRIDGE_PASS", "ANTHROPIC_API_KEY", "VAULT"]
    missing = [k for k in need if not os.environ.get(k)]
    if missing:
        sys.exit("Missing in .env: " + ", ".join(missing))


def load(name, default):
    p = HOME / name
    try:
        return json.loads(p.read_text())
    except Exception:
        return default


def save(name, data):
    p = HOME / name
    p.write_text(json.dumps(data, indent=1, ensure_ascii=False))


def frontmatter(path):
    try:
        text = path.read_text(encoding="utf-8")
    except FileNotFoundError:
        return {}
    m = re.match(r"^---\n(.*?)\n---", text.replace("\r", ""), re.S)
    out = {}
    for line in (m.group(1).splitlines() if m else []):
        if ":" in line:
            k, v = line.split(":", 1)
            out[k.strip()] = v.strip().strip('"')
    return out


def sender_key(name):
    return re.sub(r"\s+", " ", (name or "").split(" <")[0].strip().strip('"').lower())


def hidden_senders(path):
    """Settings > Inbox > Hidden: {sender: alerts_only}. Lines under '## Hidden senders' like '- Indeed · everything · 2026-11-16'."""
    try:
        lines = path.read_text(encoding="utf-8").replace("\r", "").splitlines()
    except FileNotFoundError:
        return {}
    out, on = {}, False
    for line in lines:
        if line.startswith("## "):
            on = line.strip().lower() == "## hidden senders"
        elif on and line.strip().startswith("- "):
            parts = [p.strip() for p in line.strip()[2:].split(" · ")]
            if parts[0]:
                out[sender_key(parts[0])] = len(parts) > 1 and parts[1].lower() == "alerts"
    return out


def is_hidden(hide, sender, kind=None):
    """True when Tom hid this sender: everything, or only alerts (kind known and not a real reply)."""
    k = sender_key(sender)
    if k not in hide:
        return False
    return not hide[k] or (kind is not None and kind != "reply")


def due(settings, state, now):
    """True when it's time to check: the interval has passed, or Check now was pressed since the last run."""
    last = state.get("last_run")
    if not last:
        return True
    last = dt.datetime.fromisoformat(last)
    every = max(10, min(240, int(settings.get("every", "30") or 30)))
    check_now = settings.get("check_now")
    if check_now:
        try:
            if dt.datetime.fromisoformat(check_now[:16]) > last:
                return True
        except ValueError:
            pass
    return now - last >= dt.timedelta(minutes=every - 1)


def text_of(msg):
    """The plain text of an email (HTML stripped when there's no plain part)."""
    plain, html = [], []
    for part in msg.walk() if msg.is_multipart() else [msg]:
        if part.get_content_maintype() == "multipart" or part.get_filename():
            continue
        try:
            body = part.get_payload(decode=True).decode(part.get_content_charset() or "utf-8", "replace")
        except Exception:
            continue
        (plain if part.get_content_type() == "text/plain" else html if part.get_content_type() == "text/html" else []).append(body)
    out = "\n".join(plain) or re.sub(r"<[^>]+>", " ", re.sub(r"(?is)<(style|script).*?</\1>", " ", "\n".join(html)))
    return re.sub(r"\s+", " ", out).strip()[:BODY_CHARS]


def words(h):
    return str(email.header.make_header(email.header.decode_header(h or ""))).strip()


def fetch(folders):
    """New emails from the folders, read-only. Yields dicts with id, from, subject, date and text."""
    host = os.environ.get("BRIDGE_HOST", "127.0.0.1")
    port = int(os.environ.get("BRIDGE_PORT", "1143"))
    ctx = ssl.create_default_context()
    if host in ("127.0.0.1", "localhost"):  # Bridge uses its own certificate on localhost only
        ctx.check_hostname = False
        ctx.verify_mode = ssl.CERT_NONE
    imap = imaplib.IMAP4(host, port)
    imap.starttls(ctx)
    imap.login(os.environ["BRIDGE_USER"], os.environ["BRIDGE_PASS"])
    since = (dt.date.today() - dt.timedelta(days=LOOK_BACK_DAYS)).strftime("%d-%b-%Y")
    try:
        for folder in folders:
            ok, _ = imap.select('"%s"' % folder, readonly=True)  # EXAMINE: can't mark read, move or delete
            if ok != "OK":
                print("No folder", folder, file=sys.stderr)
                continue
            ok, data = imap.uid("SEARCH", None, "SINCE", since)
            for uid in (data[0].split() if ok == "OK" and data and data[0] else []):
                ok, parts = imap.uid("FETCH", uid, "(BODY.PEEK[])")
                raw = next((p[1] for p in parts if isinstance(p, tuple)), None) if ok == "OK" else None
                if not raw:
                    continue
                msg = email.message_from_bytes(raw)
                mid = (msg.get("Message-ID") or "%s-%s" % (folder, uid.decode())).strip("<> ")
                name, addr = email.utils.parseaddr(words(msg.get("From")))
                try:
                    when = email.utils.parsedate_to_datetime(msg.get("Date")).astimezone().replace(tzinfo=None)
                except Exception:
                    when = dt.datetime.now()
                yield {"id": re.sub(r"[^A-Za-z0-9._@-]", "", mid)[:80], "from": name or addr, "addr": addr,
                       "subject": words(msg.get("Subject")), "date": when.isoformat(timespec="minutes"), "text": text_of(msg)}
    finally:
        try:
            imap.logout()
        except Exception:
            pass


def system(today):
    return f"""You sort emails for Tom's to-do app. Today is {today:%A %d %B %Y}. Reply with JSON only, no prose and no code fences:
{{"kind": "reply"|"bill"|"job"|"delivery"|"receipt"|"read"|"skip", "gist": string, "summary": string, "task": string or null, "due": "YYYY-MM-DD" or null,
"bill": string or null, "amount": "12.34" or null, "bill_due": "YYYY-MM-DD" or null, "repeat": "monthly"|"yearly"|"weekly"|"once" or null,
"company": string or null, "job_status": "Applied"|"Interview"|"Offer"|"Rejected" or null, "shop": string or null, "window": "HH:MM-HH:MM" or null}}
- reply: a real person waiting on Tom. bill: money to pay or a renewal (bill = payee, amount = the amount to pay, even a minimum
  payment on a statement, bill_due = pay-by date, repeat = how often it comes, e.g. monthly for a statement or direct debit).
- job: a recruiter or employer about a role (company; job_status when it's clear, e.g. Interview for a call or interview invite).
- delivery: a parcel on its way (due = the delivery day, window = the delivery slot if given). receipt: a purchase already paid (shop, amount).
- read: worth knowing, nothing to do. skip: newsletters, promos, notifications.
- gist: under 12 words, what it's about (e.g. "Asks if you're free Saturday for Mum's birthday lunch").
- summary: 1-3 short sentences with times, dates and asks. task: a short to-do when Tom needs to act, else null. UK English."""


def ask(mail, today):
    body = json.dumps({
        "model": os.environ.get("MODEL", "claude-haiku-5-5"), "max_tokens": 500, "system": system(today),
        "messages": [{"role": "user", "content": f"From: {mail['from']} <{mail['addr']}>\nSubject: {mail['subject']}\nDate: {mail['date']}\n\n{mail['text']}"}],
    }).encode()
    req = urllib.request.Request("https://api.anthropic.com/v1/messages", body, {
        "x-api-key": os.environ["ANTHROPIC_API_KEY"], "anthropic-version": "2023-06-01", "content-type": "application/json"})
    with urllib.request.urlopen(req, timeout=60) as r:
        out = json.loads(r.read())
    reply = "".join(b.get("text", "") for b in out.get("content", []))
    use = out.get("usage", {})
    cost = use.get("input_tokens", 0) * PRICE["in"] + use.get("output_tokens", 0) * PRICE["out"]
    m = re.search(r"\{.*\}", reply, re.S)
    return (json.loads(m.group(0)) if m else {"kind": "read"}), cost


def line(item):
    tag = {k: v for k, v in item.items() if v not in (None, "") and k in
           ("id", "kind", "from", "subject", "date", "summary", "gist", "task", "due", "bill", "amount", "bill_due", "repeat", "company",
            "job_status", "shop", "window")}
    return "- %s **%s** · %s <!--mail:%s-->" % (EMOJI.get(item["kind"], "📄"), item["from"], item.get("gist") or item["subject"],
                                              json.dumps(tag, ensure_ascii=False, separators=(",", ":")))


def write_digest(vault, items, read, skipped, cost, now):
    path = vault / DIGEST
    path.parent.mkdir(parents=True, exist_ok=True)
    groups = [("Needs you", ("reply", "bill", "job")), ("Deliveries", ("delivery",)), ("Receipts", ("receipt",)), ("Worth knowing", ("read",))]
    out = ["---", "type: email-digest", f"checked: {now.isoformat(timespec='minutes')}", f"read: {read}", f"skipped: {skipped}",
           f"cost_month: {cost:.2f}", "---", "", "Written by the Oracle box from Starred and the Vault label (read-only). Don't edit; Flight Deck keeps what you did in [[Email state]].", ""]
    for title, kinds in groups:
        rows = sorted((i for i in items if i["kind"] in kinds), key=lambda i: i["date"], reverse=True)
        if rows:
            out += [f"## {title}", ""] + [line(i) for i in rows] + [""]
    fd, tmp = tempfile.mkstemp(dir=path.parent, suffix=".tmp")
    with os.fdopen(fd, "w", encoding="utf-8") as f:
        f.write("\n".join(out))
    os.replace(tmp, path)  # one write, so Sync never sees half a note
    old = vault / OLD_DIGEST
    if old.exists() and "type: email-digest" in old.read_text(encoding="utf-8", errors="replace")[:200]:
        old.unlink()  # only our own old digest, never a note Tom wrote


CAL_TAG = re.compile(r"<!--cal:(\{.*\})-->")


def ics(ev, organizer, to, now):
    """A one-event iCalendar invite (all day, or timed when start/end are given), in UK time."""
    day = ev["date"].replace("-", "")
    if ev.get("start") and ev.get("end"):
        when = ["DTSTART;TZID=Europe/London:%sT%s00" % (day, ev["start"].replace(":", "")),
                "DTEND;TZID=Europe/London:%sT%s00" % (day, ev["end"].replace(":", ""))]
    else:
        nxt = (dt.date.fromisoformat(ev["date"]) + dt.timedelta(days=1)).strftime("%Y%m%d")
        when = ["DTSTART;VALUE=DATE:" + day, "DTEND;VALUE=DATE:" + nxt]
    title = ev["title"].replace("\\", "\\\\").replace(",", "\\,").replace(";", "\\;")
    return "\r\n".join([
        "BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//Flight Deck//Inbox//EN", "METHOD:REQUEST", "BEGIN:VEVENT",
        "UID:flightdeck-%s@flightdeck" % re.sub(r"[^A-Za-z0-9._-]", "", ev["id"])[:60],
        "DTSTAMP:" + now.astimezone(dt.timezone.utc).strftime("%Y%m%dT%H%M%SZ"), *when, "SUMMARY:" + title,
        "DESCRIPTION:Added from the Flight Deck inbox.", "ORGANIZER;CN=Flight Deck:mailto:" + organizer,
        "ATTENDEE;CN=Tom;RSVP=TRUE;PARTSTAT=NEEDS-ACTION;ROLE=REQ-PARTICIPANT:mailto:" + to,
        "SEQUENCE:0", "STATUS:CONFIRMED", "TRANSP:TRANSPARENT", "END:VEVENT", "END:VCALENDAR", ""])


def send_invites(vault, now):
    """Emails Tom an invite for each unticked line in calendar-out.md, then ticks it. Only ever to CAL_TO."""
    path = vault / CALENDAR
    if not path.exists():
        return
    lines = path.read_text(encoding="utf-8").replace("\r", "").split("\n")
    todo = [(i, json.loads(m.group(1))) for i, l in enumerate(lines) if l.strip().startswith("- [ ]") and (m := CAL_TAG.search(l))]
    if not todo:
        return
    to = os.environ.get("CAL_TO") or os.environ["BRIDGE_USER"]
    organizer = os.environ.get("CAL_ORGANIZER") or to
    host = os.environ.get("BRIDGE_HOST", "127.0.0.1")
    ctx = ssl.create_default_context()
    if host in ("127.0.0.1", "localhost"):
        ctx.check_hostname = False
        ctx.verify_mode = ssl.CERT_NONE
    with smtplib.SMTP(host, int(os.environ.get("BRIDGE_SMTP_PORT", "1025")), timeout=60) as smtp:
        smtp.starttls(context=ctx)
        smtp.login(os.environ["BRIDGE_USER"], os.environ["BRIDGE_PASS"])
        for i, ev in todo:
            msg = MIMEMultipart("mixed")
            msg["Subject"] = "📅 " + ev["title"]
            msg["From"] = "Flight Deck <%s>" % os.environ["BRIDGE_USER"]
            msg["To"] = to
            msg["Message-ID"] = "<%s@flightdeck>" % uuid.uuid4()
            alt = MIMEMultipart("alternative")
            alt.attach(MIMEText("%s on %s. Tap Yes to add it to Proton Calendar." % (ev["title"], ev["date"]), "plain", "utf-8"))
            cal = MIMEText(ics(ev, organizer, to, now), "calendar", "utf-8")
            cal.set_param("method", "REQUEST")
            alt.attach(cal)
            msg.attach(alt)
            smtp.sendmail(os.environ["BRIDGE_USER"], [to], msg.as_string())
            lines[i] = lines[i].replace("- [ ]", "- [x]", 1)
            print("Sent invite:", ev["title"])
    fd, tmp = tempfile.mkstemp(dir=path.parent, suffix=".tmp")
    with os.fdopen(fd, "w", encoding="utf-8") as f:
        f.write("\n".join(lines))
    os.replace(tmp, path)


def main():
    env()
    HOME.mkdir(parents=True, exist_ok=True)
    vault = Path(os.environ["VAULT"]).expanduser()
    now = dt.datetime.now().replace(second=0, microsecond=0)
    settings = frontmatter(vault / SETTINGS)
    state = load("state.json", {})
    try:
        send_invites(vault, now)  # every run, so an invite lands within minutes of the tap
    except Exception as e:
        print("Calendar invites failed:", e, file=sys.stderr)
    if "--now" not in sys.argv and not due(settings, state, now):
        return
    wanted = {"reply": settings.get("replies", "true") != "false", "bill": settings.get("bills", "true") != "false",
              "job": settings.get("jobs", "true") != "false", "delivery": settings.get("deliveries", "true") != "false",
              "receipt": True, "read": True}
    seen = load("seen.json", {})
    items = load("items.json", [])
    month = now.strftime("%Y-%m")
    if state.get("month") != month:
        state.update(month=month, cost=0.0)
    folders = [f.strip() for f in os.environ.get("FOLDERS", "Starred,Labels/Vault").split(",") if f.strip()]
    new = 0
    hide = hidden_senders(vault / SETTINGS)
    for mail in fetch(folders):
        if mail["id"] in seen:
            continue
        if is_hidden(hide, mail["from"]):  # hidden for everything: don't spend Claude on it
            seen[mail["id"]] = {"at": now.isoformat(), "kind": "hidden"}
            continue
        try:
            got, cost = ask(mail, now.date())
        except Exception as e:  # try again next time
            print("Claude failed for", mail["subject"], e, file=sys.stderr)
            continue
        state["cost"] = state.get("cost", 0.0) + cost
        kind = got.get("kind", "read")
        seen[mail["id"]] = {"at": now.isoformat(), "kind": kind}
        new += 1
        if kind == "skip" or not wanted.get(kind, True):
            continue
        if is_hidden(hide, mail["from"], kind):
            seen[mail["id"]]["kind"] = "hidden"
            continue
        items.append({**{k: got.get(k) for k in ("gist", "summary", "task", "due", "bill", "amount", "bill_due", "repeat", "company",
                                                 "job_status", "shop", "window")},
                      "id": mail["id"], "kind": kind, "from": mail["from"], "subject": mail["subject"], "date": mail["date"]})
    cutoff = (now - dt.timedelta(days=KEEP_DAYS)).isoformat()
    items = [i for i in items if i["date"] >= cutoff and not is_hidden(hide, i["from"], i["kind"])]
    seen = {k: v for k, v in seen.items() if v["at"] >= cutoff}
    skipped = sum(1 for v in seen.values() if v["kind"] == "skip")
    write_digest(vault, items, len(seen), skipped, state["cost"] * 0.79, now)  # USD to GBP, roughly
    state["last_run"] = now.isoformat()
    save("items.json", items)
    save("seen.json", seen)
    save("state.json", state)
    print(f"{now:%H:%M} checked, {new} new, {len(items)} kept")


if __name__ == "__main__":
    main()
