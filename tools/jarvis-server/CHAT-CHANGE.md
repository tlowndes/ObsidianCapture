# Chat memory: change for your own /voice server

Paste this to Claude on the Oracle box, in the folder of your server.

---

The Flight Deck app now shows Jarvis as a chat, and wants Claude to remember the conversation. Please add this to
the Jarvis server, keeping the bearer-token check on every route:

1. `POST /voice` (multipart) and `POST /text` (JSON) take a new optional field `chat`: a UUID the app makes for
   each conversation. A new chat (after the app's "New chat") comes with a new id.
2. When `chat` is there, run the Claude CLI in that chat's session:
   - the first time you see the id: `claude -p "<text>" --session-id <chat> ...`
   - every time after: `claude -p "<text>" --resume <chat> ...`
   Keep the set of ids you've seen in a small file (for example `~/.config/jarvis-chats.json`) so it survives a
   restart. Run it in the worker's folder as now (each worker has its own chats). Don't use `--continue` when a
   chat id is given.
3. Without `chat`, behave as now.

Background workers (Research) and ramble sorting don't use the chat. Nothing changes in the reply.
See `ask_claude` in `tools/jarvis-server/jarvis_server.py` for a version that does this.
