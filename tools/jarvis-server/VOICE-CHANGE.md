# Voice picker: change for your own /voice server

Paste this to Claude on the Oracle box, in the folder of your server.

---

The Flight Deck app now has a voice picker. Please add this to the Jarvis /voice server, keeping the bearer-token
check on every route:

1. `GET /voices` returns a JSON list of the voices the text-to-speech can use:
   `[{"id": "en_GB-alan-medium", "label": "Alan", "detail": "UK, male", "sample_url": "/voices/en_GB-alan-medium/sample.ogg"}]`
   `label` and `detail` are what the app shows. `sample_url` is optional.
2. `GET /voices/{id}/sample.ogg` returns a short OGG/Opus sample of that voice ("Hello Tom, I'm Jarvis.").
   Cache it after the first time.
3. `POST /voice` takes a new optional multipart form field, `voice` (one of the ids). Use that voice for the
   spoken reply. If it's missing or unknown, use the current default voice.

Nothing else changes: the recording is still the field `file`, the worker is `worker`, and the reply is still
`{"transcript", "reply", "audio_url"}`.
