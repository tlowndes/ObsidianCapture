# Daily Brief (Oracle box)

`brief.py` writes `99 - Meta/Daily Brief.md` in the vault at 06:15: the morning email as a page Flight Deck shows on
the Deck, in Hangar and on Windows, plus the spoken version the app reads out (Full, about 5–6 minutes, or Short).
Obsidian Sync carries it to the phone and PC.

- **The news**: the newest TLDR Design, AI, Tech and InfoSec issues from the last 3 days (so weekends get Friday's),
  read through Proton Mail Bridge, read-only (`EXAMINE` and `BODY.PEEK`). Claude Haiku picks 3 stories from each.
- **The rest** comes from the vault, read-only: tasks (Quick Task, and `#task` lines elsewhere), job applications,
  the Proton calendars listed in `99 - Meta/capture-config.json`, app events in `99 - Meta/Events.md`, the inbox
  digest in `99 - Meta/Email.md`, and the weather from Open-Meteo. Claude Sonnet writes the opening, Three things,
  hard stop and both spoken versions.
- **The app is the backup**: if there's no brief for today by 06:45, the phone or PC writes its own (without the news).
  If the box runs late, it only replaces the app's version when Tom hasn't opened it yet.
- The only file it writes is `99 - Meta/Daily Brief.md`.

## Files on the box

```
~/brief/brief.py   this script
~/brief/.env       chmod 600
```

`.env`:

```
ANTHROPIC_API_KEY=...
VAULT=/home/ubuntu/vault
LAT=53.48
LON=-2.24
BRIDGE_USER=...            # same as ~/mailbox/.env
BRIDGE_PASS=...            # the Bridge password, not the Proton one
```

Optional: `BRIDGE_HOST` (127.0.0.1), `BRIDGE_PORT` (1143), `NEWS_FOLDER` (`INBOX`), `NEWS_FROM`
(`tldrnewsletter.com`), `MODEL` (`claude-sonnet-5-5`), `NEWS_MODEL` (`claude-haiku-5-5`), `TZ_NAME` (`Europe/London`).

Try it: `python3 ~/brief/brief.py --dry-run` prints the note without saving. `--force` writes it again.

## systemd (user units)

`~/.config/systemd/user/brief.service`

```
[Unit]
Description=Flight Deck Daily Brief
After=network-online.target

[Service]
Type=oneshot
ExecStart=/usr/bin/python3 %h/brief/brief.py
```

`~/.config/systemd/user/brief.timer`

```
[Unit]
Description=Flight Deck Daily Brief at 06:15

[Timer]
OnCalendar=*-*-* 06:15:00 Europe/London
Persistent=true

[Install]
WantedBy=timers.target
```

```
systemctl --user daemon-reload
systemctl --user enable --now brief.timer
loginctl enable-linger $USER      # so it runs without a login
```

Cost: about 1p a day (one Sonnet call, four small Haiku calls).
