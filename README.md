# GoKart — Paper 26.3

*By Redstoneskew*

## Download

Grab **`GoKart-1.0.0.jar`** from the [**Releases**](../../releases/latest) page and put it in your server's `plugins/` folder (Paper 26.3, Java 25). That's it.

Every push to `main` builds a fresh jar and publishes it there automatically.

Challenge another player to a go-kart race. Each of you gets a detailed kart with a little driver wearing your skin, a **Kart Controller** to drive it, and a chase camera that follows your kart.

## Building the jar

You need **Java 25** (Paper 26.3 requires it; Gradle downloads one automatically if you don't have it).

```
./gradlew build          # Windows: gradlew.bat build
```

The plugin is `build/libs/GoKart-1.0.0.jar`. Drop it in `plugins/` and restart. No Java setup? Push this folder to GitHub and the included workflow builds the jar (Actions → latest run → Artifacts).

## Racing

1. Stand near your opponent and type `/kart duel <player>`.
2. They click **[Accept]** in chat (or `/kart accept`). Chat closes and a 3-2-1 countdown starts.
3. Your view switches to a chase camera behind your kart, and a Kart Controller goes in your hand:

| Key | Does |
|---|---|
| **W** | Gas |
| **S** | Brake, then reverse |
| **A / D** | Steer |
| **SPACE** | Boost (recharges) |
| **SNEAK** | Handbrake (sharper turns) |

4. First kart across the checkered finish line (40 blocks ahead of where the challenger was looking) wins. Afterwards you're put back exactly where you were, in your normal game mode.

`/kart cancel` backs out of a challenge or forfeits. Leaving or dying mid-race forfeits too.

## Trophy cups

Win a race and you get a **trophy cup**. Every **5 wins** it upgrades:

| Wins | Trophy |
|---|---|
| 1–4 | Copper Cup |
| 5–9 | Iron Cup |
| 10–14 | Gold Cup |
| 15–19 | Emerald Cup |
| 20–24 | Diamond Cup (shiny) |
| 25–29 | Netherite Cup (with a gold star on top) |
| 30+ | Champion Cup (with a glowing diamond star) |

You only ever have one cup — when you win, the one in your inventory (or on display) upgrades itself. Lose it? Your next win gives you a fresh one at your current tier.

**Put it on display:** right-click a block with the cup and it becomes a 3D trophy — plinth with a name plaque, stem, bowl, handles, and a floating label with your name and win count. Right-click it to see the stats; **sneak + right-click** to pick it back up. Displayed trophies upgrade too.

**Looks like a trophy in your inventory too.** GoKart includes a small resource pack that draws every cup as a 3D trophy (plinth, name plate, stem, bowl, handles, and a star on the top two tiers) in your inventory, hand, item frames and on the ground. The plugin hosts it and offers it to players when they join.

**On MineKeep:** already set up for the **DominionTesting** server — the pack is served from `https://dominiontesting.minekeep.dev:2053`. For a different MineKeep server, change `resource-pack.minekeep-server-name` in `plugins/GoKart/config.yml`. The pack is served on port **2053** (MineKeep allows 2053, 2083, 2087, 2096 — change `resource-pack.port` if something else already uses 2053). Other hosts: set `public-base-url` to the public address of that port, or upload `plugins/GoKart/GoKart-pack.zip` somewhere and set `url`. Anyone who declines the pack sees the tier's ingot/gem instead.

Forfeit wins don't count. `/kart wins [player]` shows anyone's wins and tier, `/kart top` shows the top 10. Wins are saved in `plugins/GoKart/wins.yml`.

## Rubber & Racing Tires

- **Rubber:** smelt **Sugar Cane** in a furnace → 1 Rubber.
- **Racing Tires:** **8 Rubber** in a ring on a crafting table (every slot but the middle).
- Carry a set when a race starts and your kart gets black rubber tires with chrome rims: +15% top speed, +20% acceleration, much better grip. One set wears out per race (configurable). Without them you race on cobblestone wheels.

## The karts

Built from ~75 display pieces: chrome tube frame, team-colored nose cone and side pods (red #1 for the challenger, blue #2 for the opponent), bucket seat, engine with cooling fins and exhaust, chain and sprocket, brake disc, pedals, fuel tank and number plate. The wheels spin, the front wheels and steering wheel turn as you steer, the kart tilts on slopes, and it shakes with the engine.

## Commands & permissions

| Command | |
|---|---|
| `/kart duel <player>` | Challenge someone |
| `/kart accept` / `decline` | Answer a challenge |
| `/kart cancel` | Back out / forfeit |
| `/kart wins [player]` | Race wins + trophy tier |
| `/kart top` | Top 10 racers |
| `/kart give <rubber\|tires> [amount] [player]` | Give items (admin) |
| `/kart reload` | Reload config (admin) |

Alias: `/gokart`. Permissions: `gokart.use` (everyone), `gokart.admin` (op).

Everything — track length, speed, steering, boost, camera, sounds, tire bonuses, kart and driver size — is in `config.yml`.
