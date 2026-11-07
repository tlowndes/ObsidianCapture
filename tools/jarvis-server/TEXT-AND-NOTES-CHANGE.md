# Typing and rambles: change for your own /voice server

Paste this to Claude on the Oracle box, in the folder of your server.

---

The Flight Deck app now has Jarvis inside Capture: you can type to it, and a long ramble comes back sorted into
tasks, ideas and a tidied note for the app to check and save. Please add this to the Jarvis server, keeping the
bearer-token check on every route:

1. `POST /text` takes JSON `{"text": "...", "worker": "jarvis", "voice": "en_GB-alan-medium"}` (`voice` optional)
   and does what `POST /voice` does after speech-to-text: same workers, same reply
   `{"transcript", "reply", "audio_url"}`. Put the typed text in `transcript`.
2. On both `/voice` and `/text`: when the message is long (40 words or more), first ask Claude whether it is a
   ramble to keep or a question/request. If it is a ramble, skip the normal worker and add `"sorted"` to the reply:
   ```
   "sorted": {
     "tasks": [{"text": "Book MOT", "tag": "#home" or null, "due": "2026-11-20" or null}],
     "ideas": ["Plant-watering app"],
     "note": null, or "a short leftover line", or {"title": "Portfolio rethink", "markdown": "..."}
   }
   ```
   `reply` is then one or two spoken sentences saying what was found. The note markdown is the ramble tidied into
   one cohesive note: a one or two sentence summary, then `##` headings with short bullets, keeping Tom's points and
   wording, nothing invented, no top-level `#` heading. The app adds the raw transcript under the note itself.
3. Don't write anything to the vault for a ramble: the app shows Check and save and writes it from the phone.

The prompt that does step 2 is `SORT_PROMPT` in `tools/jarvis-server/jarvis_server.py`, with `parse_sorted` to read
the answer; copy them if that's easier.
