# Lanterns: Shadows of the Corps (v1.14.0)
**v1.14:** the WILL METER, a personal stress bar for every innocent (Manhunters never have one). Low Will closes in the screen, plays phantom sounds and shadow glimpses, and stops sprinting. Separate from the ring. See "Will meter".
**v1.13:** custom task screens (8 minigames), task tracker HUD, different task mixes per player, more task types. `/lantern answer` removed.
**v1.12:** SHAPESHIFT for Manhunters (Blackout Core second mode, client-side disguise, Among Us style). See "Shapeshift".
**v1.11:** Manhunters see their allies with a red through-walls outline; nobody else can (packet-only, config `manhunterOutline`).
**v1.10:** Manhunters auto-scale with lobby size: 1 under 6 players, 2 for 6-10, 3 for 11+ (`autoScaleManhunters`).
**v1.9:** all ring visuals are private to the Lantern (custom dust helix/dome/ring effects); Last Light now lasts until the next meeting; blocked kills are a silent sensor (only the Lantern is told).
**v1.8:** TASKS + task-charged GUARDIAN LIGHT (see "Tasks" below).
**v1.7:** the Power Ring is now a 26-cuboid 3D model (gold signet, emerald bars, round gem), kept small in hand. Editable in Blockbench: `assets/lanterns/models/item/power_ring.json` + `textures/item/power_ring_3d.png`.
**v1.6 (smoothness):** boss-bar countdown for discussion/voting; ability feedback no longer flickers away; "Kill ready"/"Blackout ready" pings; Blackout Core shows a cooldown overlay; meeting lock is a soft radius (`meetingFreeRadius`, default 3) instead of rubber-banding; 3-second beat before vote results; kill particles.
**v1.5:** ring and Blackout Core always go in hotbar slot 9 (never auto-selected); `/lantern forcerole`; discussion 45s; old configs auto-migrate.
**v1.1:** ring only goes to the Lantern after the first meeting; 1 Manhunter by default; Manhunters punch to kill and use a Blackout Core item; role shown at start.
Fabric mod (Minecraft Java **1.21.1**, **Java 21**, Fabric Loader >= 0.16, Fabric API 0.102.0+1.21.1).
A social-deduction murder mystery: 2 hidden Manhunters, bodies, meetings, votes, and one secret Green Lantern ring.

## HONEST STATUS
- **No compiled JAR is included.** The environment this was written in had no network, so Gradle, Minecraft and Fabric
  could not be downloaded. The full source project is complete; build it with one of the options below.
- The Minecraft-facing code (`GameManager`, `LanternsMod`, `RingItem`) was written against Yarn 1.21.1 mappings
  but **has never been compiled or run in-game**. Expect to possibly fix a few small mapping/API mismatches on first build.
  Please treat it as a first full draft, not a play-tested release.

## Build the JAR
**A) Easiest, no local setup:** push this folder to a GitHub repo. The included workflow (`.github/workflows/build.yml`)
builds it; download the JAR from the Actions run's *Artifacts*.

**B) Locally:** install JDK 21 and Gradle 8.8+, then in this folder:
```
gradle wrapper --gradle-version 8.8     # once, creates gradlew
./gradlew build                          # Windows: gradlew.bat build
```
Output: `build/libs/lanterns-shadows-of-the-corps-1.0.0.jar` (ignore the `-sources` jar).
Dev client: `./gradlew runClient`.

## Install
Put the JAR and **Fabric API** in `mods/` on the server **and on every client** (the ring is a custom item, so vanilla clients can't join).

## Setup & play
1. Stand where meetings should happen: `/lantern setmeetingpoint` (OP).
2. `/lantern start` (OP; needs 6+ players, or `/lantern start force` with 3+ for testing).
3. At start everyone sees their role (big title, chat line, and a constant action-bar line; `/lantern role` shows it again).
   **Nobody has a ring at the start.** After the first meeting ends, ONE random living innocent receives the Power Ring (and is told privately).
   Ring: right-click = use, sneak + right-click = change mode. If the Lantern is voted out/disconnects, the ring item moves to the next bearer.
4. **Manhunters** punch a player to kill (cooldown), and get a **Blackout Core** item at start: right-click it for System Blackout (it only works for Manhunters). **Manhunters scale automatically:** 1 under 6 players, 2 for 6-10, 3 for 11 or more.
   To use a fixed number instead, set `autoScaleManhunters` to false and edit `manhunters` in `config/lanterns.json`.

## Commands
| Command | Who | Effect |
|---|---|---|
| `/lantern start [force]` | OP | Randomize roles, start |
| `/lantern stop` | OP | End game safely, restore game modes/rings |
| `/lantern status` | all | Public info only, no roles |
| `/lantern forcerole <manhunter\|innocent\|lantern\|clear> [player]` | OP | Guarantee a role (default: yourself) from the next start. Lantern = you get the ring after the first meeting. Cleared with `clear`; forgotten on server restart |
| `/lantern task add <scan\|puzzle\|calibrate>` | OP | Turn the block you are looking at into a task station (saved in config) |
| `/lantern task remove` / `list` / `clear` | OP | Remove the looked-at station / list all / delete all |
| `/lantern tasks` | players | Show your own tasks and team progress |
| `/lantern answer <n>` | players | Answer a puzzle (also clickable in chat) |
| `/lantern role` | players | Shows your own role again (private) |
| `/lantern meeting` | players | Emergency meeting (1 per player, grace + cooldown) |
| `/lantern report` | players | Report nearest body in range (or right-click the body) |
| `/lantern vote <player\|skip>` | players | Cast vote (also clickable in chat); one vote, locked |
| `/lantern setmeetingpoint` | OP | Save position + dimension |
| `/lantern reload` | OP | Reload `config/lanterns.json` |

(`vote` and `reload` are additions; there is deliberately no `/lantern investigate`.)

## Rules as implemented
- **Manhunters** (auto: 1 / 2 / 3 by lobby size, see above; always clamped to <= (n-1)/2): know each other. Abilities: *Instant Kill* (punch a player within 3.5 blocks, 60 s cooldown, 30 s opening cooldown)
  and *System Blackout* (right-click the Blackout Core) (10 s darkness on **everyone** incl. the activator, 90 s cooldown, ends when a meeting starts, doesn't bypass kill cooldown).
  Win: living Manhunters >= all living non-Manhunters, or killing the current Lantern.
- **Bodies:** a kill leaves an "Unidentified Body" and a hidden evidence record. Reporting shows the victim and approximate time only; no role, no killer.
- **Meetings:** all abilities off, living players teleported to the meeting point and held there, 45 s discussion (voting can't start before this), 30 s voting (ends early when everyone has voted).
  Most votes ejects; ties, or skips >= top votes, eject nobody. Ejected/dead players become spectators ("ghosts" with a private ghost chat).
  Players return to prior positions afterwards. Roles are **not** revealed on death (config `revealRoleOnDeath`); all roles are revealed at game end.
- **Green Lantern:** none until the first meeting ends; then a random living innocent gets the ring. If the bearer is voted out (or disconnects) the ring secretly
  passes to another random living innocent (never a Manhunter/dead player); ability state belongs to the ring, so it carries over. If killed -> Manhunters win instantly.
  - *Verdict of Will:* look at a player within 30 blocks, accurate private result, **1 charge per game**, no notification to target.
  - *Residual Will:* near a body, 45 s cooldown; gives up to 4 distinct inconclusive clues (rough age, blackout or not, a 3-name pool containing the killer, side the attacker stood on).
  - *Last Light:* look at a player (or at nobody = self) to shield them from one Manhunter kill until the next meeting starts (or until it blocks one). 1 use per round (refreshed when a meeting ends);
    self-shield only once per game, tracked separately. A blocked kill is SILENT for everyone except the Lantern: the protected player and the Manhunter see/hear nothing; the Lantern gets a private burst, sound and action-bar note. The Lantern also sees a faint ring on whoever is currently warded. Not usable in meetings; harmless to everyone.
- Participants are put in Adventure mode (no block breaking), take no damage, and don't starve. Game modes are restored on stop/disconnect/shutdown.
- Disconnecting players are removed from the game (ring transfers if they held it); rejoiners during a game spectate.


## Tasks and Guardian Light (v1.13: custom task screens)
**Setup (admins):** look at any block (a lectern, a button, a furnace...) and run `/lantern task add <type>`. Stations are saved in `config/lanterns.json`.
With **no stations** the game plays exactly as before (tasks off). `/lantern task list | remove | clear` manage them.

**Task types** (right-clicking a station opens a custom full-screen terminal; the server checks timing and distance):
| Type | Title | What you do |
|---|---|---|
| `wires` | Fix Wiring | Join the matching coloured wires (Among Us) |
| `keypad` | Unlock Manifolds | Press 1 to 10 in order on a shuffled pad (Among Us) |
| `shields` | Prime Shields | Switch off every red panel (Among Us) |
| `download` | Download Data | Start the transfer and wait for the bar (Among Us) |
| `lever` | Drain Reserves | Drag the lever all the way down (Among Us) |
| `timing` | Calibrate Distributor | Stop three moving markers inside the green zone (Among Us) |
| `focus` | Will Focus | Keep your cursor inside a drifting green ring (Green Lantern) |
| `spectrum` | Spectrum Sequence | Memorise and repeat 5 rounds of coloured lights (Green Lantern) |
| `calibrate` | Power Node | Plays like Will Focus; if the **Lantern** finishes it, Residual Will recharges (Verdict is never restored). Looks identical to everyone else |

(`scan` and `puzzle` from older configs still work: they open Download Data and Unlock Manifolds.)

**Finding your tasks:** a HUD in the top-left lists *your* tasks with a direction arrow and distance to each, plus your count and the team %. Stations also sparkle (privately) for players who still have a task there,
and `/lantern tasks` prints them in chat.

**Different mixes:** every player is dealt `tasksPerPlayer` (default 3) different stations; mixes are pairwise different whenever enough combinations exist, and less-used stations are preferred so all get visited.
With 6+ stations and 3 per player there are 20+ possible mixes. Aim for 8 to 12 stations spread far apart.

**Manhunters get fake tasks** (same screens, same HUD) that add nothing to team progress. A dead innocent's unfinished tasks drop out of the total.
**If every remaining innocent finishes all of their tasks, the Innocents win** (`tasksCompleteWins`, default true; false makes tasks only charge Guardian Light).

**Guardian Light (task-charged, hidden, automatic):** team progress crossing 25% / 50% / 75% (`guardianThresholds`) banks a charge (max 1 banked) on the ring.
If a Manhunter hits the **Lantern** while it is charged and unlocked, the kill fails silently (Last Light takes priority if active) and only the Lantern gets the private sensor alert.
**Nerf:** after it triggers it is locked until **two meetings have ended**. Charges earned while locked are banked but unusable until the lock expires.

## Will meter (v1.14)
A personal stress bar for **every living non-Manhunter** (the Lantern is included, as a person). **Manhunters never have a Will meter**: they get no bar, no effects and no packets about it.
**It is a separate system from the ring.** The ring's own charges (Verdict of Will, Residual Will, Last Light, Guardian Light) are never read, spent or recharged by the meter, and the meter is never changed by the ring. (The name overlaps with the ring's "Will" abilities only in wording; the HUD label is shown as `WILL` under your task list.)

- **Drain:** about 12 per minute while you are alone (100 to 0 in roughly 8 minutes). **Company halves it:** any living player within 6 blocks counts, whatever their role, so it never hints at who is a Manhunter. It pauses during meetings.
- **Restore:** +35 for each task you finish, +25 when a meeting ends.
- **Tiers:** Steady (60+), Shaken (below 60: the screen edges darken), Fraying (below 35: private phantom sounds every 10 to 18 s, sometimes a dark figure glimpsed a few blocks away), Broken (below 15: stronger, faster hallucinations, blood-dark pulsing vignette, and **no sprinting**).
- **Hallucinations are private and fake.** Sounds and figures are sent only to you and are not tied to any real player's position.
- **Needs task stations** (it is restored by tasks). With no stations the game plays exactly as before. Disable with `willEnabled: false`.
- Dead or ejected players lose the bar at once; everyone's food is reset at game end.
- Config: `willEnabled, willDrainPerMin, willTaskRestore, willMeetingRestore, willCompanyRadius, willCompanyDrainFactor, willHallucinations, willSprintLock`. All numbers are first guesses; playtest and tune.

## Shapeshift (v1.12, Among Us style)
The **Blackout Core** now has two modes. **Sneak + right-click** switches between *System Blackout* and *Shapeshift*; the action bar shows the current mode.
- **Shapeshift:** look at any living player within 30 blocks and right-click. For `shapeshiftDurationSec` (default 20) you look like them to **innocents and ghosts**: their skin,
  slim/wide model and nametag, and your chat messages (and "X found the body" / "X called a meeting") appear under their name. Right-click again to revert early. Cooldown (default 60s) starts when you change back.
- **Allies see through it** (like the red outline), so Manhunters never lose track of each other. Set `manhunterSeesDisguise` to true to see disguises yourself (useful for solo testing in F5 view).
- **You can still kill while shapeshifted** (witnesses will see "the other person" do it), and Blackout/Kill cooldowns are separate.
- **Evidence:** the transformation shows a purple shimmer and teleport sound to everyone nearby (`shapeshiftEvidence`), so a careful witness can notice it.
- **Ends automatically** when the timer runs out, when the person you copied dies, is ejected or leaves, when you die/leave, and **at the start of every meeting** (cooldown then pauses during the meeting like the others).
- **The Lantern can catch it:** Verdict of Will names the real player and says whose face they are wearing; Residual Will has a new clue ("The attacker wore a borrowed face" or "...their own face").
- **Technical note:** the disguise is rendered on each client, so every player needs the mod installed (already true for the ring item). The Blackout Core's grey cooldown overlay was removed because it would block mode switching.

## Config (`config/lanterns.json`)
`willEnabled, willDrainPerMin, willTaskRestore, willMeetingRestore, willCompanyRadius, willCompanyDrainFactor, willHallucinations, willSprintLock, shapeshiftDurationSec, shapeshiftCooldownSec, shapeshiftEvidence, manhunterSeesDisguise, manhunterOutline, autoScaleManhunters, manhunters, minPlayers, killCooldownSec, initialKillCooldownSec, postMeetingKillCooldownSec, killRange, blackoutDurationSec, blackoutCooldownSec,
residualCooldownSec, meetingFreeRadius, tasksPerPlayer, tasksCompleteWins, guardianThresholds, tasks, discussionSec, votingSec, emergencyMeetingsPerPlayer, meetingGraceSec, emergencyCooldownSec, reportRange,
revealRoleOnDeath, revealVotesPublicly, returnPlayersAfterMeeting, meetingPoint`. Verdict (1/game) and self-shield (1/game) are hard-coded and not configurable.

## Test report (truthful)
| What | Result |
|---|---|
| Pure rules in `GameLogic` (role count + auto-scaling/assignment, forced roles, win conditions, vote ties/skips/dead votes, ring candidate selection, guardian thresholds + 2-meeting lock, cooldown math, Will tiers/drain/restore/hallucination timing and "never for Manhunters") | **58/58 checks passed** (42 rules + 16 for the Will meter), compiled with `javac` 21 and run via `tools/LogicCheck.java` |
| Same scenarios as JUnit 5 (`src/test/java/.../GameLogicTest.java`) | Written; **not run** (no JUnit offline). Runs with `gradle test` |
| Gradle build / JAR | **Not run** (no network) |
| In-game behavior (kills, bodies, meetings, teleport, blackout, ring item, commands, disconnect handling) | **Not tested.** No Minecraft server was available |

Run the logic checks without Gradle:
```
javac -d /tmp/out src/main/java/com/lanterns/GameLogic.java tools/LogicCheck.java && java -cp /tmp/out LogicCheck
```

## Known limitations / suggested next steps
- No custom GUI for voting (clickable chat + commands). No player-head skins on bodies. Game state is not persisted across server restarts.
- Chat is open to the living at all times (only the dead are isolated). Clue and balance numbers are first guesses; playtest and tune the config.
- Item art is generated: the ring is a 3D model (32x32 texture), the Blackout Core is a flat 16x16 sprite. Replace the PNGs in assets/lanterns/textures/item/ with your own art any time.
