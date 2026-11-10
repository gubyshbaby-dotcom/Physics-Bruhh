# Physics Bruhh

A NeoForge mod for Minecraft 26.3. Honey and slime blocks stop being rigid cubes:

- **Honey** melts a few seconds after it is placed, tips over and spreads into a thick puddle.
- **Slime** stays a wobbly jelly cube that sags, bounces back and sticks to what it touches.
- **Pistons** shove both around. A sticky piston grabs the goo; two sticky pistons pulling apart
  stretch it between their heads: slime as an elastic band, honey as strings that thin out and snap.

Everything is on by default for the vanilla `honey_block` and `slime_block`.

## Using it

- Place a honey or slime block, or let a piston push or pull one: it turns into goo.
- Punch the goo to get the block back as an item.
- `/gamerule physicsbruhh:physical_goo false` switches the mod off (blocks behave as in vanilla again;
  goo that already exists stays until it is punched).
- `/gamerule physicsbruhh:clear_goo true` changes the look: instead of the blocks' pixel textures,
  honey and slime are drawn as clear liquid (amber and green) that is darker where it is thick,
  mirrors the sky at flat angles and has a highlight. Off by default; applies to all goo at once.

Slime and honey contraptions (flying machines, block conveyors) do not work while the rule is on,
because the blocks they rely on are no longer blocks.

## How it works

| Part | Where | What it does |
| --- | --- | --- |
| Simulation core | `native/` (Rust) | Position-based fluid with a spring network on top: springs that creep and tear make honey, permanent springs make slime. Collides with block boxes and moving piston parts, sticks to surfaces, builds the surface mesh. |
| Java twin | `src/main/java/physicsbruhh/sim/JavaSim.java` | The same algorithm line for line. Used when the native library cannot be loaded. |
| Server side | `GooEvents`, `GooEntity` | Decides when a block becomes goo (placement, piston about to move it) and keeps a small marker entity so it is saved and synced. |
| Client side | `client/` | Runs the simulation on its own thread at 60 Hz, feeds it the blocks and pistons around the goo, draws the surface (vanilla honey / slime textures, or the clear look). |

The mod jar carries the native library for Windows x64, Linux x64 and macOS (Intel and Apple
silicon). On anything else, or if loading fails, it falls back to the Java simulation and says so in
the log.

## Building

CI (`.github/workflows/build.yml`) does everything on every push and uploads the finished jar as
the `physicsbruhh-jar` artifact:

1. builds and tests the Rust core on all four platforms,
2. checks that the Java and Rust simulations agree,
3. builds the mod against NeoForge,
4. boots a dedicated server and runs real piston contraptions against the hooks,
5. opens the world in a real client (software rendering) and plays a piston scene, saving screenshots.

Locally: JDK 25 and a Rust toolchain, then `./gradlew build`. The jar lands in `build/libs/`.
Without Rust the build still works; that jar only has the Java simulation.

Looking at the simulation without the game:

```
cd native
cargo test --release
cargo run --release --example viz -- pull_slime out.ppm   # honey, slime, slime_push, honey_push, pull, pull_slime
```

## Limits

- Honey or slime that is still an ordinary block (placed by a command, or while the rule was off)
  only turns into goo when a piston can actually move it; if vanilla says the piston is blocked,
  nothing happens.
- The goo is visual. It is simulated separately on every client, so two players see slightly
  different puddles, and players, mobs and items pass through it.
- At most 24 pieces of goo are simulated around a player at once; beyond that the oldest resting
  ones are dropped from view.
- Vanilla pistons move a block in a tenth of a second. Honey cannot follow that and tears: the
  stretch shows as strings that last about a second. Slime holds on as a band.
- The Rust core is single-threaded and not vectorised yet; it is only moderately faster than the
  Java twin (the mesher about three times, the solver about 20 %).

## License

All rights reserved for the mod's own code. Build scripts derived from the NeoForge MDK are under
the MIT license, see `TEMPLATE_LICENSE.txt`. The mod ships no Mojang assets; it uses the game's own
honey and slime textures at run time.
