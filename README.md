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

And **fire and smoke are gas**: flames and smoke are simulated as moving air around you instead of
being drawn as flat pictures and puffs. See [Smoke and fire](#smoke-and-fire).

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
- Goo is thick where it clings to something: a block, a piston, or more goo that does. What comes
  off falls freely: a drop of honey no longer sinks through the air on a rope of honey, and a
  thread drawn out to a single line of particles snaps.
- Goo sticks to you only while you are slow (wading, sneaking): then you draw strings. Walk, run,
  jump or fall and it stays where it is instead of riding along on you. What does stick comes off
  in water or rain, or when you shake yourself: the key for it is R, under Gameplay in the
  controls ("Shake goo off yourself").
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
  whether it keeps up with real time; where a step of the Rust core goes (its own timers, part by
  part); and, for goo drawn as a surface, how many of its quads are in view and how long handing
  them to the game takes each frame.
- `/goolook threads <n>` (or `auto`) makes the Rust core work on that many threads until the game
  is closed: it changes how long a step takes and nothing else, and is there to find out what a
  machine does best with (`PHYSICSBRUHH_THREADS` in the environment sets it for good).

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

## Smoke and fire

With the game rule `physicsbruhh:physical_gas` on (the default) the air in a box around you is
simulated: how it moves, how hot it is, how much smoke and unburnt fuel it carries.

- **What feeds it**: blocks of fire and lit campfires (soul fire burns blue), torches (a thread of
  smoke), the air over lava, lit furnaces (smoke as from a chimney), burning creatures and a
  burning player, explosions (a fireball that swells, pushes the air away and leaves a cloud).
- **What it does**: fuel comes up in a few wandering jets and burns into tongues of flame; hot gas
  rises and cools, smoke is carried along, curls, thins out; blocks are walls: smoke gathers
  under a roof and comes out round its edges.
- **What it looks like**: flames glow in the colour of their temperature (yellow-white where
  hottest, orange, dark red at the tips), brighter the hotter, in tongues and gaps finer than the
  grid (a pattern carried up with the hot gas), with edges that waver and a glow that spills over
  what is around them; smoke is dark and thick, lit by the sun and the sky,
  shaded by the smoke between it and the sun, and lit from below by the flames under it. At night
  the fire is what lights its smoke. What is seen through a flame and the hot air over it wavers.
- **The game's own fire** is left out where the gas is drawn: the flat flames of fire blocks and
  campfires (the logs stay), the puffs of smoke over them, the puff of an explosion, the flames
  across the screen when you burn. Outside the box, and with the rule off, all of that is as ever.

`/gamerule physicsbruhh:physical_gas false` switches it off for everyone. On each client:

| Command | |
| --- | --- |
| `/gaslook low` | a box of 24 x 16 x 24 blocks, 4 cells to the block |
| `/gaslook medium` | 24 x 20 x 24 blocks, 6 cells to the block (the default) |
| `/gaslook high` | 32 x 24 x 32 blocks, 8 cells to the block (12.6 million cells) |
| `/gaslook ultra` | 40 x 28 x 40 blocks, 8 cells to the block (22.9 million cells) |
| `/gaslook off` | fire and smoke as the game draws them, on this client only |
| `/gaslook solver auto\|gpu\|compute\|cpu` | where it is computed (see below) |
| `/gaslook range 24`, `32`, `48`, `64`, `96` | how wide the box is, in blocks, whatever the setting above says (it is about two thirds as high). The grid is as fine as that setting asks for if that fits into about a gigabyte of the graphics card's memory, otherwise as fine as does; the answer says what it came to and how much memory it takes |
| `/gaslook info` | which solver, how many cells, how long a step and the drawing take, how much of the box is being computed |

**Where it is computed.** On the graphics card: every step of the solver is a pass over a texture
(`shaders/core/gas_*.fsh`), and so is the drawing. Where those passes do not work, the Rust core
computes a coarser box (24 x 16 x 24 blocks, 2 cells to the block) on a thread of its own and the
graphics card only draws it. If that does not work either, fire and smoke stay as the game draws
them, and the log says why.

`/gaslook solver compute` runs the same steps as compute shaders instead of drawing them: the
game's graphics layer has no such thing, so they are run by what is under it (Vulkan, or an
OpenGL that has the calls of 4.5, whatever version it says it is). The programs are the passes'
own shaders, called for every cell instead of for every pixel, so the numbers are the same; what
is saved is setting up and clearing a render target for every pass. It is asked for and not
chosen because it has run on one real graphics card only (see Limits): if it fails to start, the
passes are drawn as before and the chat says why; `/gaslook solver gpu` goes back.

**What is not computed.** Gas is seldom everywhere in the box: a campfire and its smoke fill a few
dozen blocks of thousands. The solver on the graphics card works only on a smaller box round what
there is (the gas last seen and three blocks more, the blocks that feed it and the room over
them, burning creatures, for three seconds the place of an explosion); `/gaslook info` says how
much of the whole that is. `-Dphysicsbruhh.gas.whole=true` computes everything always.

That part grows at once and shrinks only now and then, because a texture that held something
outside the part it is written over has to be cleared first; as long as the part stays or grows,
nothing is cleared, and a step costs what the part costs and not what the box would. (Clearing
every texture before every pass used to be most of the cost of a wide box with one fire in it.)
The fine grid's textures are left uncleared only while the part is less than a quarter of the box:
they are then written over with nothing where no block is awake, which is cheaper than clearing a
large texture and dearer than clearing a small one.

**The map of the world in the box** (what is solid, what burns, how light it is) moves with the
box: what stays in the box keeps what is known of it, and only the blocks that have come into it
are looked up in the world. The rest is gone over again a few thousand blocks a tick; in a wide
box that takes seconds, so the columns near the player are gone over more often, and those around
an explosion a moment after it.
Inside that box, smoke, heat and fuel (the fine grid, where most of the work is) are worked on
only in blocks that are awake: where something feeds the gas, or where gas was seen in the block
or in one of the six next to it after the last step. Over one fire in a box of five thousand
blocks that is four hundred. The Rust core does the same.

**The pressure.** What keeps gas from piling up is found in steps down and up four grids, each
twice as coarse as the one before, instead of by many rounds on one: measured against the exact
answer in the Rust core (`examples/gas_pressure.rs`), sixteen rounds left what is taken off the
velocity 11 % off over a campfire and 53 % off in an explosion; two rounds before and two after on
every grid leave it 4 % and 8 % off for less than half the work.

**How fast.** `/gaslook info` shows the time a step takes by the graphics card's own clock (and the
time the drawing takes), so it can be compared with other solvers on the same machine at the same
number of cells. It has only ever been run on software rendering here (see Limits), so there is no
measured number for a real graphics card in this file.

## Lava

Every block of lava the game would draw, wherever it is, is drawn by the mod instead. The blocks
themselves are left exactly as they are: lava flows, burns, lights and makes stone the way it
always did, on a server that knows nothing of this. Only what is seen is the mod's.

**One surface.** Instead of a tilted square per block the top is one smooth sheet through the
levels of the blocks; where lava ends in the open it ends in a rounded front that comes down to
the floor, not in a wall; against glass and other things that are not full blocks it stands flush.
When a level changes the surface travels to its new height, and lava that is new to a block comes
in from the side it runs from.

**A heavy liquid.** In a square of 64 by 64 blocks around the player (four cells to the block) the
graphics card keeps how far the surface stands above or below where it rests and how fast it is
rising, and steps that every frame. Whatever pushes the surface starts a wave: the wave spreads,
is thrown back by the banks and dies away quickly, as it does in something that thick. A creature
or a thing that falls in makes a hollow with a rim, and throws up drops if it falls hard; wading
through lava ploughs it. Where the lava was stirred its crust is torn for a while.

**Bubbles.** Where lava lies in a pool (a block of it with lava all around; the deeper and wider,
the more and the larger) gas comes up through it now and then: the surface swells into a dome, the
dome bursts with a pop, its skin flies off as drops that fall back, and a ring runs out from where
it was. The drops are bodies thrown under gravity, each at a speed of its own.

**How it looks** is taken from photographs of lava. What is worked out for every pixel is how hot
the surface is there; colour and brightness follow from the heat the way they do for anything
that glows by being hot. Lava on the run is a bright sheet drawn out into bands and strokes along
the way it runs, with torn rafts of crust that travel with it, more of them towards its edges and
where it is thin. Lava that stands still has set into plates with glowing cracks between them,
with patches where hot lava wells up and the plates have gone under. Falling lava is a bright
stream with hardly any crust. The crust is dark rock, lit by the sky like rock.

| Command | What it does |
|---|---|
| `/lavalook on` | the mod draws the lava (the default) |
| `/lavalook off` | lava as the game draws it, on this client only |
| `/lavalook info` | how much lava the mod is drawing, and what it costs |

With a shader pack on, the pack draws the lava its own way and the mod's lava waits until the
pack is off. If the mod's shaders do not work on a machine, that is said once in the log and the
game's own lava is back.

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

The core uses two thirds of the machine's processors, twelve at most (half of them on a machine
with four or fewer); they only work while goo is moving. `PHYSICSBRUHH_THREADS=<n>` in the
environment overrides that.

What keeps the core quick when there is a lot of goo:

- Where the processor has them (AVX2: about every desktop processor since 2015), the loops over a
  particle's neighbours work on eight neighbours at a time instead of one. The numbers are the same
  to the last bit either way, and a test holds the two against each other; `PHYSICSBRUHH_PLAIN=1`
  in the environment switches the wide way off. On Apple silicon the core works one at a time.
- The neighbour lists are walked in the order they lie in memory.
- With the wide registers nothing is carried from the density to the correction: the correction
  works the kernel towards every neighbour out again. Carrying it meant sixteen bytes for every
  neighbour of every particle, written and read three times a step: half a gigabyte a step for
  eighty thousand particles, which at sixty steps a second is more than a desktop's memory moves.
  (On two cores this is no quicker; it is meant for many threads, where it has not been measured.)
- Springs are relaxed on all threads: every piece its own, side by side, then the springs between
  two pieces, pair by pair, in rounds in which no piece is in two pairs. A lake of honey, where
  every piece is joined to its neighbours, used to do all of this on one thread.
- Nothing is sized by how much goo there could be: room for pieces is made as they come, the table
  particles are looked up in is as large as the number of particles that are moving, and the blocks
  around the goo are kept in a table that does not care how far apart they are.

On the machine this was measured on (two cores of a 2.1 GHz server processor), a step with 24
pieces moving (41 thousand particles) took 92 ms on one thread before and takes 70 ms now, and
51 ms against 42 ms on two; a lake of 24 pieces takes 76 ms on one thread and 45 ms on two. See
`cargo run --release --example bench -- 24 3 [lake]` in `native/`.

On a real desktop it has been reported once (16 threads, before the last two points above and
before the last loops over all particles were taken off the single thread): 50 pieces of honey,
49 of them moving, on 10 threads ran at 37 % of real time, about 40 ms a step. That is three
times what the same step should take if ten threads were worth eight cores, and why is not known
yet; `/goolook info` and `/goolook threads` are there to find out.

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
cargo run --release --example gas_preview -- close 4 fire      # pictures of the gas solver without the game: close, closecamp, fires, room, blast, runner; add "night"
cargo run --release --example gas_pressure -- camp 4           # how far off the pressure solve is, for ways of solving it and guesses to start from: camp, fire, room, blast
```

## Limits

- Lava is still the game's lava: what the mod adds is how its surface is drawn and how that
  surface moves. The waves and the bubbles are worked out on each client and are only something to
  look at: they do not push, burn or carry anything, and two players see different bubbles in the
  same lake. The waves exist in a square of 64 blocks around the player; lava further away is drawn
  at rest. Where several storeys of lava lie above one another inside that square, only the one
  nearest the player's height has waves. A lava-fall is still a column a block wide. Lava that
  disappears from a block is gone at once, not drawn sinking away. The lava has been run on the
  software renderers of the build machines (Vulkan and OpenGL) and, its shaders alone, in a
  browser's WebGL; on a real graphics card nobody has run it yet, so there is no number here for
  what it costs there.
- Smoke and fire exist in a box around each player and nowhere else: a fire further away than
  that is drawn the vanilla way, and a column of smoke ends at the top of the box. The box is 24
  blocks wide unless `/gaslook range` says otherwise, 96 at most (an explosion is then gas up to
  48 blocks away; the server tells clients of explosions within 96). What stops it going further
  is the graphics card's memory: 96 blocks wide at 4 cells to the block is 38 million cells, about
  0.9 GB. The game's tests run a box 48 blocks wide on a software renderer (one fire in it: a
  tenth to a fifth of the box is computed); nothing wider than 24 blocks has been run on a real
  graphics card, and 64 and 96 not at all. Up to 32 burning creatures and explosions feed the gas
  at once. The gas is
  computed separately on every client and is only something to look at: it does not burn, choke
  or push anything, and two players see different curls of the same smoke.
- The gas solver's passes have been run on the software renderers of the build machines (Vulkan
  and OpenGL) and in a browser's WebGL, where they give the same numbers as the Rust solver to
  four digits. On a real graphics card they have been run by one player, on one NVIDIA card: at
  `medium` a step took 0.6 to 1.1 ms and the drawing 2 to 4 ms. The compute shaders
  (`/gaslook solver compute`) started on that card with Vulkan; how fast they are there has not
  been reported. With OpenGL they did not start there, because the driver calls itself 3.3 and
  the mod asked for 4.5; it now asks for the calls instead of the version, which no real card has
  confirmed yet. More is at stake with them than with the passes:
  they go round the game's graphics layer, and a mistake there that a software renderer forgives
  can take the game down on a real driver instead of being caught. That is why they are not the
  default. The model itself is the common one for real-time smoke (velocity and pressure on a coarse
  grid, smoke carried on a finer one): it is not a model of chemistry, of soot or of radiation.
- Torches get a thread of smoke but no flame of gas (a torch's flame is smaller than a cell), and
  the flat flames on a burning creature are still the game's.
- The gas is drawn after everything else in the world, at half the screen's size: behind stained
  glass or water it is drawn over them, and a thin post in front of a fire has a soft edge.

- Honey or slime that is still an ordinary block (placed by a command, or while the rule was off)
  only turns into goo when a piston can actually move it; if vanilla says the piston is blocked,
  nothing happens.
- The shape of the goo is simulated separately on every client, so two players see slightly
  different puddles. The server only knows roughly where each piece is (a marker that follows
  pistons), which is what mobs, items and fall damage go by.
- There is no fixed number of pieces of goo any more: room is made as they arrive. What stops it
  is memory, about half a megabyte a piece, so there is a ceiling of 1024 pieces
  (`-Dphysicsbruhh.goo.most=<n>` moves it, up to 8192); past it the oldest piece that lies still
  makes room for a new one. Blocks around the goo: 65 thousand boxes at most; pistons parts and
  creatures in it: 512. The Java simulation (used only where the Rust core cannot be loaded) holds
  64 pieces and only knows the blocks within 96 blocks of each other.
- Poured and thrown goo joins a piece of its kind lying within a block and a half of where it
  comes down; further away it starts a piece of its own.
- Goo drawn as a surface (the block textures, or `/goolook shader`) is handed to the game anew
  every frame and sorted by it, so a frame costs more the more goo is in view, whether it moves or
  lies still. Puddles out of view are left out, but a great deal of goo in front of the camera
  still costs frames. Clear goo drawn as a liquid does not have this cost: its particles go to the
  graphics card once, when they change.
- The server does not simulate goo, so where a stream or a thrown lump ends up is its estimate
  (a throw followed until it meets a block). Mobs and falls go by that estimate; what you see and
  wade through is the real thing on your client.
- Under a shader pack, goo that is handed to the pack is plain translucent geometry: no bending
  of what lies behind it, no amber deepening per pixel; how it is lit is the pack's doing. Clear
  goo drawn as a liquid over the pack's picture is the other way round: it looks as it does
  without a pack, and the pack's light, shadows and effects pass it by.
- The liquid look draws the goo after everything else in the world. Behind clear glass that is
  right: the frame and the streaks of the pane stay in front of the goo. Goo behind stained glass
  or water is drawn over them or hidden by them, depending on the graphics settings. It costs a
  few full-screen passes; `/goolook shader` is the cheaper way.
- Vanilla pistons move a block in a tenth of a second. Slime holds on and stays stretched between
  two sticky pistons as a band. Honey cannot follow that fast: it tears into strands, and what is
  left sags into a strand or a puddle between the pistons.
- A piece of goo is 1728 particles, and only pieces that are moving cost anything: slime comes to
  rest about three seconds after it was last disturbed, honey about seven. A moving piece takes
  roughly 3 ms of one core per step (measured on a slow server core, see above), 60 steps a
  second, so a big puddle that is still spreading needs several cores. A machine that cannot keep up shows the goo in slow motion
  rather than skipping steps (`/goolook info` shows the percentage); it then takes one step
  between two looks at the goo instead of making up for lost time with three, so that the slow
  motion is at least even.

## License

All rights reserved for the mod's own code. Build scripts derived from the NeoForge MDK are under
the MIT license, see `TEMPLATE_LICENSE.txt`. The mod ships no Mojang assets; it uses the game's own
honey and slime textures and sounds at run time.
