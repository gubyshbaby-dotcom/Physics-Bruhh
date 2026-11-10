# Physics Bruhh

A NeoForge mod for Minecraft 26.3. Honey and slime blocks stop being rigid cubes:

- **Honey** melts a few seconds after it is placed, tips over and spreads into a thick puddle.
- **Slime** stays a wobbly jelly cube that sags, bounces back and sticks to what it touches.
- **Pistons** shove both around. A sticky piston grabs the goo; two sticky pistons pulling apart
  stretch it between their heads: slime as an elastic band, honey as strands that thin out and snap.
- **You and the mobs** push the goo aside when walking through it, and it still does what the
  blocks did: honey slows you down and halves your jump, slime throws you back up when you fall
  into it and takes the fall damage away.

Everything is on by default for the vanilla `honey_block` and `slime_block`.

## Using it

- Place a honey or slime block, or let a piston push or pull one: it turns into goo.
- Punch the goo anywhere to get the block back as an item (with the block's own breaking sound
  and particles).
- `/goo clear` removes all goo in the world, `/goo clear <radius>` only what lies within that many
  blocks of you. Nothing is dropped. Needs the same permission as `/gamerule`.
- `/gamerule physicsbruhh:physical_goo false` switches the mod off (blocks behave as in vanilla
  again; goo that already exists stays until it is punched or cleared).
- `/gamerule physicsbruhh:clear_goo true` changes the look: instead of the blocks' pixel textures,
  honey and slime are drawn as clear liquid (amber and green) that is darker where it is thick.
  Off by default; applies to all goo at once.
- `/goolook plain` (this client only, remembered) draws the goo without the mod's own shader, in
  case that shader misbehaves on some machine; `/goolook shader` switches back.

Slime and honey contraptions (flying machines, block conveyors) do not work while
`physical_goo` is on, because the blocks they rely on are no longer blocks.

### What is kept of the original blocks

| | Honey | Slime |
| --- | --- | --- |
| Walking through | 40 % speed, in proportion to how deep you stand in it | slowed on the ground, as on the block |
| Jumping | half height | — |
| Falling into it | a fifth of the fall damage | no fall damage, and you bounce (not while sneaking) |
| Sounds | the block's own: step, slide, break; sliding when a piston moves it | the block's own: step, fall, break; a squelch when a piston hits it |
| Mobs and items | slowed | bounced and slowed |

What cannot be kept: goo is not solid, so nothing can stand on top of it, and pistons do not
drag neighbouring blocks along with it.

## How it works

| Part | Where | What it does |
| --- | --- | --- |
| Simulation core | `native/` (Rust) | Position-based fluid with a spring network on top: springs that creep and tear make honey, permanent springs make slime. Collides with block boxes, moving piston parts and creatures, sticks to surfaces, builds the surface mesh. Runs on several threads; the result does not depend on how many. |
| Java twin | `src/main/java/physicsbruhh/sim/JavaSim.java` | The same algorithm in plain Java, single-threaded. Used when the native library cannot be loaded. CI checks that the two agree on the scenarios in `src/simcheck`. |
| Server side | `GooEvents`, `GooEntity`, `GooCommands` | Decides when a block becomes goo (placement, piston about to move it), keeps a small marker entity so it is saved and synced, applies the blocks' effects to mobs and falls, runs `/goo clear`. |
| Client side | `client/` | Runs the simulation on its own thread at 60 Hz, feeds it the blocks, pistons and creatures around the goo, draws the surface with its own shader (gloss, sky reflection, colour by depth; works with and without "improved transparency"), applies the blocks' effects to the player. |

The mod jar carries the native library for Windows x64, Linux x64 and macOS (Intel and Apple
silicon). On anything else, or if loading fails, it falls back to the Java simulation and says so in
the log.

The core uses half of the machine's cores, six at most. `PHYSICSBRUHH_THREADS=<n>` in the
environment overrides that.

An error inside the mod's client code does not take the game down: the goo is dropped, the reason
is written to the log, and after three errors in one world the mod stays quiet until the world is
left.

## Building

CI (`.github/workflows/build.yml`) does everything on every push and uploads the finished jar as
the `physicsbruhh-jar` artifact:

1. builds and tests the Rust core on all four platforms (on Linux also with a fixed number of
   threads, and a benchmark that must give bit-identical results for 1, 2 and 4 threads),
2. checks that the Java and Rust simulations agree,
3. builds the mod against NeoForge,
4. boots a dedicated server and runs real piston contraptions, placement, falls and `/goo clear`
   against the hooks,
5. opens the world in a real client (software rendering) four times: the default graphics backend,
   "improved transparency" on, the OpenGL backend, and the shader-less fallback. Each run drives
   pistons against honey and slime in both looks, checks that the slime ends up stretched between
   the retracted sticky pistons, removes one piece while others move, stands a pig in the slime,
   walks the player into honey and drops them onto slime, and saves screenshots (artifact
   `clienttest`; a commit message containing `[shots]` also prints them into the log as text).

Locally: JDK 25 and a Rust toolchain, then `./gradlew build`. The jar lands in `build/libs/`.
Without Rust the build still works; that jar only has the Java simulation.

Looking at the simulation without the game:

```
cd native
cargo test --release
cargo run --release --example bench                            # step time, where it goes, threads
cargo run --release --example viz -- game_pull_slime out.ppm   # honey, slime, slime_push, honey_push, pull, pull_slime, game_pull, game_pull_slime
```

## Limits

- Honey or slime that is still an ordinary block (placed by a command, or while the rule was off)
  only turns into goo when a piston can actually move it; if vanilla says the piston is blocked,
  nothing happens.
- The shape of the goo is simulated separately on every client, so two players see slightly
  different puddles. The server only knows roughly where each piece is (a marker that follows
  pistons), which is what mobs, items and fall damage go by.
- At most 24 pieces of goo are simulated around a player at once.
- Vanilla pistons move a block in a tenth of a second. Slime holds on and stays stretched between
  two sticky pistons as a band. Honey cannot follow that fast: it tears into strands, and what is
  left sags into a strand or a puddle between the pistons.
- A piece of goo is 1728 particles. A machine that cannot keep up shows the goo in slow motion
  rather than skipping steps.

## License

All rights reserved for the mod's own code. Build scripts derived from the NeoForge MDK are under
the MIT license, see `TEMPLATE_LICENSE.txt`. The mod ships no Mojang assets; it uses the game's own
honey and slime textures and sounds at run time.
