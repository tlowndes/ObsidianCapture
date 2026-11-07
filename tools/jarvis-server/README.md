# Jarvis voice server

The other end of the app's Jarvis tab. Runs on the Oracle box, reachable only over Tailscale.

For each recording it turns speech into text (faster-whisper), runs `claude -p` in the picked worker's folder,
turns the reply into speech (Piper) and sends back `{"transcript", "reply", "audio_url"}`. Typed messages come in on
`POST /text` and get the same answer. A ramble (40 words or more that isn't a question) is sorted instead: the reply
carries `"sorted"` with tasks, ideas and a tidied note, which the app checks and saves to the vault. Research runs in the
background: it answers "On it" straight away, saves the write-up to `~/research/jarvis-results/`, and the app's
Done line shows it when it's finished.

## Setup (Ubuntu on the Oracle box, about 10 minutes)

1. Tools: `sudo apt install -y python3-venv ffmpeg`
2. Claude CLI, signed in: run `claude` once as this user and log in.
3. Copy this folder to `~/jarvis-server`, then:
   ```
   cd ~/jarvis-server
   python3 -m venv .venv && .venv/bin/pip install faster-whisper piper-tts
   mkdir -p voices && cd voices
   wget https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_GB/alan/medium/en_GB-alan-medium.onnx
   wget https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_GB/alan/medium/en_GB-alan-medium.onnx.json
   cd .. && cp workers.example.json workers.json
   ```
4. Token: `echo "JARVIS_TOKEN=$(openssl rand -hex 24)" > ~/.config/jarvis.env && chmod 600 ~/.config/jarvis.env`.
   Paste the same token into the app (Settings › Jarvis › Token). Never put it in the vault or the repo.
5. Service:
   ```
   mkdir -p ~/.config/systemd/user && cp jarvis.service ~/.config/systemd/user/
   systemctl --user enable --now jarvis && sudo loginctl enable-linger $USER
   ```
6. Tailscale HTTPS: `tailscale serve --bg 8765`. The app's address is the box's name, e.g. `oracle.tail1234.ts.net`.

Check it: `journalctl --user -u jarvis -f` shows what it heard and what each worker did.

## Workers

`workers.json` sets each worker's folder (`cwd`), instructions (`prompt`) and permissions. Point `obsidian` at a
copy of the vault on the box. `claude -p` can't ask before editing, so workers that need to change files get
`"permission_mode": "acceptEdits"`; `allowed_tools` narrows what a worker may use. `"background": true` makes
a worker report on the Done line instead of answering live. Add `--continue` to the service line if you want each
worker to carry on its last conversation.

## Voices

Every Piper `.onnx` voice (with its `.onnx.json`) in `voices/` shows in the app's voice picker, with a sample.
More UK voices: <https://huggingface.co/rhasspy/piper-voices/tree/main/en/en_GB> (for example `jenny_dioco`,
`alba`, `northern_english_male`, `southern_english_female`). `--piper-voice` is the default when the app sends none.

## Options

`--ramble-words` (default 40; 0 turns sorting off), `--whisper-model` (default `base.en`; `small.en` is more accurate but slower), `--claude-timeout` (seconds,
default 120), `--port` (default 8765). Without `--piper-voice` it still works, with text-only replies.
