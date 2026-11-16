# Shared shopping and chores (Oracle box)

`shared.py` serves one small page for someone who doesn't use Obsidian or Flight Deck. They add and tick shopping and
mark chores done; Obsidian Sync carries it to Tom's phone and PC.

- It can only read and write `01 - Inbox/Shopping list.md` and `99 - Meta/Chores.md`, plus
  `99 - Meta/Shared list.md` (so the app shows the "👥 Shared" pill). Nothing else in the vault is read.
- Items added on the page end in ` 👥`, and chores done there get `· by partner`, so the app can say who.
- The page lives under a secret token: `https://<box>.<tailnet>.ts.net/<TOKEN>/`. Anything else is a 404.
  No access log is kept.
- Python standard library only.

## Files on the box

```
~/shared/shared.py   this script
~/shared/.env        chmod 600: VAULT, TOKEN. Optional PORT (8787), HOST (127.0.0.1), PARTNER (partner), OWNER (Tom)
```

Make a token with `python3 -c 'import secrets; print(secrets.token_urlsafe(24))'`.

## systemd (user unit)

`~/.config/systemd/user/shared.service`

```
[Unit]
Description=Flight Deck shared list
After=network-online.target

[Service]
ExecStart=/usr/bin/python3 %h/shared/shared.py
Restart=on-failure

[Install]
WantedBy=default.target
```

Then `systemctl --user daemon-reload && systemctl --user enable --now shared.service`
(`loginctl enable-linger $USER` if it isn't on already).

## Reaching it from their phone

They don't need Tailscale. Tailscale Funnel gives the box a public HTTPS address that only forwards to this page:

```
sudo tailscale funnel --bg 8787
tailscale funnel status
```

Send them `https://<box>.<tailnet>.ts.net/<TOKEN>/` and have them "Add to Home Screen". To stop sharing:
`sudo tailscale funnel --https=443 off`, or change `TOKEN` and restart to cut off the old link.

Logs: `journalctl --user -u shared.service -n 50`.
