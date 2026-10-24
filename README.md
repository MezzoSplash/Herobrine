# Herobrine

A small Paper plugin for a harmless Herobrine prank (great for Halloween).

Every now and then Herobrine appears 30 to 50 blocks away from a survival player, near the edge of their view. Only that player can see him. When the player looks at him, gets too close, or after a minute, he vanishes with a puff of smoke and a cave sound.

By default no blocks are changed (world traces are opt in, see below), nobody takes damage, nothing is saved to the world and no resource pack is needed. He is a vanilla Mannequin entity (Minecraft 1.21.9+) with no AI, no collision and no gravity.

## Behaviour
- Every `check-interval-seconds` each eligible player (survival, alive, allowed world, not excluded) gets a roll with probability `chance`.
- At night or in dark spots (sky light < 4) the chance is multiplied by `night-or-cave-multiplier` (default 1.0, so no preference).
- Players can opt out with `/herobrine optout` (saved in `optout.yml`, survives restarts). Opted out players get no apparitions, sounds or traces.
- After an appearance a player gets a cooldown (`player-cooldown-seconds`).
- He vanishes when the player looks within `look-angle` degrees of him (with line of sight), comes closer than `vanish-distance`, or after `timeout-seconds`.

## Commands
Permission `herobrine.admin` (default: op), except `optout`.

| Command | What it does |
|---|---|
| `/herobrine spawn <player>` | Make him appear for a player now |
| `/herobrine spawnat <player> <x> <y> <z>` | Place him at an exact spot for a player |
| `/herobrine reload` | Reload config.yml |
| `/herobrine toggle` | Turn random appearances on/off |
| `/herobrine status` | Show state and active apparitions |
| `/herobrine clear` | Remove all active apparitions |
| `/herobrine sound <player> [footsteps\|door\|cave\|fake-join]` | Play a spooky sound for a player now |
| `/herobrine event [start [minutes]\|stop\|status]` | Start an event night (more rolls, higher chance) |
| `/herobrine traces list` | List all world traces with id and coordinates |
| `/herobrine traces undo <id>` | Restore the original blocks of one trace |
| `/herobrine traces undoall` | Restore all traces |
| `/herobrine traces place <player> [type]` | Test: place a trace near a player now (all safety checks still apply) |
| `/herobrine optout` | Any player (permission `herobrine.optout`, default true): opt out or back in |

## Config (defaults)
```yaml
# Herobrine prank. Only the targeted player sees him. No block changes, no damage.
enabled: true
# How often (seconds) every eligible player gets a roll.
check-interval-seconds: 600
# Chance per roll (0.0 to 1.0).
chance: 0.05
# Chance is multiplied by this at night or when the player is in a dark/cave spot (sky light < 4). 1.0 = no preference.
night-or-cave-multiplier: 1.0
# If true, Herobrine only appears at night or in caves.
only-night-or-cave: false
# Minimum seconds between two apparitions for the same player.
player-cooldown-seconds: 900
min-distance: 30
max-distance: 50
# He vanishes when the player comes closer than this.
vanish-distance: 15
# He vanishes when the player looks within this many degrees of him (with line of sight).
look-angle: 5.0
# Seconds before he vanishes on his own.
timeout-seconds: 60
# Spawn him this many degrees off the player's view direction (edge of field of view).
fov-offset-min: 45
fov-offset-max: 70
# Worlds where he may appear. Empty = all worlds.
worlds: []
# Player names that are never targeted.
excluded-players: []
# Skin: signed texture of Mojang account "_Herobrine" (classic Steve with white eyes).
# Set skin-name to another Mojang username to use that skin instead (resolved online).
skin-name: ''
# Log every roll to the console (for testing).
debug: false
skin-value: '...'      # signed texture, see below
skin-signature: '...'
```

The full default config with all 1.1 options (sounds, halloween, event, traces) is in [`src/main/resources/config.yml`](src/main/resources/config.yml).

## New in 1.1
- **Sounds** (`sounds.*`): footsteps sneaking up behind you, a door opening and closing, cave ambience, and a fake yellow "Herobrine joined the game" (and left) in that one player's chat. Every sound has its own toggle.
- **Halloween mode** (`halloween.*`): between `start` and `end` (default 10-25 to 11-01) all chances are multiplied by `chance-multiplier` (2.0).
- **Event night** (`event.*`): `/herobrine event` runs extra rolls every `check-interval-seconds` with `chance-multiplier` for `duration-minutes`. Optional broadcast, night and thunder (off by default).
- **Per player opt out**: `/herobrine optout`.
- **World traces** (`traces.*`, **off by default**): 2x2 tunnels, leafless trees, small sand pyramids and torch messages. These DO change blocks, so:
  - each type has its own toggle, and `traces.enabled: false` disables all of them;
  - traces only go far from players (`min-distance-from-players`), spawn (`min-distance-from-spawn`), in chunks players rarely visit (`max-chunk-inhabited-minutes`) and with no player made blocks (`player-block-types`) within `protection-radius`;
  - every trace is logged to the console and `plugins/Herobrine/traces.log`, and the original blocks are stored in `traces.yml`, so `/herobrine traces undo <id>` or `undoall` puts everything back exactly;
  - claim plugins (WorldGuard, GriefPrevention) are not checked; the distance and block checks are the protection.

Updating from 1.0: your existing `config.yml` keeps working. Missing new keys use the defaults above (traces stay off). Copy the new sections from the jar's config if you want to tweak them.

## Skin
The default skin is the signed texture of the Mojang account `_Herobrine` (classic Steve with white eyes), embedded in the config. Set `skin-name` to any Mojang username to use that skin instead (looked up online).

## Compatibility
- Tested on **Paper 26.3** (Java 25).
- Purpur and other Paper forks for 26.3: not tested. Folia: not supported.
- Server side only. Tested with Geyser/Floodgate on the server; Bedrock players receive the Herobrine skin data (verified at network level).

## Install
1. Put `Herobrine-1.1.0.jar` in `plugins/`.
2. Restart the server.
3. Tweak `plugins/Herobrine/config.yml` and run `/herobrine reload`.

## Build
`gradle build` (jar in `build/libs/`).

## License
MIT, see [LICENSE](LICENSE). Not affiliated with Mojang or Microsoft.
