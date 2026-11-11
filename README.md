# Physics Bruhh

A NeoForge mod for Minecraft 26.3 (NeoForge 26.3.0.7-beta and later). Honey and slime blocks stop
being rigid cubes:

- **Honey** melts a few seconds after it is placed, tips over and spreads into a thick puddle.
- **Slime** stays a wobbly jelly cube that sags, bounces back and sticks to what it touches.
- **Pistons** shove both around. A sticky piston grabs the goo; two sticky pistons pulling apart
  stretch it between their heads: slime as an elastic band, honey as strands that thin out and snap.
- **You and the mobs** push the goo aside when walking through it, and it still does what the
  blocks did: honey slows you down and halves your jump, slime throws you back up when you fall
  into it and takes the fall damage away.
- **Dispensers and hands** pour and throw it: a honey bottle becomes a stream of honey, a slime
  ball a flying lump of slime, and both can be taken up again.
- **Every block can be made thicker or thinner** before it is placed: sneak and turn the mouse
  wheel with the block in hand.

Everything is on by default for the vanilla `honey_block` and `slime_block`.

## Using it

- Place a honey or slime block, or let a piston push or pull one: it turns into goo.
- Punch the goo anywhere to get the block back as an item (with the block's own breaking sound
  and particles). Less than a block of slime comes back as slime balls; less than a block of honey
  is lost, honey needs a bottle.
- Right click the goo, anywhere on it:

  | In hand | On honey | On slime |
  | --- | --- | --- |
  | empty bottle | fills it (a quarter of a block) | — |
  | honey bottle | pours it in | — |
  | slime ball | — | presses it on (a ninth of a block) |
  | nothing | a taste: a little food | tears a slime ball off |

- Sneak and use a honey bottle to pour it out where you look (the bottle stays); use a slime
  ball to throw it as a lump of slime. Goo that lands on goo of its kind joins it, up to a block's
  worth per piece: four bottles of honey or nine slime balls punched afterwards give the block.
- A solid block placed where goo lies squeezes the goo out of itself, sideways and upwards, over a
  fraction of a second. (A honey or slime block placed there turns into goo itself.)
- A stream of honey is not a smooth rod: it starts as a thread, swells, wavers from side to side,
  comes in thicker and thinner gulps and thins out to a thread again before it breaks off. No two
  streams are alike.
- Thickness. Hold a honey or slime block, sneak and turn the mouse wheel (the hotbar stays where
  it is meanwhile): up is thicker, down is thinner, four notches either way, from a quarter to
  four times the usual. The setting belongs to that stack of items (it is shown in the tooltip
  and above the hotbar, stacks of different thickness do not merge) and goes with the block:
  placed by hand or by a dispenser it becomes goo of that thickness, and punched as a whole it
  comes back as a block that still has it.

  | | Thinner | Thicker |
  | --- | --- | --- |
  | Honey | melts sooner, runs out fast into a wide, flat puddle, tears into strands easily | keeps its shape for a long time, creeps slowly, stays a heap, pulls longer strands |
  | Slime | soft and floppy: sags, wobbles long, stretches far | stiff and springy: barely sags, snaps back at once |
  | You in it | honey slows you less; slime throws you back up less | honey slows you more and lets you jump less; slime bounces fully |

  Bottles and slime balls carry no thickness; what they add takes on the thickness of the piece
  it lands on.
- Dispensers: a honey bottle is poured out as a stream and the empty bottle stays inside; a slime
  ball is shot out as a lump; a honey or slime block comes out as a whole block of goo; an empty
  bottle is filled from honey lying in front of the dispenser. With the rule off, or when none of
  that is possible, dispensers do what they always did.
- `/goo clear` removes all goo in the world, `/goo clear <radius>` only what lies within that many
  blocks of you. Nothing is dropped. Needs the same permission as `/gamerule`.
- `/gamerule physicsbruhh:physical_goo false` switches the mod off (blocks behave as in vanilla
  again; goo that already exists stays until it is punched or cleared).
- `/gamerule physicsbruhh:clear_goo true` changes the look: instead of the blocks' pixel textures,
  honey and slime are drawn the way the real things look. Honey is a clear liquid: you see the
  world through it, bent by its surface and coloured by how much of it the light crossed (a film
  is pale gold, a puddle amber, a block's depth deep orange), with a dark outline, a small sharp
  glint of the sun and the sky mirrored in it at a glancing angle. Slime is a cloudy, glossy
  jelly that only lets light through where it is thin. Both throw a shade of their own colour on
  the ground. Off by default; applies to all goo at once.
- `/goolook` (this client only, remembered) picks how that is drawn: `liquid` (the default) as
  described; `shader` as a glossy tinted surface, which is cheaper and is also what the game falls
  back to by itself if the liquid look does not work on a machine; `plain` without any shader of
  the mod's own.
- `/goolook info` says what the simulation is doing on this client: Rust or Java core, how many
  threads, how many pieces of goo and how many of them are moving, how long an update takes, and
  whether it keeps up with real time.

### Shader packs and Sodium

Sodium needs nothing: with it the mod looks and works as without it.

With Iris and a shader pack switched on, the pack draws the whole world its own way, and a shader
that a mod brings along for things in the world does not fit into that. So while a pack is on:

- Goo with the blocks' textures is handed to the game as ordinary translucent geometry (position,
  colour, light, normal, texture), and the pack lights it like everything else.
- Clear goo (`clear_goo`) stays a liquid. The liquid look is drawn after the world, over the
  picture the pack has finished, so it does not depend on the pack. It is lit by the mod's own
  idea of sun and sky, not by the pack's: the pack's shadows do not fall on it and its effects
  (bloom, fog, depth of field) do not include it.
- `/goolook shader` or `/goolook plain` hands the clear goo to the pack as well, as plainly
  tinted translucent geometry: less of a liquid, but all of it lit by the pack.

Switch the pack off and everything is as before; nothing needs setting, and `/goolook info` says
what is happening. Two switches for the game's start: `-Dphysicsbruhh.packs.liquid=false` always
hands everything over, `-Dphysicsbruhh.packs=true` makes the mod behave as if a pack were on.

Which versions go together is not up to this mod. The jar runs on NeoForge 26.3.0.7-beta and
later. When this was written, the released Iris (1.11.7) asked for Sodium 0.9.2, and those two
only start on an early NeoForge 26.3 (they were tried on 26.3.0.7-beta); on NeoForge 26.3.0.101
Sodium 0.9.2 crashes at start by itself, Iris 1.11.7 does start there with Sodium 0.9.3-beta.3,
and the newest Sodium (0.9.3-beta.4) asks for an Iris that is not out yet. The "Shader packs"
workflow (see Building) finds out afresh what starts with what.

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
| Server side, hands and dispensers | `GooEntity`, `GooEvents`, `GooDispense` | A marker also knows how much goo its piece holds. Pouring, throwing and scooping change that amount and say where and with what velocity; clients let the same happen to their simulated goo (a stream or a lump out of a spout, particles taken away near a point). |
| Client side | `client/` | Runs the simulation on its own thread at 60 Hz, feeds it the blocks, pistons and creatures around the goo, draws it, applies the blocks' effects to the player. |
| Drawing | `client/GooRenderer`, `client/GooFluid`, `assets/.../shaders` | Textured goo is a surface mesh with the mod's shader (gloss, sky reflection, colour by depth; works with and without "improved transparency"). Clear goo is drawn from the particles themselves, after the world: discs into a nearest-surface texture and an amount texture, an edge-keeping blur that turns the heap of discs into one surface, then one pass that bends the picture behind it, lets the goo absorb light by its thickness, and adds the mirrored sky and the sun's glint; a pass from straight above gives the shade on the ground. The colours and the way light behaves were set against photographs of honey, poured caramel, toy slime and jelly. |

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
4. boots a dedicated server and runs real piston contraptions, placement, falls, dispensers,
   right clicks and `/goo clear` against the hooks,
5. opens the world in a real client (software rendering) six times: the default graphics backend,
   "improved transparency" on, the OpenGL backend, clear goo as a surface instead of a liquid, the
   shader-less fallback, and the hand-over to shader packs. Each run drives pistons against honey
   and slime in both looks, checks
   that the slime ends up stretched between the retracted sticky pistons, removes one piece while
   others move, stands a pig in the slime, walks the player into honey and drops them onto slime,
   lets dispensers pour honey bottles and shoot slime balls, fills a bottle from the puddle, pours
   it out again and throws a slime ball, sneaks and turns the wheel on a block of honey, places
   it and compares it with thin and usual honey and with thin and thick slime, puts slime and
   honey blocks down by hand the way a player builds (in the open, at the feet, against a wall,
   in a pit, next to and on top of other goo) and checks that each stays in one piece, also when
   the block is made to linger in the client's world after its goo has arrived, and saves
   screenshots (artifact `clienttest`; a commit message containing `[shots]` also prints them
   into the log as text).

A second workflow, "Shader packs" (`.github/workflows/shaderpacks.yml`), is run by hand from the
Actions page or by pushing to a branch whose name starts with `iris`. It downloads Sodium, Iris
and shader packs from Modrinth and runs the same client test with them on OpenGL: Sodium alone,
Iris with the pack off, each pack, and one pack with everything handed over. For Iris it tries the
Sodium versions Iris may accept, first on the NeoForge the mod is built for and then on the older
one named in the workflow, where it also runs the server test and checks that the mod compiles to
the very same class files. It reports and never fails: whether those mods exist for the game
version, and start, is not in this repository's hands. (In a development environment the game
checks every draw strictly; Iris with a pack on does not pass that check and runs its own
development game with it switched off, so the runs with a pack switch it off too.)

Locally: JDK 25 and a Rust toolchain, then `./gradlew build`. The jar lands in `build/libs/`.
Without Rust the build still works; that jar only has the Java simulation.

Looking at the simulation without the game:

```
cd native
cargo test --release
cargo run --release --example bench                            # step time, where it goes, threads
cargo run --release --example viz -- game_pull_slime out.ppm   # honey, slime, slime_push, honey_push, pull, pull_slime, game_pull, game_pull_slime
cargo run --release --example dump -- particles.bin            # a scene with a band, a puddle, a stream and lumps, as particles
```

## Limits

- Honey or slime that is still an ordinary block (placed by a command, or while the rule was off)
  only turns into goo when a piston can actually move it; if vanilla says the piston is blocked,
  nothing happens.
- The shape of the goo is simulated separately on every client, so two players see slightly
  different puddles. The server only knows roughly where each piece is (a marker that follows
  pistons), which is what mobs, items and fall damage go by.
- At most 24 pieces of goo are simulated around a player at once. Poured and thrown goo joins a
  piece of its kind lying within a block and a half of where it comes down; further away it
  starts a piece of its own.
- The server does not simulate goo, so where a stream or a thrown lump ends up is its estimate
  (a throw followed until it meets a block). Mobs and falls go by that estimate; what you see and
  wade through is the real thing on your client.
- Under a shader pack, goo that is handed to the pack is plain translucent geometry: no bending
  of what lies behind it, no amber deepening per pixel; how it is lit is the pack's doing. Clear
  goo drawn as a liquid over the pack's picture is the other way round: it looks as it does
  without a pack, and the pack's light, shadows and effects pass it by.
- The liquid look draws the goo after everything else in the world: goo behind stained glass or
  water is drawn over them or hidden by them, depending on the graphics settings. It costs a few
  full-screen passes; `/goolook shader` is the cheaper way.
- Vanilla pistons move a block in a tenth of a second. Slime holds on and stays stretched between
  two sticky pistons as a band. Honey cannot follow that fast: it tears into strands, and what is
  left sags into a strand or a puddle between the pistons.
- A piece of goo is 1728 particles, and only pieces that are moving cost anything: slime comes to
  rest about three seconds after it was last disturbed, honey about seven. A moving piece takes
  roughly 3 to 4 ms of one core per step, 60 steps a second, so a big puddle that is still
  spreading needs several cores. A machine that cannot keep up shows the goo in slow motion
  rather than skipping steps (`/goolook info` shows the percentage).

## License

All rights reserved for the mod's own code. Build scripts derived from the NeoForge MDK are under
the MIT license, see `TEMPLATE_LICENSE.txt`. The mod ships no Mojang assets; it uses the game's own
honey and slime textures and sounds at run time.
