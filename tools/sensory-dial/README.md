# Sensory Dial: the Home Assistant side

Flight Deck's Sensory Dial (in Hangar, under At the desk) sets one helper in Home Assistant,
`input_select.sensory_level`. This package does the rest: lights, brown noise and the phone's Do Not Disturb.

| Level | Lights | Brown noise | Phone |
| --- | --- | --- | --- |
| Normal | Put back as they were (10 s fade) | Off | Do Not Disturb off |
| Low | 40%, 2700 K | 25% | Priority only |
| Shelter | 10%, 2200 K | 45% | Total silence |

## Set up

1. Turn on packages, if you haven't: in `configuration.yaml`, under `homeassistant:`, add
   `packages: !include_dir_named packages`.
2. Copy `sensory_dial.yaml` into `packages/`.
3. Change the four `CHANGE ME` lines: your lights, the brown noise file, your phone's notify action, and the
   STYRBAR's IEEE address. On Zigbee2MQTT, delete the ZHA automation and uncomment the one under it.
4. Put a brown noise file in HA's media folder as `brown-noise.mp3`.
5. Restart HA. In Settings › Helpers, set **Sensory speaker** to your speaker's entity id, or leave it blank for no noise.
6. The phone needs the HA companion app with notifications on, and Do Not Disturb access allowed for it.
7. In Flight Deck: Hangar › Add a tool › Sensory Dial, then the HA address and a long-lived access token
   (HA profile › Security, at the bottom).

## The STYRBAR

Down is one level lower, up is one level higher, hold down jumps to Shelter, and left or right goes back to Normal.
To pair it, choose Add device in HA's Zigbee integration and press the pairing button inside the battery cover
4 times quickly.

## The log

HA keeps the level's history, and Flight Deck reads it (when Sensory Dial opens, and every 6 hours) into
`99 - Meta/Sensory log.md`: `- 2026-11-20 14:02 · Low · 12 min`. HA never writes to the vault. HA keeps 10 days
of history by default, so a phone that's been off for longer than that misses the oldest turns.
