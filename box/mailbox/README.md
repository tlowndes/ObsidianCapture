# Proton inbox digest (Oracle box)

`mailbox.py` reads **Starred** and the **Vault** label through Proton Mail Bridge, read-only, and writes
`99 - Meta/Email.md` in the vault for Flight Deck's Inbox card and page. Obsidian Sync carries it to the phone and PC.

- Read-only: folders are opened with `EXAMINE` and mail is fetched with `BODY.PEEK`, so nothing is marked read,
  moved or deleted. There's no SMTP, so it can't send.
- Only the sender, subject and first 1,500 characters go to Claude (Haiku). No attachments.
- Settings › Inbox in the app writes `99 - Meta/Email settings.md` (how often, what to pick out, Check now). The timer
  runs every 5 minutes and the script only does work when that says it's time.
- What Tom does with each email lives in `99 - Meta/Email state.md`, written by the app. The script never touches it.

## Files on the box

```
~/mailbox/mailbox.py      this script
~/mailbox/.env            chmod 600: BRIDGE_USER, BRIDGE_PASS, ANTHROPIC_API_KEY, VAULT
~/mailbox/seen.json       ids already read (kept 30 days)
~/mailbox/items.json      what's in the digest (kept 30 days)
~/mailbox/state.json      last run and this month's Claude cost
```

`BRIDGE_PASS` is the Bridge password from `protonmail-bridge --cli` then `info`, **not** the Proton password.
Optional: `BRIDGE_HOST` (127.0.0.1), `BRIDGE_PORT` (1143), `FOLDERS` (`Starred,Labels/Vault`), `MODEL` (`claude-haiku-5-5`).

## systemd (user units)

`~/.config/systemd/user/mailbox.service`

```
[Unit]
Description=Flight Deck inbox digest
After=network-online.target

[Service]
Type=oneshot
ExecStart=/usr/bin/python3 %h/mailbox/mailbox.py
```

`~/.config/systemd/user/mailbox.timer`

```
[Unit]
Description=Flight Deck inbox digest every 5 minutes

[Timer]
OnBootSec=2min
OnUnitActiveSec=5min

[Install]
WantedBy=timers.target
```

Then `systemctl --user daemon-reload && systemctl --user enable --now mailbox.timer` and
`loginctl enable-linger $USER` so it runs while logged out. Run once by hand with `python3 ~/mailbox/mailbox.py --now`.
Logs: `journalctl --user -u mailbox.service -n 50`.
