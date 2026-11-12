//! Goo simulation core: position-based fluid + viscoelastic spring network + surface adhesion.
//!
//! Units are blocks and seconds. All coordinates are local to an origin the caller picks, so
//! single precision stays accurate far from the world centre.
//!
//! Every body is `n^3` particles. Three materials share one world:
//!  * honey — starts as a soft jelly cube, its springs turn plastic as it "melts", ends as a puddle;
//!  * slime — keeps its spring lattice forever, so it stays an elastic blob;
//!  * lava — a block of it is liquid from the start: thick, heavy, slow; it oozes out of where it
//!    stood as a tongue with a rounded front, pours as a rope and lies down as a thick lobe.
//! Colliders are axis-aligned boxes: static ones (blocks) and kinematic ones (moving piston parts).
//! Particles that touch a collider get anchored to it; anchors creep (honey slides slowly) and
//! snap when overstretched, which is what makes goo cling to pistons and pull into strands.

use rayon::prelude::*;
use std::sync::OnceLock;

pub const MAX_SPR: usize = 16;
pub const HONEY: u8 = 0;
pub const SLIME: u8 = 1;
pub const LAVA: u8 = 2;
/// Which material a number names: anything that is not slime or lava is honey.
#[inline] pub fn material(mat: u8) -> u8 { if mat == SLIME || mat == LAVA { mat } else { HONEY } }
pub const DT: f32 = 1.0 / 60.0;
/// How much thinner or thicker than usual a piece of goo may be.
pub const THICK_MIN: f32 = 0.25;
pub const THICK_MAX: f32 = 4.0;

#[derive(Clone, Copy, Debug)]
pub struct Material {
    pub melt_time: f32,      // seconds until fully fluid; 0 = never melts
    pub damp_fresh: f32,     // velocity damping 1/s while fresh / once melted
    pub damp_melted: f32,
    pub spring_k0: f32,      // spring stiffness per solver iteration, fresh -> melted
    pub spring_k1: f32,
    pub plast0: f32,         // plastic creep of spring rest length, 1/s, fresh -> melted
    pub plast1: f32,
    pub yield_: f32,         // strain below which springs stay elastic
    pub dynamic_springs: bool, // new springs form between neighbours (fluid) or only at spawn (solid)
    pub break_strain: f32,   // spring snaps when length > break_strain * rest
    pub spring_max_l: f32,   // dynamic springs dissolve once their rest length creeps past this (blocks)
    pub shock_k: f32,        // spring stiffness while a piston is yanking the goo (rate-dependent response)
    pub shock_plast: f32,    // plastic creep during that time, 1/s
    pub shock_decay: f32,    // seconds for the stiffening to fade
    pub shock_break: f32,    // break strain / max rest length while stiffened: long threads survive the
    pub shock_max_l: f32,    // stroke and then snap one by one as the stiffening fades
    pub thread_sag: f32,     // how much a stretched thread droops, as a fraction of its length
    pub coh_k: f32,          // tensile (cohesion) share of the density constraint
    pub coh_lim: f32,
    pub xsph: f32,
    pub adh_k: f32,          // anchor stiffness per iteration (close to 1: stuck goo stays on the surface)
    pub adh_creep: f32,      // anchor creep 1/s under load (0 = sticks in place)
    pub adh_break: f32,      // anchor snaps when the rest of the goo drags it further than this in one
                             // solver iteration (blocks, a stand-in for force), scaled by the collider's stick
    pub set_after: f32,      // seconds after which damping starts to grow ("setting"), 0 = never
    pub set_rate: f32,
    pub tip: f32,            // initial tipping rate of a fresh block, rad/s
    pub sleep_v: f32,        // rms speed (blocks/s) below which a body counts as at rest
}

impl Material {
    /// This material as a thicker (`d` > 1) or thinner (`d` < 1) piece of goo; 1 leaves it as it is.
    /// Thick honey melts later, creeps and flows more slowly and holds a heap; thin honey runs like
    /// syrup. Thick slime is a stiff jelly that hardly wobbles, thin slime is soft and saggy.
    pub fn thick(&self, d: f32) -> Material {
        let d = if d.is_finite() && d > 0.0 { d.clamp(THICK_MIN, THICK_MAX) } else { 1.0 };
        if d == 1.0 { return *self; }
        let mut m = *self;
        let root = d.sqrt();
        if m.dynamic_springs {
            m.melt_time *= d;
            m.damp_fresh *= root;
            m.damp_melted *= d;
            m.spring_k1 = (m.spring_k1 * d).min(0.5);
            m.plast0 /= d;
            m.plast1 /= d;
            m.yield_ *= d;
            m.adh_creep /= d;
            m.coh_k = (m.coh_k * root).min(0.45);
            m.xsph = (m.xsph * root).min(0.5);
        } else {
            // per solver iteration a spring closes this share of its error: d springs in a row close 1 - (1 - k)^d
            let stiff = |k: f32| 1.0 - (1.0 - k).powf(d);
            m.spring_k0 = stiff(m.spring_k0);
            m.spring_k1 = stiff(m.spring_k1);
            m.shock_k = stiff(m.shock_k);
            m.damp_fresh *= root;
            m.damp_melted *= root;
        }
        m
    }

    pub fn honey() -> Material {
        Material {
            melt_time: 3.5, damp_fresh: 1.2, damp_melted: 11.0,
            spring_k0: 0.5, spring_k1: 0.03, plast0: 0.7, plast1: 20.0, yield_: 0.03,
            dynamic_springs: true, break_strain: 4.0, spring_max_l: 0.21, shock_k: 0.42, shock_plast: 0.6, shock_decay: 1.1, shock_break: 16.0, shock_max_l: 1.7, thread_sag: 0.16,
            coh_k: 0.25, coh_lim: 0.35, xsph: 0.35,
            adh_k: 0.9, adh_creep: 9.0, adh_break: 0.30,
            set_after: 5.0, set_rate: 0.5, tip: 2.05, sleep_v: 0.03,
        }
    }
    /// Lava: liquid from the moment its block lets go, but thick and heavy. It creeps rather than
    /// runs, keeps a rounded front a good part of a block high, pours as a rope and lies down as a lobe.
    /// It has no strength of its own (no strain it holds for good), so it creeps on for as long as it
    /// is thick enough to push itself along; a layer of it stays behind wherever it has been.
    pub fn lava() -> Material {
        Material {
            melt_time: 1.0, damp_fresh: 3.0, damp_melted: 12.0,
            spring_k0: 0.3, spring_k1: 0.12, plast0: 6.0, plast1: 3.0, yield_: 0.0,
            dynamic_springs: true, break_strain: 4.0, spring_max_l: 0.21, shock_k: 0.42, shock_plast: 0.6, shock_decay: 1.1, shock_break: 16.0, shock_max_l: 1.7, thread_sag: 0.16,
            coh_k: 0.45, coh_lim: 0.35, xsph: 0.45,
            // it does not cling: what holds lava back is its own thickness, not the wall
            adh_k: 0.0, adh_creep: 30.0, adh_break: 0.01,
            set_after: 25.0, set_rate: 0.1, tip: 0.0, sleep_v: 0.01,
        }
    }
    pub fn slime() -> Material {
        Material {
            melt_time: 0.0, damp_fresh: 2.2, damp_melted: 2.2,
            spring_k0: 0.45, spring_k1: 0.45, plast0: 0.0, plast1: 0.0, yield_: 0.0,
            dynamic_springs: false, break_strain: 8.0, spring_max_l: 9.0, shock_k: 0.45, shock_plast: 0.0, shock_decay: 0.3, shock_break: 12.0, shock_max_l: 9.0, thread_sag: 0.03,
            coh_k: 0.15, coh_lim: 0.3, xsph: 0.25,
            adh_k: 0.9, adh_creep: 0.0, adh_break: 0.16,
            set_after: 0.0, set_rate: 0.0, tip: 0.0, sleep_v: 0.015,
        }
    }
}

#[derive(Clone, Copy, Debug, Default)]
pub struct Body {
    pub state: u8, // 0 free, 1 live, 2 fading out
    pub mat: u8,
    pub awake: bool,
    pub age: f32,
    pub born: f32,
    pub fade: f32,
    pub melt: f32,
    pub kd: f32,
    pub k_spring: f32,
    pub plast: f32,
    pub brk: f32,
    pub max_l: f32,
    pub calm: u32,
    pub quiet: bool,
    pub count: u32,
    pub anchored: u32,
    pub fresh: bool,
    pub shock: f32,
    /// Seconds left of taking it easy: set when a body appears inside other goo, so the two part gently instead of bursting.
    pub soft: f32,
    /// Counts the steps in which this body changed; the mesher skips puddles whose bodies all stand still.
    pub rev: u32,
    pub bb: [f32; 6],
    /// A stream still on its way: particles left to come out, where from, how fast, and how far the last layer has got.
    pub pending: u32,
    pub emit_pos: [f32; 3],
    pub emit_vel: [f32; 3],
    pub emit_acc: f32,
    /// How much the stream held when it began, and how long it has been running (seconds): it swells, sways and thins out by these.
    pub emit_total: u32,
    pub emit_time: f32,
    /// How thick this piece is, see [`Material::thick`]; 1 is the usual.
    pub thick: f32,
    /// Until this time (world clock) the body has particles young enough to bond to what they touch.
    pub young_until: f32,
}

#[derive(Clone, Copy, Debug)]
pub struct SBox { pub min: [f32; 3], pub max: [f32; 3], pub stick: f32 }

#[derive(Clone, Copy, Debug)]
pub struct KBox {
    pub id: i32, pub min: [f32; 3], pub max: [f32; 3], pub pmin: [f32; 3], pub pmax: [f32; 3], pub vel: [f32; 3], pub stick: f32, pub t: f32,
    /// Face that goo caught inside the box leaves through: 0 = whichever fits, 1..6 = +x, -x, +y, -y, +z, -z.
    /// A piston head sets this to its front, so nothing is ever pushed out of its back, towards the piston.
    pub front: u8,
}

/// Floats per kinematic box: id, min xyz, max xyz, velocity xyz, stick, front.
pub const KIN_FLOATS: usize = 12;

pub struct World {
    pub n: usize, pub nb: usize,
    /// Places for bodies there are right now, and the particles they hold; both grow as bodies are added, up to `most` bodies.
    pub slots: usize, pub maxp: usize, pub most: usize,
    pub s: f32, pub h: f32, pub side: f32, pub rc: f32,
    pub mats: [Material; 3],
    /// The material of each body: the plain one, thickened or thinned as that piece is.
    pub bmats: Vec<Material>,
    pub gravity: f32, pub iters: usize, pub visc_iters: usize, pub eps: f32, pub k_corr: f32,
    pub kill_y: f32,
    pub p: Vec<f32>, q: Vec<f32>, v: Vec<f32>, pub alive: Vec<u8>, pub scale: Vec<f32>,
    anc_kind: Vec<u8>, anc_id: Vec<i32>, anc_pt: Vec<f32>, anc_loc: Vec<f32>, anc_stick: Vec<f32>, anc_cool: Vec<f32>, anc_d: Vec<f32>, anc_axis: Vec<u8>,
    pub(crate) spr_n: Vec<u8>, pub(crate) spr_j: Vec<u32>, pub(crate) spr_l: Vec<f32>,
    rho: Vec<f32>, lam: Vec<f32>,
    dpx: Vec<f32>, dpy: Vec<f32>, dpz: Vec<f32>, damp: Vec<f32>,
    /// How well each particle is held by something that stands still (1 = it sticks to a block or a piston itself), and scratch for the next value.
    sup: Vec<f32>, sup_n: Vec<f32>,
    /// Per particle: when it appeared (world clock); whether it is still in flight (a stream or lump
    /// that has not touched anything yet falls freely instead of being slowed with the rest of its
    /// body); scratch for "landed this step".
    born: Vec<f32>, fly: Vec<u8>, land: Vec<u8>,
    act: Vec<u32>,
    c_start: Vec<u32>, c_fill: Vec<u32>, sorted: Vec<u32>, pkey: Vec<u32>, pbkt: Vec<u32>, cell: Vec<i32>,
    /// Neighbour lists: particle `i` owns entries `nbr_lo[i]..nbr_hi[i]` of `nbr` (the neighbour), `nw` (kernel value) and `ngx`, `ngy`, `ngz` (kernel gradient).
    /// For each place of the cell order: whether a cell's run of particles starts there.
    first: Vec<u8>,
    nbr: Vec<u32>, nbr_lo: Vec<u32>, nbr_hi: Vec<u32>, nw: Vec<f32>, ngx: Vec<f32>, ngy: Vec<f32>, ngz: Vec<f32>,
    /// Where the particles were when the density was taken (only kept where the wide registers are used: [`correct_wide`]).
    was: Vec<f32>,
    /// Scratch for the neighbour search: positions in cell order, and where each cell's particles start in that order.
    sp: Vec<f32>, runs: Vec<u32>,
    kp: f32, ks: f32, w0: f32, rho0: f32, wq: f32,
    pub bodies: Vec<Body>,
    st: Vec<SBox>,
    /// Which blocks lie in which cell of the world: see [`Cells`].
    st_cells: Cells,
    pub kin: Vec<KBox>,
    /// Per body: which moving boxes can reach it during the current step (body `b` has the entries `kin_from[b]..kin_from[b + 1]` of `kin_near`).
    kin_from: Vec<u32>, kin_near: Vec<u32>,
    /// The moving boxes by their id: (id, which box), in the order of the ids.
    kin_ids: Vec<(i32, u32)>,
    /// Per body: the bodies it has ever had springs with (so that freeing a body need not look through all the others).
    partners: Vec<Vec<u32>>,
    pub clock: f32, acc: f32, seed: u32, steps: u32,
    /// Fixed steps taken since the world was made, whether or not anything was awake.
    pub ticks: u32,
    pub last_pairs: usize,
    pub(crate) mesh_cache: std::sync::Mutex<crate::mesh::Cache>,
    rev: u32,
    /// Seconds spent in each phase of the step since the world was made (diagnostics).
    pub prof: [f64; 12],
    /// Whether the particle loops use the processor's wide registers (see [`wide_registers`]). The numbers are the same either way.
    pub wide: bool,
}

#[inline] fn smooth(t: f32) -> f32 { let t = t.clamp(0.0, 1.0); t * t * (3.0 - 2.0 * t) }
#[inline] fn hash3(x: i32, y: i32, z: i32, mask: u32) -> u32 {
    ((x.wrapping_mul(73856093)) ^ (y.wrapping_mul(19349663)) ^ (z.wrapping_mul(83492791))) as u32 & mask
}
#[inline] fn key3(x: i32, y: i32, z: i32) -> u32 { ((x & 1023) | ((y & 1023) << 10) | ((z & 1023) << 20)) as u32 }

/// What a box is, as far as pushing goo out of it goes.
const BLOCK: u8 = 0;
/// A piston part or a block in transit: goo caught inside is carried along on the leading face.
const PISTON: u8 = 1;
/// A creature: it never presses goo downwards (that would be into the ground it stands on); goo
/// under its feet is squeezed out sideways instead.
const CREATURE: u8 = 2;

/// Furthest a particle that finds itself inside a block is moved towards the way out in one step
/// (2.4 blocks a second). A block that turns up where goo is squeezes the goo out of itself; it
/// does not fire it out, which is what putting every particle on the nearest face at once did.
const EJECT: f32 = 0.04;
/// What [`resolve_box`] returns for a particle that is inside a block and on its way out of it.
const LEAVING: u8 = 4;

/// Push `q` out of the box if it is inside (box grown by `rc`). The exit face is the one the
/// particle came from last step when that is known, so fast pistons shove goo ahead of them
/// instead of letting it tunnel out the back. Returns the axis of the face it was put on, 3 if
/// the box was not touched, [`LEAVING`] if it is still inside a block. `solid` says whether a
/// point lies inside some (other) block.
#[inline]
fn resolve_box(q: &mut [f32; 3], p: &[f32; 3], bmin: &[f32; 3], bmax: &[f32; 3], pmin: &[f32; 3], pmax: &[f32; 3], rc: f32, front: u8, kind: u8, solid: impl Fn(&[f32; 3]) -> bool) -> u8 {
    // swept part: a particle that started beyond a face may not end up behind that face while it
    // is within the box's cross-section, however far a spring or anchor tried to drag it. When
    // several faces qualify (e.g. sagging a hair below a floor while crossing a block seam) the
    // shallowest one wins, so seams between neighbouring blocks never act as walls.
    let (mut best, mut ba, mut hi) = (f32::MAX, 3usize, false);
    for a in 0..3 {
        let (b, c) = ((a + 1) % 3, (a + 2) % 3);
        let within = q[b] > bmin[b] - rc && q[b] < bmax[b] + rc && q[c] > bmin[c] - rc && q[c] < bmax[c] + rc;
        if !within { continue; }
        if p[a] >= pmax[a] + rc - 1e-4 && q[a] < bmax[a] + rc { let pen = bmax[a] + rc - q[a]; if pen < best { best = pen; ba = a; hi = true; } }
        if p[a] <= pmin[a] - rc + 1e-4 && q[a] > bmin[a] - rc && !(kind == CREATURE && a == 1) { let pen = q[a] - (bmin[a] - rc); if pen < best { best = pen; ba = a; hi = false; } }
    }
    if ba < 3 { q[ba] = if hi { bmax[ba] + rc } else { bmin[ba] - rc }; return ba as u8; }
    // started inside (spawned there, squeezed in, or the box jumped onto it)
    for a in 0..3 { if q[a] <= bmin[a] - rc || q[a] >= bmax[a] + rc { return 3; } }
    let (mut best, mut ba, mut hi) = (f32::MAX, 0usize, false);
    if front != 0 {
        // a box with a front (a piston head) puts everything out in front of it
        ba = (front as usize - 1) / 2; hi = front & 1 == 1;
    } else {
        let mut moved = 0.0f32;
        if kind == PISTON { for a in 0..3 { let d = bmin[a] - pmin[a]; if d.abs() > moved { moved = d.abs(); ba = a; hi = d > 0.0; } } }
        if moved < 1e-6 {
            // nothing says which way: the nearest face
            for a in 0..3 {
                let pen_lo = q[a] - (bmin[a] - rc); if pen_lo < best && !(kind == CREATURE && a == 1) { best = pen_lo; ba = a; hi = false; }
                let pen_hi = (bmax[a] + rc) - q[a]; if pen_hi < best { best = pen_hi; ba = a; hi = true; }
            }
            if kind == BLOCK {
                // ... of a block: the nearest face with open space beyond it. The way out of a block that
                // stands on the floor is not down into the floor, where the floor would send it straight back.
                let mut e = *q;
                e[ba] = if hi { bmax[ba] + 1.5 * rc } else { bmin[ba] - 1.5 * rc };
                if solid(&e) {
                    let (mut open, mut oa, mut oh) = (f32::MAX, 3usize, false);
                    for a in 0..3 { for side in 0..2 {
                        let up = side == 1;
                        let pen = if up { (bmax[a] + rc) - q[a] } else { q[a] - (bmin[a] - rc) };
                        if pen >= open { continue; }
                        let mut e = *q;
                        e[a] = if up { bmax[a] + 1.5 * rc } else { bmin[a] - 1.5 * rc };
                        if !solid(&e) { open = pen; oa = a; oh = up; }
                    } }
                    if oa < 3 { ba = oa; hi = oh; } // walled in on every side: the nearest face after all
                }
            }
        } // else: a block being pushed carries it along on its leading face
    }
    let face = if hi { bmax[ba] + rc } else { bmin[ba] - rc };
    if kind == BLOCK {
        // out of a block at a walk: at least EJECT further than where the step began, never past the face
        q[ba] = if hi { face.min((p[ba] + EJECT).max(q[ba])) } else { face.max((p[ba] - EJECT).min(q[ba])) };
        if q[ba] != face { return LEAVING; }
    } else {
        q[ba] = face;
    }
    ba as u8
}

/// Particles (or pairs) handed to one thread at a time. Fixed, so the split never depends on the machine.
const CHUNK: usize = 256;
/// Entries of a neighbour list that the wide registers work out side by side.
const LANES: usize = 8;
/// Steps a body has to stay at rest before it is put to sleep.
const CALM_STEPS: u32 = 60;
/// Seconds a removed body takes to shrink away.
const FADE_TIME: f32 = 0.45;
const LAVA_FADE_TIME: f32 = 3.0;
/// Seconds goo that was let go by a box that stopped holding it stays unable to stick to anything.
const LET_GO: f32 = 0.6;
/// A thread of honey one particle thick holds nothing. Both ends of a spring count as that thin below
/// this share of the density at rest (a single file of particles a spacing apart comes to 0.34, the edge
/// of a sheet one particle thick to 0.5) ...
const THREAD_THIN: f32 = 0.42;
/// ... and such a spring goes once it is stretched past this many spacings.
const THREAD_REACH: f32 = 1.5;
/// How much of a particle's hold on the world reaches its neighbours, per neighbour and per step. Goo is thick
/// because it clings to what it lies on, and that only goes so far through the goo: a block's worth of it above
/// the floor is still held (six neighbours up, 0.53), a drop two blocks below what it dripped from is not, and
/// one that has come off altogether loses its hold in a fraction of a second and simply falls.
const SUPPORT_HOP: f32 = 0.9;
/// Hold below which a particle falls freely, and from which it is as thick as its body is.
const SUPPORT_LO: f32 = 0.1;
const SUPPORT_HI: f32 = 0.45;
/// Seconds overlapping goo is given to move apart gently after a block appears inside it.
const SOFT_TIME: f32 = 0.9;
/// Seconds a freshly emitted slime particle keeps bonding to slime of its own body that it touches.
const YOUNG: f32 = 2.0;
/// Seconds after which a particle no longer counts as in flight, whatever happened to it.
const FLIGHT_MAX: f32 = 4.0;
/// Particles in one layer of a stream (one in the middle, six around it).
const STREAM_LAYER: usize = 7;
/// ... and at most this many, where it bulges.
const STREAM_BULGE: usize = 12;
/// Filler points [`World::particles`] may add along stretched springs.
pub const PARTICLE_FILLERS: usize = 16_000;

/// The core's own worker threads: two thirds of the machine's processors (the game needs the rest),
/// twelve at most; on a machine with four or fewer, half of them. They only work while goo is
/// moving. `PHYSICSBRUHH_THREADS` overrides the number. `None` if the pool could not be made.
fn pool() -> Option<std::sync::Arc<rayon::ThreadPool>> {
    let mut pool = POOL.lock().unwrap_or_else(|e| e.into_inner());
    if pool.is_none() {
        let n = std::env::var("PHYSICSBRUHH_THREADS").ok().and_then(|v| v.parse::<usize>().ok()).unwrap_or_else(default_threads);
        *pool = Some(make_pool(n));
    }
    pool.as_ref().and_then(|p| p.clone())
}

/// The pool, once it has been asked for (the inner `None`: it could not be made).
static POOL: std::sync::Mutex<Option<Option<std::sync::Arc<rayon::ThreadPool>>>> = std::sync::Mutex::new(None);

fn default_threads() -> usize {
    let all = std::thread::available_parallelism().map(|n| n.get()).unwrap_or(2);
    if all <= 4 { (all / 2).max(1) } else { (all * 2 / 3).min(12) }
}

fn make_pool(n: usize) -> Option<std::sync::Arc<rayon::ThreadPool>> {
    rayon::ThreadPoolBuilder::new().num_threads(n.clamp(1, 64)).thread_name(|i| format!("PhysicsBruhh-core-{}", i)).build().ok().map(std::sync::Arc::new)
}

/// How many threads the solver uses.
pub fn threads() -> usize { pool().map(|p| p.current_num_threads()).unwrap_or(1) }

/// Makes the pool anew with `n` threads (0: as many as it would take by itself) and says how many
/// it has now. What is running on the old pool finishes there. The number of threads changes how
/// long a step takes and nothing else.
pub fn set_threads(n: usize) -> usize {
    let made = make_pool(if n == 0 { default_threads() } else { n });
    if made.is_some() { *POOL.lock().unwrap_or_else(|e| e.into_inner()) = Some(made); }
    threads()
}

/// Runs `f` on the core's thread pool, so parallel loops inside it use those threads.
pub fn on_pool<R: Send>(f: impl FnOnce() -> R + Send) -> R { match pool() { Some(p) => p.install(f), None => f() } }

/// An array several threads write at once, each to its own particles.
#[derive(Clone, Copy)]
struct Shared<T>(*mut T);
unsafe impl<T> Send for Shared<T> {}
unsafe impl<T> Sync for Shared<T> {}
impl<T> Shared<T> {
    fn of(v: &mut [T]) -> Shared<T> { Shared(v.as_mut_ptr()) }
    /// Safety: `i` is in bounds and no other thread touches that element during the loop.
    #[allow(clippy::mut_from_ref)]
    #[inline(always)]
    unsafe fn at(&self, i: usize) -> &mut T { &mut *self.0.add(i) }
}

/// Everything a particle can bump into.
struct Walls<'a> { st: &'a [SBox], cells: &'a Cells, kin: &'a [KBox], kin_from: &'a [u32], kin_near: &'a [u32], nb: usize, rc: f32, rho_max: f32 }

#[derive(Clone, Copy)]
struct Anchors { kind: Shared<u8>, id: Shared<i32>, pt: Shared<f32>, loc: Shared<f32>, stick: Shared<f32>, axis: Shared<u8>, fly: Shared<u8> }

impl Walls<'_> {
    /// Whether the point lies inside a block. A box is listed in every cell it lies in, so the point's own cell is enough.
    #[inline]
    fn solid(&self, pt: &[f32; 3]) -> bool {
        self.cells.at(pt[0].floor() as i32, pt[1].floor() as i32, pt[2].floor() as i32).iter().any(|&k| {
            let b = &self.st[k as usize];
            (0..3).all(|a| pt[a] > b.min[a] && pt[a] < b.max[a])
        })
    }
}

/// Which blocks lie in which cell of the world (a cell is one block), as a table hashed by the
/// cell: it is as large as the number of cells that have anything in them, however far apart they are.
#[derive(Default)]
struct Cells { keys: Vec<u64>, from: Vec<u32>, to: Vec<u32>, items: Vec<u32>, mask: usize }

impl Cells {
    const NONE: u64 = u64::MAX;
    #[inline] fn key(x: i32, y: i32, z: i32) -> u64 { (x as u32 as u64 & 0x1f_ffff) | (y as u32 as u64 & 0x1f_ffff) << 21 | (z as u32 as u64 & 0x1f_ffff) << 42 }
    #[inline] fn place(key: u64, mask: usize) -> usize { (key.wrapping_mul(0x9E37_79B9_7F4A_7C15) >> 32) as usize & mask }

    /// The boxes listed for the cell (x, y, z).
    #[inline]
    fn at(&self, x: i32, y: i32, z: i32) -> &[u32] {
        if self.keys.is_empty() { return &[]; }
        let key = Cells::key(x, y, z);
        let mut at = Cells::place(key, self.mask);
        loop {
            let k = self.keys[at];
            if k == key { return &self.items[self.from[at] as usize..self.to[at] as usize]; }
            if k == Cells::NONE { return &[]; }
            at = (at + 1) & self.mask;
        }
    }

    /// Lists every box in every cell it lies in.
    fn fill(&mut self, boxes: &[SBox]) {
        let mut listed: Vec<(u64, u32)> = Vec::with_capacity(boxes.len() * 2);
        for (bi, b) in boxes.iter().enumerate() {
            // only the cells the box itself lies in: a particle looks in every cell its own reach touches,
            // so a box next door is found exactly when the particle is close enough to matter
            let mut r = [[0i32; 2]; 3];
            for a in 0..3 { r[a][0] = b.min[a].floor() as i32; r[a][1] = (b.max[a].ceil() as i32 - 1).max(r[a][0]).min(r[a][0] + 64); }
            for z in r[2][0]..=r[2][1] { for y in r[1][0]..=r[1][1] { for x in r[0][0]..=r[0][1] { listed.push((Cells::key(x, y, z), bi as u32)); } } }
        }
        listed.sort_unstable(); // by cell, and within a cell in the order of the boxes
        let mut cells = 0;
        for k in 0..listed.len() { if k == 0 || listed[k].0 != listed[k - 1].0 { cells += 1; } }
        let mut size = 16usize; while size < cells * 2 { size <<= 1; }
        self.mask = size - 1;
        self.keys.clear(); self.keys.resize(size, Cells::NONE);
        self.from.clear(); self.from.resize(size, 0);
        self.to.clear(); self.to.resize(size, 0);
        self.items.clear();
        if boxes.is_empty() { self.keys.clear(); return; }
        let mut k = 0;
        while k < listed.len() {
            let key = listed[k].0;
            let mut at = Cells::place(key, self.mask);
            while self.keys[at] != Cells::NONE { at = (at + 1) & self.mask; }
            self.keys[at] = key; self.from[at] = self.items.len() as u32;
            while k < listed.len() && listed[k].0 == key { self.items.push(listed[k].1); k += 1; }
            self.to[at] = self.items.len() as u32;
        }
    }
}

/// Pushes particle `i` out of the colliders and lets it stick to the last one it touched.
/// Safety: writes only particle `i`'s own position and anchor.
#[inline]
unsafe fn collide(w: &Walls, an: Anchors, qs: Shared<f32>, p: &[f32], rho: f32, cool: f32, i: usize) {
    let o = i * 3;
    let mut q = [*qs.at(o), *qs.at(o + 1), *qs.at(o + 2)];
    let p = [p[o], p[o + 1], p[o + 2]];
    let rc = w.rc;
    let mut hit: u8 = 0; let mut hit_id = 0i32; let mut hit_stick = 0.0f32; let mut hit_min = [0.0f32; 3]; let mut hit_axis = 3u8;
    let mut leaving = false; // inside a block and on its way out: it has touched something, but that is no place to stick
    if !w.st.is_empty() {
        let mut r = [[0i32; 2]; 3];
        for a in 0..3 { r[a][0] = (q[a] - rc).floor() as i32; r[a][1] = (q[a] + rc).floor() as i32; }
        for z in r[2][0]..=r[2][1] { for y in r[1][0]..=r[1][1] { for x in r[0][0]..=r[0][1] {
            for &k in w.cells.at(x, y, z) {
                let b = &w.st[k as usize];
                let ax = resolve_box(&mut q, &p, &b.min, &b.max, &b.min, &b.max, rc, 0, BLOCK, |pt| w.solid(pt));
                if ax < 3 { hit = 1; hit_stick = b.stick; hit_axis = ax; } else if ax == LEAVING { leaving = true; }
            }
        } } }
    }
    // (only the moving boxes that can reach this particle's body during this step)
    let body = i / w.nb;
    for &k in &w.kin_near[w.kin_from[body] as usize..w.kin_from[body + 1] as usize] {
        let b = &w.kin[k as usize];
        let ax = resolve_box(&mut q, &p, &b.min, &b.max, &b.pmin, &b.pmax, rc, b.front, if b.id > 0 { PISTON } else { CREATURE }, |_| false);
        if ax < 3 { hit = 2; hit_id = b.id; hit_stick = b.stick; hit_min = b.min; hit_axis = ax; }
    }
    *qs.at(o) = q[0]; *qs.at(o + 1) = q[1]; *qs.at(o + 2) = q[2];
    if hit != 0 || leaving { *an.fly.at(i) = 0; } // it has touched something: no longer in free flight
    if hit != 0 && !leaving && *an.kind.at(i) == 0 && cool <= 0.0 && hit_stick > 0.0 && rho < w.rho_max {
        *an.kind.at(i) = hit; *an.id.at(i) = hit_id; *an.stick.at(i) = hit_stick; *an.axis.at(i) = hit_axis;
        for a in 0..3 { *an.pt.at(o + a) = q[a]; *an.loc.at(o + a) = q[a] - hit_min[a]; }
    }
}

/// Whether this processor has the wide registers (AVX2) that the particle loops can use: eight
/// neighbours at a time instead of one. `PHYSICSBRUHH_PLAIN` in the environment switches them off.
/// The results are the same to the last bit either way, so this is only a matter of speed.
pub fn wide_registers() -> bool {
    #[cfg(target_arch = "x86_64")]
    {
        static WIDE: OnceLock<bool> = OnceLock::new();
        *WIDE.get_or_init(|| std::env::var_os("PHYSICSBRUHH_PLAIN").is_none() && std::arch::is_x86_feature_detected!("avx2") && std::arch::is_x86_feature_detected!("popcnt"))
    }
    #[cfg(not(target_arch = "x86_64"))]
    { false }
}

/// The numbers the density constraint is worked out with.
#[derive(Clone, Copy)]
struct Kern { h: f32, h2: f32, ks: f32, kp: f32, w0: f32, ir0: f32, eps: f32, iwq: f32, kc: f32, max_d: f32 }

/// The neighbour lists: particle `i` has the entries `lo[i]..hi[i]` of `nbr`. `nbr` has [`LANES`]
/// entries to spare behind the last list (each of them a particle that exists), so that eight
/// entries can be read from anywhere in a list.
#[derive(Clone, Copy)]
struct Lists<'a> { nbr: &'a [u32], lo: &'a [u32], hi: &'a [u32] }

/// What is kept for every entry of the neighbour lists between the two halves of a round: the kernel and its gradient.
#[derive(Clone, Copy)]
struct Kept { w: Shared<f32>, gx: Shared<f32>, gy: Shared<f32>, gz: Shared<f32> }

/// Density and multiplier of one particle from its sums over the neighbours (kernel, gradient squared, gradient x, y, z).
/// Safety: particle `i` is this thread's alone.
#[inline(always)]
unsafe fn finish_density(i: usize, sums: [f32; 5], k: &Kern, mats: &[Material], nb: usize, rho: Shared<f32>, lam: Shared<f32>) {
    let [r, s2, sx, sy, sz] = sums;
    let m = &mats[i / nb];
    let mut c = r * k.ir0 - 1.0;
    if c < 0.0 { c = c.max(-m.coh_lim) * m.coh_k; }
    let den = (s2 + sx * sx + sy * sy + sz * sz) * k.ir0 * k.ir0 + k.eps;
    *rho.at(i) = r; *lam.at(i) = -c / den;
}

/// Density and its multiplier for the particles of `chunk`; the kernel value and gradient towards
/// every neighbour are kept for the correction.
/// Safety: the particles of `chunk` and the entries of their lists are this thread's alone.
#[allow(clippy::too_many_arguments)]
unsafe fn density_plain(chunk: &[u32], q: &[f32], l: Lists, k: &Kern, mats: &[Material], nb: usize, kept: Kept, rho: Shared<f32>, lam: Shared<f32>) {
    let (h, h2, ks, kp) = (k.h, k.h2, k.ks, k.kp);
    for &iu in chunk { let i = iu as usize; let o = i * 3;
        let (x, y, z) = (q[o], q[o + 1], q[o + 2]);
        let (mut r, mut s2, mut sx, mut sy, mut sz) = (k.w0, 0.0f32, 0.0f32, 0.0f32, 0.0f32);
        for e in l.lo[i] as usize..l.hi[i] as usize {
            let oj = l.nbr[e] as usize * 3;
            let (ex, ey, ez) = (x - q[oj], y - q[oj + 1], z - q[oj + 2]);
            let r2 = ex * ex + ey * ey + ez * ez;
            if r2 >= h2 { *kept.w.at(e) = 0.0; continue; }
            let d = r2.sqrt() + 1e-7; let t = h2 - r2; let u = h - d; let gm = -ks * u * u / d;
            let w = kp * t * t * t; let (gx, gy, gz) = (gm * ex, gm * ey, gm * ez);
            *kept.w.at(e) = w; *kept.gx.at(e) = gx; *kept.gy.at(e) = gy; *kept.gz.at(e) = gz;
            r += w; s2 += gx * gx + gy * gy + gz * gz;
            sx += gx; sy += gy; sz += gz;
        }
        finish_density(i, [r, s2, sx, sy, sz], k, mats, nb, rho, lam);
    }
}

/// The kernel and its gradient for eight neighbours of a particle at once, every operation the same
/// one as for a single neighbour ([`density_plain`]): `$q` is where the positions are read, `$nbr`
/// the neighbour lists, `$e` the first of the eight entries, `$n` how many of them belong to the
/// list, `$at` the particle's own place as three vectors, `$c` the constants (reach, reach squared,
/// minus the gradient's factor, the kernel's factor, 1e-7). Gives (within reach and of this list,
/// kernel, gradient x, y, z); the kernel is zero out of reach, the gradient is not masked.
#[cfg(target_arch = "x86_64")]
macro_rules! kernel_wide {
    ($q:expr, $nbr:expr, $e:expr, $n:expr, $at:expr, $c:expr) => {{
        let (qp, np, e, n): (*const f32, *const u32, usize, usize) = ($q, $nbr, $e, $n);
        let (vx, vy, vz) = $at;
        let (vh, vh2, vks, vkp, tiny) = $c;
        // where the eight neighbours are: x, y, z (and one number more) of each, then turned into eight x, eight y, eight z
        let at = |m: usize| qp.add(*np.add(e + m) as usize * 3);
        let v0 = _mm256_insertf128_ps::<1>(_mm256_castps128_ps256(_mm_loadu_ps(at(0))), _mm_loadu_ps(at(4)));
        let v1 = _mm256_insertf128_ps::<1>(_mm256_castps128_ps256(_mm_loadu_ps(at(1))), _mm_loadu_ps(at(5)));
        let v2 = _mm256_insertf128_ps::<1>(_mm256_castps128_ps256(_mm_loadu_ps(at(2))), _mm_loadu_ps(at(6)));
        let v3 = _mm256_insertf128_ps::<1>(_mm256_castps128_ps256(_mm_loadu_ps(at(3))), _mm_loadu_ps(at(7)));
        let (t0, t1, t2, t3) = (_mm256_unpacklo_ps(v0, v1), _mm256_unpackhi_ps(v0, v1), _mm256_unpacklo_ps(v2, v3), _mm256_unpackhi_ps(v2, v3));
        let ex = _mm256_sub_ps(vx, _mm256_shuffle_ps::<0x44>(t0, t2));
        let ey = _mm256_sub_ps(vy, _mm256_shuffle_ps::<0xEE>(t0, t2));
        let ez = _mm256_sub_ps(vz, _mm256_shuffle_ps::<0x44>(t1, t3));
        let r2 = _mm256_add_ps(_mm256_add_ps(_mm256_mul_ps(ex, ex), _mm256_mul_ps(ey, ey)), _mm256_mul_ps(ez, ez));
        // within reach (and an entry of this list at all: behind its end are other particles' entries)
        let mut near = _mm256_cmp_ps::<_CMP_NGE_UQ>(r2, vh2);
        if n < LANES { near = _mm256_and_ps(near, _mm256_castsi256_ps(_mm256_cmpgt_epi32(_mm256_set1_epi32(n as i32), _mm256_setr_epi32(0, 1, 2, 3, 4, 5, 6, 7)))); }
        let d = _mm256_add_ps(_mm256_sqrt_ps(r2), tiny);
        let t = _mm256_sub_ps(vh2, r2); let u = _mm256_sub_ps(vh, d);
        let gm = _mm256_div_ps(_mm256_mul_ps(_mm256_mul_ps(vks, u), u), d);
        let w = _mm256_and_ps(near, _mm256_mul_ps(_mm256_mul_ps(_mm256_mul_ps(vkp, t), t), t));
        (near, w, _mm256_mul_ps(gm, ex), _mm256_mul_ps(gm, ey), _mm256_mul_ps(gm, ez))
    }};
}

/// [`density_plain`] eight neighbours at a time: the kernel of all eight is worked out side by side
/// ([`kernel_wide`]); the sums then take the eight results one after the other, in the order of
/// the list. So the numbers are those of [`density_plain`] to the last bit (an entry out of reach
/// adds a zero here and nothing there, which is the same).
///
/// Nothing is kept for the correction here: [`correct_wide`] works the kernel out again. That is
/// less to do than it sounds and much less to carry: sixteen bytes for every neighbour of every
/// particle, written here and read there three times a step, were half a gigabyte a step for
/// eighty thousand particles, and with ten threads at it the memory, not the processor, set the
/// pace. Only the kernel of the last round is kept (`keep`), for the viscosity that follows.
/// Safety: as [`density_plain`]; the processor has AVX2; `q` has room for a load of four behind its last particle.
#[cfg(target_arch = "x86_64")]
#[target_feature(enable = "avx2")]
#[allow(clippy::too_many_arguments)]
unsafe fn density_wide(chunk: &[u32], q: &[f32], l: Lists, k: &Kern, mats: &[Material], nb: usize, keep: Option<Shared<f32>>, rho: Shared<f32>, lam: Shared<f32>) {
    use std::arch::x86_64::*;
    let (qp, np) = (q.as_ptr(), l.nbr.as_ptr());
    let consts = (_mm256_set1_ps(k.h), _mm256_set1_ps(k.h2), _mm256_set1_ps(-k.ks), _mm256_set1_ps(k.kp), _mm256_set1_ps(1e-7));
    for &iu in chunk { let i = iu as usize; let o = i * 3;
        let at = (_mm256_set1_ps(q[o]), _mm256_set1_ps(q[o + 1]), _mm256_set1_ps(q[o + 2]));
        let mut sums = [k.w0, 0.0f32, 0.0f32, 0.0f32, 0.0f32];
        let (mut e, hi) = (l.lo[i] as usize, l.hi[i] as usize);
        while e < hi {
            let n = (hi - e).min(LANES);
            let (near, w, gx, gy, gz) = kernel_wide!(qp, np, e, n, at, consts);
            let g2 = _mm256_and_ps(near, _mm256_add_ps(_mm256_add_ps(_mm256_mul_ps(gx, gx), _mm256_mul_ps(gy, gy)), _mm256_mul_ps(gz, gz)));
            let mut t = [[0.0f32; LANES]; 5];
            _mm256_storeu_ps(t[0].as_mut_ptr(), w); _mm256_storeu_ps(t[1].as_mut_ptr(), g2);
            _mm256_storeu_ps(t[2].as_mut_ptr(), _mm256_and_ps(near, gx)); _mm256_storeu_ps(t[3].as_mut_ptr(), _mm256_and_ps(near, gy)); _mm256_storeu_ps(t[4].as_mut_ptr(), _mm256_and_ps(near, gz));
            if let Some(kept) = keep {
                // (the end of a list: only its own entries are written)
                if n == LANES { _mm256_storeu_ps(kept.0.add(e), w); } else { for m in 0..n { *kept.at(e + m) = t[0][m]; } }
            }
            for m in 0..LANES { for c in 0..5 { sums[c] += t[c][m]; } }
            e += n;
        }
        finish_density(i, sums, k, mats, nb, rho, lam);
    }
}

/// Moves particle `i` by what its neighbours ask for, held back for goo that has just had a block dropped into it.
/// Safety: particle `i` is this thread's alone.
#[inline(always)]
#[allow(clippy::too_many_arguments)]
unsafe fn push(i: usize, mut x: f32, mut y: f32, mut z: f32, k: &Kern, bodies: &[Body], nb: usize, q: Shared<f32>) {
    // goo that has just had a block dropped into it is only allowed small corrections, growing back to normal
    let limit = k.max_d * (1.0 - 0.87 * (bodies[i / nb].soft * (1.0 / SOFT_TIME)).min(1.0));
    let l2 = x * x + y * y + z * z;
    if l2 > limit * limit { let sc = limit / l2.sqrt(); x *= sc; y *= sc; z *= sc; }
    let o = i * 3;
    *q.at(o) += x; *q.at(o + 1) += y; *q.at(o + 2) += z;
}

/// The position correction of the density constraint for the particles of `chunk`.
/// Safety: the particles of `chunk` are this thread's alone.
#[allow(clippy::too_many_arguments)]
unsafe fn correct_plain(chunk: &[u32], l: Lists, nw: &[f32], gx: &[f32], gy: &[f32], gz: &[f32], lam: &[f32], bodies: &[Body], nb: usize, k: &Kern, q: Shared<f32>) {
    let (iwq, kc, ir0) = (k.iwq, k.kc, k.ir0);
    for &iu in chunk { let i = iu as usize;
        let (mut x, mut y, mut z) = (0.0f32, 0.0f32, 0.0f32);
        let li = lam[i];
        for e in l.lo[i] as usize..l.hi[i] as usize {
            let w = nw[e]; if w == 0.0 { continue; }
            let mut qq = w * iwq; qq *= qq; qq *= qq;
            let f = (li + lam[l.nbr[e] as usize] - kc * qq) * ir0;
            x += f * gx[e]; y += f * gy[e]; z += f * gz[e];
        }
        push(i, x, y, z, k, bodies, nb, q);
    }
}

/// [`correct_plain`] eight neighbours at a time, with the same numbers to the last bit (see
/// [`density_wide`]). The kernel and its gradient are worked out again, from where the particles
/// were when the density was taken (`was`: this pass moves them).
/// Safety: as [`correct_plain`]; the processor has AVX2; `was` has room for a load of four behind its last particle.
#[cfg(target_arch = "x86_64")]
#[target_feature(enable = "avx2")]
#[allow(clippy::too_many_arguments)]
unsafe fn correct_wide(chunk: &[u32], was: &[f32], l: Lists, lam: &[f32], bodies: &[Body], nb: usize, k: &Kern, q: Shared<f32>) {
    use std::arch::x86_64::*;
    let (qp, np, lp) = (was.as_ptr(), l.nbr.as_ptr(), lam.as_ptr());
    let consts = (_mm256_set1_ps(k.h), _mm256_set1_ps(k.h2), _mm256_set1_ps(-k.ks), _mm256_set1_ps(k.kp), _mm256_set1_ps(1e-7));
    let (viwq, vkc, vir0, zero) = (_mm256_set1_ps(k.iwq), _mm256_set1_ps(k.kc), _mm256_set1_ps(k.ir0), _mm256_setzero_ps());
    for &iu in chunk { let i = iu as usize; let o = i * 3;
        let at = (_mm256_set1_ps(was[o]), _mm256_set1_ps(was[o + 1]), _mm256_set1_ps(was[o + 2]));
        let vli = _mm256_set1_ps(lam[i]);
        let mut sums = [0.0f32; 3];
        let (mut e, hi) = (l.lo[i] as usize, l.hi[i] as usize);
        while e < hi {
            let n = (hi - e).min(LANES);
            let (near, w, gx, gy, gz) = kernel_wide!(qp, np, e, n, at, consts);
            let lj = |m: usize| *lp.add(*np.add(e + m) as usize);
            let lj = _mm256_setr_ps(lj(0), lj(1), lj(2), lj(3), lj(4), lj(5), lj(6), lj(7));
            let there = _mm256_and_ps(near, _mm256_cmp_ps::<_CMP_NEQ_UQ>(w, zero));
            let mut qq = _mm256_mul_ps(w, viwq); qq = _mm256_mul_ps(qq, qq); qq = _mm256_mul_ps(qq, qq);
            let f = _mm256_mul_ps(_mm256_sub_ps(_mm256_add_ps(vli, lj), _mm256_mul_ps(vkc, qq)), vir0);
            let mut t = [[0.0f32; LANES]; 3];
            for (c, g) in [gx, gy, gz].into_iter().enumerate() { _mm256_storeu_ps(t[c].as_mut_ptr(), _mm256_and_ps(there, _mm256_mul_ps(f, g))); }
            for m in 0..LANES { for c in 0..3 { sums[c] += t[c][m]; } }
            e += n;
        }
        push(i, sums[0], sums[1], sums[2], k, bodies, nb, q);
    }
}

/// For each pattern of eight yes/no answers: the places of the yeses, in order (see [`near_wide`]).
#[cfg(target_arch = "x86_64")]
static PACK: [[u32; 8]; 256] = {
    let mut t = [[0u32; 8]; 256];
    let mut m = 0;
    while m < 256 {
        let (mut n, mut b) = (0, 0);
        while b < 8 { if m >> b & 1 != 0 { t[m][n] = b as u32; n += 1; } b += 1; }
        m += 1;
    }
    t
};

/// The neighbours of the particle at place `k` of the cell order among the candidates in `around`
/// (ranges of that order), eight candidates at a time: written to `out` from entry `n` on, in the
/// order of the candidates. Returns the new `n`.
/// Safety: the processor has AVX2; `sx`, `sy`, `sz` and `sorted` can be read eight entries past any
/// candidate; `out` has room for all the candidates and eight entries more.
#[cfg(target_arch = "x86_64")]
#[target_feature(enable = "avx2,popcnt")]
#[allow(clippy::too_many_arguments)]
unsafe fn near_wide(x: f32, y: f32, z: f32, k: usize, h2: f32, around: &[(u32, u32)], sx: *const f32, sy: *const f32, sz: *const f32, sorted: *const u32, out: *mut u32, mut n: usize) -> usize {
    use std::arch::x86_64::*;
    let (vx, vy, vz, vh2) = (_mm256_set1_ps(x), _mm256_set1_ps(y), _mm256_set1_ps(z), _mm256_set1_ps(h2));
    for &(a, b) in around {
        let (mut m, b) = (a as usize, b as usize);
        while m < b {
            let (ex, ey, ez) = (_mm256_sub_ps(vx, _mm256_loadu_ps(sx.add(m))), _mm256_sub_ps(vy, _mm256_loadu_ps(sy.add(m))), _mm256_sub_ps(vz, _mm256_loadu_ps(sz.add(m))));
            let r2 = _mm256_add_ps(_mm256_add_ps(_mm256_mul_ps(ex, ex), _mm256_mul_ps(ey, ey)), _mm256_mul_ps(ez, ez));
            let mut bits = _mm256_movemask_ps(_mm256_cmp_ps::<_CMP_LT_OQ>(r2, vh2)) as u32;
            if b - m < LANES { bits &= (1u32 << (b - m)) - 1; }      // behind the end of the range
            if k >= m && k < m + LANES { bits &= !(1u32 << (k - m)); } // the particle itself
            // those within reach are moved to the front and all eight written; the next lot overwrites the rest
            let packed = _mm256_permutevar8x32_epi32(_mm256_loadu_si256(sorted.add(m) as *const __m256i), _mm256_loadu_si256(PACK[bits as usize].as_ptr() as *const __m256i));
            _mm256_storeu_si256(out.add(n) as *mut __m256i, packed);
            n += bits.count_ones() as usize;
            m += LANES;
        }
    }
    n
}

/// Two awake bodies with springs between them: `a` is the lower slot; `runs` are the springs from
/// `a` into `b` and from `b` into `a`, each as (which list, from, to) into the step's lists of springs across bodies.
struct Pair { a: u32, b: u32, runs: [(u32, u32, u32); 2] }

/// One spring pulls its two ends towards its rest length `l`: half each, or all of it on particle
/// `i` if the far end `j` is `fixed` (then that end is taken where it was before the step).
/// Safety: no other thread touches particle `i`, nor `j` unless it is fixed.
#[inline(always)]
unsafe fn relax(q: Shared<f32>, p: &[f32], i: usize, j: usize, l: f32, ksp: f32, fixed: bool) {
    let (oi, oj) = (i * 3, j * 3);
    let (jx, jy, jz) = if fixed { (p[oj], p[oj + 1], p[oj + 2]) } else { (*q.at(oj), *q.at(oj + 1), *q.at(oj + 2)) };
    let (ex, ey, ez) = (*q.at(oi) - jx, *q.at(oi + 1) - jy, *q.at(oi + 2) - jz);
    let r = (ex * ex + ey * ey + ez * ez).sqrt() + 1e-7;
    let c = ksp * (r - l) / r * if fixed { 1.0 } else { 0.5 };
    *q.at(oi) -= ex * c; *q.at(oi + 1) -= ey * c; *q.at(oi + 2) -= ez * c;
    if !fixed { *q.at(oj) += ex * c; *q.at(oj + 1) += ey * c; *q.at(oj + 2) += ez * c; }
}

/// How far lava of this age (seconds since it left its block) has cooled, 0 (as it came out) to 1
/// (crusted over): a skin forms within ten seconds, the rest takes a minute and more.
#[inline] pub fn lava_cold(age: f32) -> f32 { 0.35 * smooth((age - 1.0) * 0.1) + 0.65 * smooth((age - 10.0) * (1.0 / 70.0)) }

#[inline] fn lava_hash(x: u32, y: u32, z: u32, s: u32) -> f32 {
    let mut h = x.wrapping_mul(0x9E37_79B1) ^ y.wrapping_mul(0x85EB_CA77) ^ z.wrapping_mul(0xC2B2_AE3D) ^ s.wrapping_mul(0x27D4_EB2F);
    h ^= h >> 15; h = h.wrapping_mul(0x2C1B_3C6D); h ^= h >> 12;
    (h & 0xff) as f32 * (1.0 / 255.0)
}

/// What a particle of lava says about itself besides where it is, added to its size: 8 (lava),
/// 16 times a pattern (0..63) and 1024 times its heat (0..63).
/// The pattern is a blotchy noise over the block the lava came from, so it belongs to the lava and
/// travels, stretches and tears with it; whoever draws the lava makes the crust out of it. The heat
/// is what is left of it after cooling, more where the lava is on the move.
#[inline] pub fn lava_bits(body: usize, k: usize, n: usize, cold: f32, speed: f32) -> f32 {
    let (a, b, c) = ((k / (n * n)) as f32, ((k / n) % n) as f32, (k % n) as f32);
    // blotches a third of a block wide
    let (fx, fy, fz) = (a * 0.25, b * 0.25, c * 0.25);
    let (ix, iy, iz) = (fx as u32, fy as u32, fz as u32);
    let (tx, ty, tz) = (smooth(fx - ix as f32), smooth(fy - iy as f32), smooth(fz - iz as f32));
    let s = body as u32;
    let at = |dx: u32, dy: u32, dz: u32| lava_hash(ix + dx, iy + dy, iz + dz, s);
    let x00 = at(0, 0, 0) + (at(1, 0, 0) - at(0, 0, 0)) * tx;
    let x10 = at(0, 1, 0) + (at(1, 1, 0) - at(0, 1, 0)) * tx;
    let x01 = at(0, 0, 1) + (at(1, 0, 1) - at(0, 0, 1)) * tx;
    let x11 = at(0, 1, 1) + (at(1, 1, 1) - at(0, 1, 1)) * tx;
    let y0 = x00 + (x10 - x00) * ty;
    let y1 = x01 + (x11 - x01) * ty;
    let blotch = y0 + (y1 - y0) * tz;
    // and a grain of its own on top
    let grain = lava_hash(k as u32, 7, 13, s);
    let pattern = ((blotch * 0.8 + grain * 0.2).clamp(0.0, 1.0) * 63.0 + 0.5) as u32;
    let warm = (1.0 - cold) + cold * 0.5 * (speed * (1.0 / 0.8)).min(1.0);
    let heat = (warm.clamp(0.0, 1.0) * 63.0 + 0.5) as u32;
    (8 + 16 * pattern + 1024 * heat) as f32
}

fn spring_exists(spr_n: &[u8], spr_j: &[u32], a: usize, b: usize) -> bool {
    let (na, nb) = (spr_n[a] as usize, spr_n[b] as usize);
    spr_j[a * MAX_SPR..a * MAX_SPR + na].iter().any(|&j| j as usize == b) || spr_j[b * MAX_SPR..b * MAX_SPR + nb].iter().any(|&j| j as usize == a)
}

impl World {
    /// A world for at most `max_bodies` bodies of `n`^3 particles each.
    pub fn new(n: usize, max_bodies: usize) -> World { World::with_room(n, max_bodies, max_bodies) }

    /// A world with room for `first` bodies to begin with, which grows as bodies are added, up to `most`.
    pub fn with_room(n: usize, first: usize, most: usize) -> World {
        let n = n.clamp(6, 16);
        let side = 0.97f32;
        let s = side / n as f32;
        let nb = n * n * n;
        let h = 2.05 * s;
        let h2 = h * h;
        let kp = 315.0 / (64.0 * std::f32::consts::PI * h.powi(9));
        let ks = 45.0 / (std::f32::consts::PI * h.powi(6));
        let w0 = kp * h2 * h2 * h2;
        let mut rho0 = 0.0f32;
        let m = (h / s).ceil() as i32;
        for a in -m..=m { for b in -m..=m { for c in -m..=m {
            let r2 = (a * a + b * b + c * c) as f32 * s * s;
            if r2 < h2 { let t = h2 - r2; rho0 += kp * t * t * t; }
        } } }
        rho0 *= 0.97;
        let wq = { let r = 0.25 * h; let t = h2 - r * r; kp * t * t * t };
        let mut w = World {
            n, nb, slots: 0, maxp: 0, most: most.max(first).max(1), s, h, side, rc: s * 0.5,
            mats: [Material::honey(), Material::slime(), Material::lava()],
            bmats: Vec::new(),
            gravity: 9.8, iters: 3, visc_iters: 1, eps: 60.0, k_corr: 2.0e-4, kill_y: -64.0,
            p: Vec::new(), q: Vec::new(), v: Vec::new(), alive: Vec::new(), scale: Vec::new(),
            anc_kind: Vec::new(), anc_id: Vec::new(), anc_pt: Vec::new(), anc_loc: Vec::new(), anc_stick: Vec::new(), anc_cool: Vec::new(), anc_d: Vec::new(), anc_axis: Vec::new(),
            spr_n: Vec::new(), spr_j: Vec::new(), spr_l: Vec::new(),
            rho: Vec::new(), lam: Vec::new(),
            dpx: Vec::new(), dpy: Vec::new(), dpz: Vec::new(), damp: Vec::new(),
            sup: Vec::new(), sup_n: Vec::new(),
            born: Vec::new(), fly: Vec::new(), land: Vec::new(),
            act: Vec::new(),
            c_start: Vec::new(), c_fill: Vec::new(), sorted: Vec::new(), pkey: Vec::new(), pbkt: Vec::new(), cell: Vec::new(),
            first: Vec::new(), nbr: Vec::new(), nbr_lo: Vec::new(), nbr_hi: Vec::new(), nw: Vec::new(), ngx: Vec::new(), ngy: Vec::new(), ngz: Vec::new(), was: Vec::new(), sp: Vec::new(), runs: Vec::new(),
            kp, ks, w0, rho0, wq,
            bodies: Vec::new(),
            st: Vec::new(), st_cells: Cells::default(),
            kin: Vec::new(), kin_from: Vec::new(), kin_near: Vec::new(), kin_ids: Vec::new(), partners: Vec::new(),
            clock: 0.0, acc: 0.0, seed: 0x9E3779B9, steps: 0, ticks: 0, last_pairs: 0, prof: [0.0; 12], mesh_cache: Default::default(), rev: 1, wide: wide_registers(),
        };
        w.grow(first.max(1));
        w
    }

    /// Makes room for `slots` bodies (never fewer than there are).
    fn grow(&mut self, slots: usize) {
        if slots <= self.slots { return; }
        let maxp = self.nb * slots;
        self.p.resize(maxp * 3, 0.0); self.v.resize(maxp * 3, 0.0);
        self.q.resize(maxp * 3 + LANES, 0.0); // (room to load four numbers from the last particle)
        self.was.resize(maxp * 3 + LANES, 0.0);
        self.alive.resize(maxp, 0); self.scale.resize(maxp, 0.0);
        self.anc_kind.resize(maxp, 0); self.anc_id.resize(maxp, 0); self.anc_pt.resize(maxp * 3, 0.0); self.anc_loc.resize(maxp * 3, 0.0);
        self.anc_stick.resize(maxp, 0.0); self.anc_cool.resize(maxp, 0.0); self.anc_d.resize(maxp * 3, 0.0); self.anc_axis.resize(maxp, 3);
        self.spr_n.resize(maxp, 0); self.spr_j.resize(maxp * MAX_SPR, 0); self.spr_l.resize(maxp * MAX_SPR, 0.0);
        self.rho.resize(maxp, 0.0); self.lam.resize(maxp, 0.0);
        self.dpx.resize(maxp, 0.0); self.dpy.resize(maxp, 0.0); self.dpz.resize(maxp, 0.0); self.damp.resize(maxp, 0.0);
        self.sup.resize(maxp, 0.0); self.sup_n.resize(maxp, 0.0);
        self.born.resize(maxp, 0.0); self.fly.resize(maxp, 0); self.land.resize(maxp, 0);
        self.sorted.resize(maxp + LANES, 0); // (room to read eight candidates from anywhere)
        self.pkey.resize(maxp, 0); self.pbkt.resize(maxp, 0); self.cell.resize(maxp * 3, 0);
        self.nbr_lo.resize(maxp, 0); self.nbr_hi.resize(maxp, 0);
        self.bodies.resize(slots, Body::default()); self.bmats.resize(slots, Material::honey());
        self.partners.resize(slots, Vec::new());
        self.slots = slots; self.maxp = maxp;
    }

    fn rnd(&mut self) -> f32 { self.seed = self.seed.wrapping_mul(1664525).wrapping_add(1013904223); ((self.seed >> 8) & 0xffff) as f32 / 65536.0 }

    fn free_body(&mut self, b: usize) {
        self.bodies[b] = Body::default();
        let (lo, hi) = (b * self.nb, (b + 1) * self.nb);
        for i in lo..hi { self.alive[i] = 0; self.scale[i] = 0.0; self.spr_n[i] = 0; self.anc_kind[i] = 0; }
        // drop springs other bodies still hold to these particles (the slot may be reused); only a body that
        // has had springs with this one can hold any
        for c in std::mem::take(&mut self.partners[b]) {
            let c = c as usize;
            if c == b { continue; }
            for i in c * self.nb..(c + 1) * self.nb {
                let mut k = 0usize;
                while k < self.spr_n[i] as usize {
                    let j = self.spr_j[i * MAX_SPR + k] as usize;
                    if j >= lo && j < hi {
                        let last = i * MAX_SPR + self.spr_n[i] as usize - 1;
                        self.spr_j[i * MAX_SPR + k] = self.spr_j[last]; self.spr_l[i * MAX_SPR + k] = self.spr_l[last]; self.spr_n[i] -= 1;
                    } else { k += 1; }
                }
            }
        }
    }

    /// A free body slot. If there is none, room is made for more; and once there are as many as there may be,
    /// the oldest sleeping body (else the oldest one) makes room.
    fn take_slot(&mut self) -> Option<usize> {
        let mut slot = self.bodies.iter().position(|b| b.state == 0);
        if slot.is_none() && self.slots < self.most {
            slot = Some(self.slots);
            self.grow((self.slots + (self.slots / 2).max(8)).min(self.most));
        }
        if slot.is_none() {
            let mut best: Option<usize> = None; let mut bt = f32::MAX;
            for (i, b) in self.bodies.iter().enumerate() { if !b.awake && b.born < bt { bt = b.born; best = Some(i); } }
            if best.is_none() { for (i, b) in self.bodies.iter().enumerate() { if b.born < bt { bt = b.born; best = Some(i); } } }
            if let Some(b) = best { self.free_body(b); slot = Some(b); }
        }
        slot
    }

    /// Puts one particle into a free place of body `b`. Returns false if the body is full.
    fn spawn(&mut self, b: usize, pos: [f32; 3], vel: [f32; 3], flying: bool) -> bool {
        let (lo, hi) = (b * self.nb, (b + 1) * self.nb);
        let i = match (lo..hi).find(|&i| self.alive[i] == 0) { Some(i) => i, None => return false };
        let o = i * 3;
        for a in 0..3 { self.p[o + a] = pos[a]; self.q[o + a] = pos[a]; self.v[o + a] = vel[a]; }
        self.alive[i] = 1; self.scale[i] = 1.0; self.spr_n[i] = 0; self.anc_kind[i] = 0; self.anc_cool[i] = 0.0;
        self.born[i] = self.clock; self.fly[i] = flying as u8;
        self.sup[i] = if flying { 0.0 } else { 1.0 };
        true
    }

    /// Adds a body with nothing in it yet, at the block cell (x, y, z); goo arrives through [`World::emit`].
    pub fn add_empty(&mut self, mat: u8, x: f32, y: f32, z: f32) -> i32 { self.add_empty_thick(mat, x, y, z, 1.0) }

    /// [`World::add_empty`] for goo thicker or thinner than usual, see [`Material::thick`].
    pub fn add_empty_thick(&mut self, mat: u8, x: f32, y: f32, z: f32, thick: f32) -> i32 {
        let mat = material(mat);
        let slot = match self.take_slot() { Some(s) => s, None => return -1 };
        self.rev = self.rev.wrapping_add(1);
        self.bmats[slot] = self.mats[mat as usize].thick(thick);
        self.bodies[slot] = Body {
            state: 1, mat, awake: true, age: self.bmats[slot].melt_time + 2.0, born: self.clock, fade: 1.0, rev: self.rev, thick,
            bb: [x, y, z, x + 1.0, y + 1.0, z + 1.0], ..Body::default()
        };
        slot as i32
    }

    /// Adds `count` particles of goo already lying at rest on the floor of the block cell (x, y, z):
    /// what a stream left behind, seen again later. Honey lies as a puddle, slime as a lump.
    pub fn add_resting(&mut self, mat: u8, x: f32, y: f32, z: f32, count: u32) -> i32 { self.add_resting_thick(mat, x, y, z, count, 1.0) }

    /// [`World::add_resting`] for goo thicker or thinner than usual, see [`Material::thick`].
    pub fn add_resting_thick(&mut self, mat: u8, x: f32, y: f32, z: f32, count: u32, thick: f32) -> i32 {
        let mat = material(mat);
        let count = (count as usize).min(self.nb);
        let slot = match self.take_slot() { Some(s) => s, None => return -1 };
        let (n, s, side, nb) = (self.n, self.s, self.side, self.nb);
        let (cx, cz) = (x + 0.5, z + 0.5);
        self.rev = self.rev.wrapping_add(1);
        self.bmats[slot] = self.mats[mat as usize].thick(thick);
        self.bodies[slot] = Body {
            state: 1, mat, awake: true, age: self.bmats[slot].melt_time + 8.0, born: self.clock, fade: 1.0, fresh: mat == SLIME, rev: self.rev, thick,
            bb: [x - 0.5, y - 0.2, z - 0.5, x + 1.5, y + 1.3, z + 1.5], ..Body::default()
        };
        if mat == SLIME {
            // the places of a block nearest to the middle of its base: a rounded lump
            let mut sites: Vec<(f32, [f32; 3])> = Vec::with_capacity(nb);
            for a in 0..n { for b in 0..n { for c in 0..n {
                let o = [(a as f32 + 0.5) * s - side * 0.5, (b as f32 + 0.5) * s, (c as f32 + 0.5) * s - side * 0.5];
                sites.push((o[0] * o[0] + o[1] * o[1] * 1.6 + o[2] * o[2], o));
            } } }
            sites.sort_by(|a, b| a.0.total_cmp(&b.0));
            for k in 0..count { let o = sites[k].1; self.spawn(slot, [cx + o[0], y + o[1], cz + o[2]], [0.0; 3], false); }
        } else {
            let reach = 1.27 * (count as f32 / nb as f32).sqrt();
            for k in 0..count {
                let rad = reach * self.rnd().sqrt(); let ang = self.rnd() * std::f32::consts::TAU;
                let lift = (k % 2) as f32 * s * 0.9 + (self.rnd() - 0.5) * s * 0.1;
                self.spawn(slot, [cx + rad * ang.cos(), y + self.rc + lift, cz + rad * ang.sin()], [0.0; 3], false);
            }
        }
        self.bodies[slot].count = count as u32;
        slot as i32
    }

    /// Goo in the body: particles that exist plus those of a stream that are still to come out.
    pub fn amount(&self, b: usize) -> u32 {
        if b >= self.slots || self.bodies[b].state != 1 { return 0; }
        (b * self.nb..(b + 1) * self.nb).filter(|&i| self.alive[i] != 0).count() as u32 + self.bodies[b].pending
    }

    /// Lets `count` more particles into body `b`, leaving `pos` with velocity `vel`: as a stream that
    /// comes out layer by layer over the next steps, or (`lump`) all at once as a rounded lump around
    /// `pos`. Returns how many the body had room for.
    pub fn emit(&mut self, b: usize, pos: [f32; 3], vel: [f32; 3], count: u32, lump: bool) -> u32 {
        if b >= self.slots || self.bodies[b].state != 1 { return 0; }
        let room = self.nb as u32 - self.amount(b).min(self.nb as u32);
        let count = count.min(room);
        if count == 0 || !pos.iter().chain(vel.iter()).all(|v| v.is_finite()) { return 0; }
        let m = self.bmats[b];
        {
            let bd = &mut self.bodies[b];
            bd.awake = true; bd.calm = 0;
            bd.age = bd.age.min(m.melt_time + 2.0); // fresh goo makes the whole piece runny again
            bd.young_until = self.clock + YOUNG + 1.0;
        }
        if !lump {
            let bd = &mut self.bodies[b];
            if bd.pending == 0 { bd.emit_total = 0; bd.emit_time = 0.0; }
            bd.pending += count; bd.emit_total += count; bd.emit_pos = pos; bd.emit_vel = vel;
            return count;
        }
        // a rounded lump: the places of a small cube nearest to its middle
        let s = self.s;
        let k = (count as f32).cbrt().ceil() as i32;
        let mut sites: Vec<(f32, [f32; 3])> = Vec::with_capacity((k * k * k) as usize);
        for a in 0..k { for bb in 0..k { for c in 0..k {
            let o = [(a as f32 - (k - 1) as f32 * 0.5) * s, (bb as f32 - (k - 1) as f32 * 0.5) * s, (c as f32 - (k - 1) as f32 * 0.5) * s];
            sites.push((o[0] * o[0] + o[1] * o[1] + o[2] * o[2], o));
        } } }
        sites.sort_by(|a, b| a.0.total_cmp(&b.0));
        // goo that is already where the lump appears: everything there takes it easy for a moment
        let r = k as f32 * s * 0.5 + s;
        let mut crowded = false;
        for c in 0..self.slots {
            let bd = &self.bodies[c];
            if bd.state != 1 || (0..3).any(|a| bd.bb[a] > pos[a] + r || bd.bb[a + 3] < pos[a] - r) { continue; }
            let touching = (c * self.nb..(c + 1) * self.nb).any(|i| self.alive[i] != 0 && (0..3).all(|a| (self.p[i * 3 + a] - pos[a]).abs() < r));
            if touching { crowded = true; let bd = &mut self.bodies[c]; bd.soft = SOFT_TIME; bd.awake = true; bd.calm = 0; }
        }
        if crowded { self.bodies[b].soft = SOFT_TIME; }
        let mut done = 0;
        for site in sites.iter().take(count as usize) {
            let o = site.1;
            if !self.spawn(b, [pos[0] + o[0], pos[1] + o[1], pos[2] + o[2]], vel, true) { break; }
            done += 1;
        }
        done
    }

    /// Removes the `count` particles of body `b` nearest to the point `at` (a bottle dipped in, a
    /// handful torn off). Returns how many were removed.
    pub fn take(&mut self, b: usize, at: [f32; 3], count: u32) -> u32 {
        if b >= self.slots || self.bodies[b].state != 1 || count == 0 { return 0; }
        let (lo, hi) = (b * self.nb, (b + 1) * self.nb);
        let mut near: Vec<(f32, usize)> = (lo..hi).filter(|&i| self.alive[i] != 0)
            .map(|i| ((0..3).map(|a| (self.p[i * 3 + a] - at[a]).powi(2)).sum::<f32>(), i)).collect();
        near.sort_by(|a, b| a.0.total_cmp(&b.0).then(a.1.cmp(&b.1)));
        near.truncate(count as usize);
        // what a stream has not let out yet goes first when the piece itself is not enough
        let short = count - near.len() as u32;
        let bd = &mut self.bodies[b];
        let from_stream = short.min(bd.pending);
        bd.pending -= from_stream;
        bd.awake = true; bd.calm = 0;
        let mut gone = vec![false; self.nb];
        for &(_, i) in &near { self.alive[i] = 0; self.scale[i] = 0.0; self.spr_n[i] = 0; self.anc_kind[i] = 0; self.fly[i] = 0; gone[i - lo] = true; }
        // springs to what was taken go at once: the places may be filled again before the solver would notice
        // (they are held by the body itself and by bodies it has had springs with)
        let mut holders: Vec<usize> = self.partners[b].iter().map(|&c| c as usize).filter(|&c| c != b).collect();
        holders.push(b);
        for i in holders.into_iter().flat_map(|c| c * self.nb..(c + 1) * self.nb) {
            let mut k = 0usize;
            while k < self.spr_n[i] as usize {
                let j = self.spr_j[i * MAX_SPR + k] as usize;
                if j >= lo && j < hi && gone[j - lo] {
                    let last = i * MAX_SPR + self.spr_n[i] as usize - 1;
                    self.spr_j[i * MAX_SPR + k] = self.spr_j[last]; self.spr_l[i * MAX_SPR + k] = self.spr_l[last]; self.spr_n[i] -= 1;
                } else { k += 1; }
            }
        }
        self.rev = self.rev.wrapping_add(1);
        self.bodies[b].rev = self.rev;
        near.len() as u32 + from_stream
    }

    /// Adds a body occupying the block cell whose minimum corner is (x, y, z).
    /// `age` > 6 s spawns honey already melted (used when an old puddle comes back into view).
    pub fn add_body(&mut self, mat: u8, x: f32, y: f32, z: f32, dirx: f32, dirz: f32, age: f32) -> i32 { self.add_body_thick(mat, x, y, z, dirx, dirz, age, 1.0) }

    /// [`World::add_body`] for goo thicker or thinner than usual, see [`Material::thick`].
    pub fn add_body_thick(&mut self, mat: u8, x: f32, y: f32, z: f32, dirx: f32, dirz: f32, age: f32, thick: f32) -> i32 {
        let mat = material(mat);
        let slot = match self.take_slot() { Some(s) => s, None => return -1 };
        self.bmats[slot] = self.mats[mat as usize].thick(thick);
        let m = self.bmats[slot];
        let (n, s, side, nb) = (self.n, self.s, self.side, self.nb);
        let base = slot * nb;
        let puddle = mat != SLIME && age > 6.0;
        let (cx, cz) = (x + 0.5, z + 0.5);
        let dl = (dirx * dirx + dirz * dirz).sqrt();
        let (dx, dz) = if dl > 1e-4 { (dirx / dl, dirz / dl) } else { (1.0, 0.0) };
        let mut k = 0usize;
        for a in 0..n { for b in 0..n { for c in 0..n {
            let i = base + k; k += 1;
            let (jx, jy, jz) = (self.rnd() - 0.5, self.rnd() - 0.5, self.rnd() - 0.5);
            let (px, py, pz, vx, vy, vz);
            if puddle {
                let layer = (k % 2) as f32;
                let rad = 1.27 * (self.rnd()).sqrt(); let ang = self.rnd() * std::f32::consts::TAU;
                px = cx + rad * ang.cos(); pz = cz + rad * ang.sin(); py = y + self.rc + layer * s * 0.9 + jy * s * 0.1;
                vx = 0.0; vy = 0.0; vz = 0.0;
            } else {
                let (ox, oy, oz) = ((a as f32 + 0.5) * s - side * 0.5, (b as f32 + 0.5) * s, (c as f32 + 0.5) * s - side * 0.5);
                px = cx + ox + jx * s * 0.02; py = y + oy + jy * s * 0.02; pz = cz + oz + jz * s * 0.02;
                let along = ox * dx + oz * dz;
                let w = if age < 0.5 { m.tip } else { 0.0 };
                vx = dx * w * oy; vz = dz * w * oy; vy = w * (side * 0.5 - along);
            }
            let o = i * 3;
            self.p[o] = px; self.p[o + 1] = py; self.p[o + 2] = pz;
            self.q[o] = px; self.q[o + 1] = py; self.q[o + 2] = pz;
            self.v[o] = vx; self.v[o + 1] = vy; self.v[o + 2] = vz;
            self.alive[i] = 1; self.scale[i] = 1.0; self.spr_n[i] = 0; self.anc_kind[i] = 0; self.anc_cool[i] = 0.0;
            self.born[i] = self.clock; self.fly[i] = 0; self.sup[i] = 1.0;
        } } }
        // goo that is already where the new block appears: both take it easy for a moment
        let mut crowded = false;
        for (b, bd) in self.bodies.iter_mut().enumerate() {
            if b == slot || bd.state != 1 { continue; }
            if bd.bb[0] < x + 1.0 && bd.bb[3] > x && bd.bb[1] < y + 1.0 && bd.bb[4] > y && bd.bb[2] < z + 1.0 && bd.bb[5] > z {
                bd.soft = SOFT_TIME; bd.awake = true; bd.calm = 0; crowded = true;
            }
        }
        self.bodies[slot] = Body {
            state: 1, mat, awake: true, age: age.max(0.0), born: self.clock, fade: 1.0, fresh: !puddle, count: nb as u32, thick,
            soft: if crowded { SOFT_TIME } else { 0.0 }, rev: { self.rev = self.rev.wrapping_add(1); self.rev },
            bb: [x - 0.5, y - 0.2, z - 0.5, x + 1.5, y + 1.3, z + 1.5], ..Body::default()
        };
        slot as i32
    }

    pub fn remove_body(&mut self, b: usize, fade: bool) {
        if b >= self.slots || self.bodies[b].state == 0 { return; }
        if fade { self.bodies[b].state = 2; self.bodies[b].awake = true; } else { self.free_body(b); }
    }

    pub fn wake(&mut self, b: usize) { if b < self.slots && self.bodies[b].state != 0 { self.bodies[b].awake = true; self.bodies[b].calm = 0; } }

    /// (static anchors, kinematic anchors, springs) — for diagnostics and tests
    pub fn link_counts(&self) -> (u32, u32, u32) {
        let (mut a, mut k, mut sp) = (0, 0, 0);
        for i in 0..self.maxp { if self.alive[i] != 0 { match self.anc_kind[i] { 1 => a += 1, 2 => k += 1, _ => {} } sp += self.spr_n[i] as u32; } }
        (a, k, sp)
    }

    /// How many particles are stuck to the moving box with this id (diagnostics and tests).
    pub fn anchors_on(&self, id: i32) -> u32 {
        (0..self.maxp).filter(|&i| self.alive[i] != 0 && self.anc_kind[i] == 2 && self.anc_id[i] == id).count() as u32
    }

    /// How many particles of body `b` are stuck to blocks (diagnostics and tests).
    pub fn anchors_of(&self, b: usize) -> u32 {
        if b >= self.slots { return 0; }
        (b * self.nb..(b + 1) * self.nb).filter(|&i| self.alive[i] != 0 && self.anc_kind[i] == 1).count() as u32
    }

    /// How many particles of body `b` are still in free flight (diagnostics and tests).
    pub fn flying(&self, b: usize) -> u32 {
        if b >= self.slots { return 0; }
        (b * self.nb..(b + 1) * self.nb).filter(|&i| self.alive[i] != 0 && self.fly[i] != 0).count() as u32
    }

    /// End points of springs longer than `min_len` (diagnostics).
    pub fn long_springs(&self, min_len: f32) -> Vec<([f32; 3], [f32; 3])> {
        let mut v = Vec::new();
        for i in 0..self.maxp { if self.alive[i] == 0 { continue; }
            for k in 0..self.spr_n[i] as usize { let j = self.spr_j[i * MAX_SPR + k] as usize;
                let a = [self.p[i * 3], self.p[i * 3 + 1], self.p[i * 3 + 2]]; let b = [self.p[j * 3], self.p[j * 3 + 1], self.p[j * 3 + 2]];
                let d = ((a[0] - b[0]).powi(2) + (a[1] - b[1]).powi(2) + (a[2] - b[2]).powi(2)).sqrt();
                if d > min_len && (d > 2.9 * self.s || d > 1.3 * self.spr_l[i * MAX_SPR + k]) { v.push((a, b)); } } }
        v
    }

    /// Writes every particle that exists as four floats: position, then its size (0..1, shrinking
    /// while its piece fades away), plus 2 for slime, plus 4 for a filler, plus what [`lava_bits`]
    /// says for lava. Fillers are extra points
    /// along springs that have been pulled long: the band of slime between two pistons and the
    /// strands of honey would otherwise be drawn as rows of separate beads. Returns the number of
    /// points, or how many floats are needed, negated, if `out` is too small.
    pub fn particles(&self, out: &mut [f32]) -> i32 {
        let s = self.s;
        let mut n = 0usize;
        let mut fillers = PARTICLE_FILLERS;
        let mut put = |x: f32, y: f32, z: f32, w: f32, n: &mut usize| {
            if *n * 4 + 4 <= out.len() { out[*n * 4] = x; out[*n * 4 + 1] = y; out[*n * 4 + 2] = z; out[*n * 4 + 3] = w; }
            *n += 1;
        };
        for b in 0..self.slots {
            let bd = &self.bodies[b];
            if bd.state == 0 { continue; }
            let m = &self.bmats[b];
            let jelly = !m.dynamic_springs;
            let lava = bd.mat == LAVA;
            let cold = if lava { lava_cold(bd.age) } else { 0.0 };
            let mut slime = if bd.mat == SLIME { 2.0 } else { 0.0 };
            for i in b * self.nb..(b + 1) * self.nb {
                if self.alive[i] == 0 { continue; }
                let sc = self.scale[i].clamp(0.0, 1.0);
                let p = [self.p[i * 3], self.p[i * 3 + 1], self.p[i * 3 + 2]];
                if lava {
                    // what the picture of lava is made from: a pattern that belongs to the particle, and how far it has cooled
                    let speed = (self.v[i * 3] * self.v[i * 3] + self.v[i * 3 + 1] * self.v[i * 3 + 1] + self.v[i * 3 + 2] * self.v[i * 3 + 2]).sqrt();
                    slime = lava_bits(b, i - b * self.nb, self.n, cold, speed);
                }
                put(p[0], p[1], p[2], sc + slime, &mut n);
                if bd.state != 1 || sc < 0.99 { continue; }
                for k in 0..self.spr_n[i] as usize {
                    let si = i * MAX_SPR + k;
                    let rest = self.spr_l[si];
                    if jelly && rest > 1.2 * s { continue; } // nearest neighbours only: one row of fillers per gap
                    let j = self.spr_j[si] as usize;
                    if self.alive[j] == 0 { continue; }
                    let d = [self.p[j * 3] - p[0], self.p[j * 3 + 1] - p[1], self.p[j * 3 + 2] - p[2]];
                    let len = (d[0] * d[0] + d[1] * d[1] + d[2] * d[2]).sqrt();
                    let (count, size, droop);
                    if jelly {
                        if len < 1.6 * s || len > 6.5 * s { continue; }
                        count = ((len / (1.1 * s)) as usize).min(5); size = 0.85; droop = 0.0;
                    } else {
                        // honey: only springs that are both long and taut are strands; every second one will do
                        if len < 1.9 * s || len > 4.0 || (len < 2.9 * s && len < 1.3 * rest) { continue; }
                        if (i.wrapping_mul(31) ^ j.wrapping_mul(17)) % 2 != 0 { continue; }
                        count = ((len / (0.9 * s)) as usize).min(40);
                        size = (1.9 * s / len).clamp(0.3, 0.7);
                        droop = (m.thread_sag * len).min(0.2);
                    }
                    if count > fillers { continue; }
                    fillers -= count;
                    for step in 1..=count {
                        let t = step as f32 / (count + 1) as f32;
                        put(p[0] + d[0] * t, p[1] + d[1] * t - droop * 4.0 * t * (1.0 - t), p[2] + d[2] * t, size + slime + 4.0, &mut n);
                    }
                }
            }
        }
        if n * 4 > out.len() { -((n * 4) as i32) } else { n as i32 }
    }

    /// How many particles of each material (honey, slime) lie inside the box: "is this creature standing in goo".
    pub fn probe(&self, min: [f32; 3], max: [f32; 3]) -> (u32, u32) {
        let mut n = [0u32; 2];
        for b in 0..self.slots {
            let bd = &self.bodies[b];
            if bd.state != 1 || bd.mat > SLIME || (0..3).any(|a| bd.bb[a] > max[a] || bd.bb[a + 3] < min[a]) { continue; }
            for i in b * self.nb..(b + 1) * self.nb {
                if self.alive[i] != 0 && (0..3).all(|a| self.p[i * 3 + a] >= min[a] && self.p[i * 3 + a] <= max[a]) { n[bd.mat as usize] += 1; }
            }
        }
        (n[0], n[1])
    }

    pub fn awake_count(&self) -> u32 { self.bodies.iter().filter(|b| b.state != 0 && b.awake).count() as u32 }

    /// Static colliders: `count` boxes of 7 floats (min xyz, max xyz, stick).
    pub fn set_static(&mut self, data: &[f32], count: usize) {
        self.st.clear();
        let count = count.min(data.len() / 7);
        for k in 0..count {
            let d = &data[k * 7..k * 7 + 7];
            self.st.push(SBox { min: [d[0], d[1], d[2]], max: [d[3], d[4], d[5]], stick: d[6] });
        }
        self.st_cells.fill(&self.st);
        // anchors of awake goo whose surface is gone let go (sleeping goo is outside the scanned region),
        // and so do anchors that ended up inside a block: goo dragged in there by a piston must come out again
        for b in 0..self.slots {
            if self.bodies[b].state == 0 || !self.bodies[b].awake { continue; }
            for i in b * self.nb..(b + 1) * self.nb {
                if self.alive[i] != 0 && self.anc_kind[i] == 1 {
                    let o = i * 3; let pt = [self.anc_pt[o], self.anc_pt[o + 1], self.anc_pt[o + 2]];
                    if !self.near_static(&pt, self.rc + 0.06) || self.near_static(&pt, -0.5 * self.rc) { self.anc_kind[i] = 0; }
                }
            }
        }
    }

    fn near_static(&self, pt: &[f32; 3], tol: f32) -> bool {
        if self.st.is_empty() { return false; }
        let c = [pt[0].floor() as i32, pt[1].floor() as i32, pt[2].floor() as i32];
        for dz in -1..=1 { for dy in -1..=1 { for dx in -1..=1 {
            for &k in self.st_cells.at(c[0] + dx, c[1] + dy, c[2] + dz) {
                let b = &self.st[k as usize];
                if (0..3).all(|a| pt[a] > b.min[a] - tol && pt[a] < b.max[a] + tol) { return true; }
            }
        } } }
        false
    }

    /// Kinematic colliders: `count` boxes of [`KIN_FLOATS`] floats (id, min xyz, max xyz, velocity xyz, stick, front).
    /// Positive ids are piston parts and blocks in transit, negative ids are creatures.
    /// Boxes missing from a new list are dropped; goo anchored to them stays where it was.
    /// A box that is new or has moved since the last list wakes sleeping goo next to it.
    pub fn set_kinematic(&mut self, data: &[f32], count: usize) {
        let count = count.min(data.len() / KIN_FLOATS);
        let mut next: Vec<KBox> = Vec::with_capacity(count);
        for k in 0..count {
            let d = &data[k * KIN_FLOATS..(k + 1) * KIN_FLOATS];
            let id = d[0] as i32;
            let (min, max) = ([d[1], d[2], d[3]], [d[4], d[5], d[6]]);
            let prev = self.kin.iter().find(|b| b.id == id);
            let (pmin, pmax) = match prev { Some(b) => (b.min, b.max), None => (min, max) };
            // a box that has just turned up, or is not where it was, is news to anything sleeping next to it
            // (a creature that appears inside resting goo and then stands still never counts as "moving")
            if prev.map_or(true, |b| (0..3).any(|a| (b.min[a] - min[a]).abs() > 1e-3 || (b.max[a] - max[a]).abs() > 1e-3)) {
                for bd in self.bodies.iter_mut() {
                    if bd.state == 1 && !bd.awake && (0..3).all(|a| max[a] > bd.bb[a] - 0.6 && min[a] < bd.bb[a + 3] + 0.6) { bd.awake = true; bd.calm = 0; }
                }
            }
            next.push(KBox { id, min, max, pmin, pmax, vel: [d[7], d[8], d[9]], stick: d[10], t: 0.0, front: (d[11] as i32).clamp(0, 6) as u8 });
        }
        for i in (0..self.slots).filter(|&b| self.bodies[b].state != 0).flat_map(|b| b * self.nb..(b + 1) * self.nb) {
            if self.alive[i] == 0 || self.anc_kind[i] != 2 { continue; }
            match next.iter().find(|b| b.id == self.anc_id[i]) {
                None => self.anc_kind[i] = 1, // becomes a plain world anchor at its last position
                // a box that no longer holds on to goo (a creature that has got moving, or is being washed or shaken clean):
                // what stuck to it lets go, and does not stick again at once
                Some(b) if b.stick <= 0.0 => { self.anc_kind[i] = 0; self.anc_cool[i] = LET_GO; }
                _ => {}
            }
        }
        self.kin = next;
    }

    /// Advances the simulation by `dt` seconds of real time (fixed 1/60 s steps, at most 3 per call).
    pub fn step(&mut self, dt: f32) -> u32 {
        self.acc += dt.clamp(0.0, 0.1);
        let mut n = 0;
        while self.acc >= DT && n < 3 { self.step_once(); self.acc -= DT; n += 1; }
        if self.acc > DT * 2.0 { self.acc = 0.0; }
        self.awake_count()
    }

    /// Lets the next layers of body `b`'s stream out of its nozzle: one layer for every particle
    /// spacing the stream has travelled, so the stream is as dense as goo at rest.
    /// Lets the next part of a stream out of its spout. A stream of honey is never an even rod: it
    /// starts as a thread, swells, sways a little from side to side, comes in thicker and thinner
    /// pulses, and thins out to a thread and single drops as the bottle runs empty.
    fn stream_out(&mut self, b: usize, dt: f32) {
        let (pos, vel) = (self.bodies[b].emit_pos, self.bodies[b].emit_vel);
        let speed0 = (vel[0] * vel[0] + vel[1] * vel[1] + vel[2] * vel[2]).sqrt().max(0.3);
        let dir0 = if speed0 > 0.31 { [vel[0] / speed0, vel[1] / speed0, vel[2] / speed0] } else { [0.0, -1.0, 0.0] };
        // two directions across the stream
        let up = if dir0[1].abs() < 0.9 { [0.0, 1.0, 0.0] } else { [1.0, 0.0, 0.0] };
        let mut u = [dir0[1] * up[2] - dir0[2] * up[1], dir0[2] * up[0] - dir0[0] * up[2], dir0[0] * up[1] - dir0[1] * up[0]];
        let ul = (u[0] * u[0] + u[1] * u[1] + u[2] * u[2]).sqrt();
        for a in 0..3 { u[a] /= ul; }
        let w = [dir0[1] * u[2] - dir0[2] * u[1], dir0[2] * u[0] - dir0[0] * u[2], dir0[0] * u[1] - dir0[1] * u[0]];
        let s = self.s;
        // where in its swaying and pulsing this stream is: by its own clock, each stream out of step with the others
        self.bodies[b].emit_time += dt;
        let t = self.bodies[b].emit_time as f64;
        let ph = b as f64 * 1.618 + (pos[0] as f64 + pos[2] as f64) * 0.37;
        let wave = |rate: f64, shift: f64| (t * rate + ph * shift).sin() as f32;
        // (the fast parts make bulges and kinks a hand apart along the stream, the slow ones move the whole of it about)
        let sway_u = 0.09 * wave(5.3, 1.0) + 0.07 * wave(13.7, 1.7) + 0.03 * wave(29.0, 0.4);
        let sway_w = 0.09 * wave(4.3, 2.1) + 0.07 * wave(15.1, 0.6) + 0.03 * wave(31.0, 1.2);
        let mut dir = [dir0[0] + u[0] * sway_u + w[0] * sway_w, dir0[1] + u[1] * sway_u + w[1] * sway_w, dir0[2] + u[2] * sway_u + w[2] * sway_w];
        let dl = (dir[0] * dir[0] + dir[1] * dir[1] + dir[2] * dir[2]).sqrt();
        for a in 0..3 { dir[a] /= dl; }
        let speed = speed0 * (1.0 + 0.10 * wave(8.9, 1.3) + 0.06 * wave(19.0, 0.9));
        let pulse = 1.0 + 0.15 * wave(6.1, 1.0) + 0.40 * wave(23.0, 2.3) + 0.25 * wave(41.0, 0.7);
        self.bodies[b].emit_acc += speed * dt;
        self.bodies[b].awake = true; self.bodies[b].calm = 0;
        self.bodies[b].young_until = self.clock + YOUNG + 1.0;
        while self.bodies[b].emit_acc >= s && self.bodies[b].pending > 0 {
            self.bodies[b].emit_acc -= s;
            let ahead = self.bodies[b].emit_acc; // this layer is already that far out
            // how full the stream runs: a thread at first, soon its full width, a thread again at the end
            let total = self.bodies[b].emit_total.max(1) as f32;
            let done = 1.0 - self.bodies[b].pending as f32 / total;
            let width = (0.25 + 0.75 * smooth(done / 0.12)) * (0.2 + 0.8 * (1.0 - smooth((done - 0.65) / 0.35))) * pulse;
            let layer = ((STREAM_LAYER as f32 * width).round() as usize).clamp(1, STREAM_BULGE);
            let turn = self.rnd() * std::f32::consts::TAU;
            for k in 0..layer {
                if self.bodies[b].pending == 0 { break; }
                // one in the middle, six around it, and where the stream bulges a second ring in the gaps of the first
                let (ca, cb) = if k == 0 { (0.0, 0.0) } else if k < 7 { let a = turn + k as f32 * std::f32::consts::TAU / 6.0; (a.cos() * s, a.sin() * s) }
                    else { let a = turn + (k as f32 + 0.5) * std::f32::consts::TAU / 6.0; (a.cos() * s * 1.75, a.sin() * s * 1.75) };
                let (j0, j1, j2) = ((self.rnd() - 0.5) * 0.3 * s, (self.rnd() - 0.5) * 0.3 * s, 1.0 + (self.rnd() - 0.5) * 0.10);
                let at = [pos[0] + dir[0] * ahead + u[0] * (ca + j0) + w[0] * (cb + j1), pos[1] + dir[1] * ahead + u[1] * (ca + j0) + w[1] * (cb + j1), pos[2] + dir[2] * ahead + u[2] * (ca + j0) + w[2] * (cb + j1)];
                if !self.spawn(b, at, [dir[0] * speed * j2, dir[1] * speed * j2, dir[2] * speed * j2], true) { self.bodies[b].pending = 0; break; }
                self.bodies[b].pending -= 1;
            }
        }
    }

    fn step_once(&mut self) {
        let dt = DT;
        self.clock += dt;
        self.ticks = self.ticks.wrapping_add(1);
        let (nb, slots) = (self.nb, self.slots);

        // moving colliders wake anything they come near; piston parts (positive ids) also stiffen it for a moment
        for k in 0..self.kin.len() {
            let b = self.kin[k];
            if b.vel[0] == 0.0 && b.vel[1] == 0.0 && b.vel[2] == 0.0 { continue; }
            for bd in self.bodies.iter_mut() {
                if bd.state == 0 { continue; }
                if !bd.awake && (0..3).all(|a| b.max[a] > bd.bb[a] - 0.6 && b.min[a] < bd.bb[a + 3] + 0.6) { bd.awake = true; bd.calm = 0; }
                if b.id > 0 && (0..3).all(|a| b.max[a] > bd.bb[a] - 0.3 && b.min[a] < bd.bb[a + 3] + 0.3) { bd.shock = 1.0; }
            }
        }

        // ---- bodies ----
        self.act.clear();
        for b in 0..slots {
            if self.bodies[b].state == 0 || !self.bodies[b].awake { continue; }
            self.rev = self.rev.wrapping_add(1);
            self.bodies[b].rev = self.rev;
            if self.bodies[b].state == 2 {
                // being removed: no physics any more, it just shrinks into the middle of its base and is gone
                // (lava that has cooled does not draw together: it crumbles away where it lies, and takes longer about it)
                let bd = &mut self.bodies[b];
                let before = bd.fade;
                let lava = bd.mat == LAVA;
                bd.fade -= dt / if lava { LAVA_FADE_TIME } else { FADE_TIME };
                if bd.fade <= 0.0 { self.free_body(b); continue; }
                let k = if lava { 1.0 } else { bd.fade / before };
                let c = [(bd.bb[0] + bd.bb[3]) * 0.5, bd.bb[1], (bd.bb[2] + bd.bb[5]) * 0.5];
                for a in 0..3 { bd.bb[a] = c[a] + (bd.bb[a] - c[a]) * k; bd.bb[a + 3] = c[a] + (bd.bb[a + 3] - c[a]) * k; }
                let fade = bd.fade;
                for i in b * nb..(b + 1) * nb {
                    if self.alive[i] == 0 { continue; }
                    let o = i * 3;
                    for a in 0..3 { let x = c[a] + (self.p[o + a] - c[a]) * k; self.p[o + a] = x; self.q[o + a] = x; self.v[o + a] = 0.0; }
                    self.scale[i] = fade; self.anc_kind[i] = 0;
                }
                continue;
            }
            let m = self.bmats[b];
            let bd = &mut self.bodies[b];
            bd.age += dt;
            bd.melt = if m.melt_time > 0.0 { smooth(bd.age / m.melt_time) } else { 0.0 };
            let set = if m.set_after > 0.0 { 1.0 + ((bd.age - m.set_after).max(0.0) * m.set_rate).min(3.0) } else { 1.0 };
            let sh = bd.shock;
            bd.kd = (m.damp_fresh + (m.damp_melted - m.damp_fresh) * bd.melt) * set + 26.0 * sh * sh;
            let k = m.spring_k0 + (m.spring_k1 - m.spring_k0) * bd.melt;
            bd.k_spring = k + (m.shock_k - k).max(0.0) * sh;
            let pl = m.plast0 + (m.plast1 - m.plast0) * bd.melt;
            bd.plast = pl + (m.shock_plast - pl).min(0.0) * sh;
            bd.brk = m.break_strain + (m.shock_break - m.break_strain).max(0.0) * sh;
            bd.max_l = m.spring_max_l + (m.shock_max_l - m.spring_max_l).max(0.0) * sh;
            bd.shock = if sh < 0.01 { 0.0 } else { sh * (-dt / m.shock_decay).exp() };
            bd.soft = (bd.soft - dt).max(0.0);
            let kd = bd.kd;
            if self.bodies[b].pending > 0 { self.stream_out(b, dt); }
            let mut cnt = 0u32;
            for i in b * nb..(b + 1) * nb {
                if self.alive[i] != 0 {
                    self.act.push(i as u32); cnt += 1;
                    // as thick as its body where something holds it, falling freely where nothing does
                    self.damp[i] = 0.15 + (kd - 0.15).max(0.0) * smooth((self.sup[i] - SUPPORT_LO) / (SUPPORT_HI - SUPPORT_LO));
                    if self.fly[i] != 0 && self.clock - self.born[i] > FLIGHT_MAX { self.fly[i] = 0; }
                }
            }
            self.bodies[b].count = cnt;
            // nothing left of it (and nothing on its way): the slot is free again
            if cnt == 0 && self.bodies[b].pending == 0 && self.clock - self.bodies[b].born > 0.5 { self.free_body(b); }
        }
        if self.act.is_empty() { return; }

        // ---- kinematic colliders move ----
        for b in self.kin.iter_mut() {
            // right after an update the sweep starts where the box really was before it
            if b.t > 0.0 { b.pmin = b.min; b.pmax = b.max; }
            b.t += dt;
            // the game reports 20 times a second: carry a box for one game tick, then wait for news
            if b.t > 0.05 + 0.5 * dt { b.vel = [0.0; 3]; }
            for a in 0..3 { b.min[a] += b.vel[a] * dt; b.max[a] += b.vel[a] * dt; }
        }

        // which moving boxes can reach which body in this step: a particle moves 0.3 blocks at most, a box from where it was to where it is
        let reach = 0.3 + self.rc + 0.05;
        self.kin_from.clear(); self.kin_near.clear();
        for b in 0..slots {
            self.kin_from.push(self.kin_near.len() as u32);
            if self.bodies[b].state == 0 || !self.bodies[b].awake { continue; }
            let bb = self.bodies[b].bb;
            for (k, kb) in self.kin.iter().enumerate() {
                if (0..3).all(|a| kb.max[a].max(kb.pmax[a]) > bb[a] - reach && kb.min[a].min(kb.pmin[a]) < bb[a + 3] + reach) { self.kin_near.push(k as u32); }
            }
        }
        self.kin_from.push(self.kin_near.len() as u32);
        self.kin_ids.clear();
        self.kin_ids.extend(self.kin.iter().enumerate().map(|(k, b)| (b.id, k as u32)));
        self.kin_ids.sort_unstable();

        match pool() { Some(pool) => pool.install(|| self.solve(dt)), None => self.solve(dt) }

        // step_once ends in solve()
    }

    /// The particle work of one step. Every loop over particles or pairs here runs on the core's
    /// thread pool; each is written so that a thread only ever writes its own particles (or pairs),
    /// and sums are taken in a fixed order, so the result does not depend on how many threads there are.
    fn solve(&mut self, dt: f32) {
        let (nb, slots) = (self.nb, self.slots);
        let n_act = self.act.len();
        let mut t_prof = std::time::Instant::now();

        // ---- predict, then collide ----
        let g = self.gravity;
        {
            let walls = Walls { st: &self.st, cells: &self.st_cells, kin: &self.kin, kin_from: &self.kin_from, kin_near: &self.kin_near, nb: self.nb, rc: self.rc, rho_max: 1.25 * self.rho0 };
            let an = Anchors { kind: Shared::of(&mut self.anc_kind), id: Shared::of(&mut self.anc_id), pt: Shared::of(&mut self.anc_pt), loc: Shared::of(&mut self.anc_loc), stick: Shared::of(&mut self.anc_stick), axis: Shared::of(&mut self.anc_axis), fly: Shared::of(&mut self.fly) };
            let (v, q, cool) = (Shared::of(&mut self.v), Shared::of(&mut self.q), Shared::of(&mut self.anc_cool));
            let (p, damp, rho) = (&self.p[..], &self.damp[..], &self.rho[..]);
            self.act.par_chunks(CHUNK).for_each(|chunk| { for &iu in chunk { let i = iu as usize; let o = i * 3; unsafe {
                let (x, z) = (p[o], p[o + 2]);
                let mut k = if *an.fly.at(i) != 0 { 0.15 } else { damp[i] }; // in flight: not slowed with the rest of its body
                if k > 0.5 { k *= 0.72 + 0.34 * ((x * 6.1 + 1.9 * (z * 2.3).sin()).sin() + (z * 3.7 + x * 1.4).sin()); }
                let f = (-k * dt).exp();
                *v.at(o) *= f; *v.at(o + 1) = (*v.at(o + 1) - g * dt) * f; *v.at(o + 2) *= f;
                for c in 0..3 { *q.at(o + c) = p[o + c] + *v.at(o + c) * dt; }
                if *cool.at(i) > 0.0 { *cool.at(i) -= dt; }
                collide(&walls, an, q, p, rho[i], *cool.at(i), i);
            } } });
        }
        self.prof[0] += t_prof.elapsed().as_secs_f64(); t_prof = std::time::Instant::now();

        // ---- neighbours (hash grid) ----
        // Every particle gets the list of all particles within reach of it. A pair therefore shows up
        // twice, once in each list, and both sides work out the same kernel value from the same two
        // positions (bit for bit, with opposite gradients), so momentum is still conserved exactly.
        // Each particle only ever reads its own list, in the order it was built: the result cannot
        // depend on the number of threads, and everything a loop touches is small and in order.
        let (h, h2) = (self.h, self.h * self.h);
        let ih = 1.0 / h;
        // (the table is as large as the number of particles that are moving asks for, not as the number there could be)
        let mut table = 1024usize; while table < n_act * 2 { table <<= 1; }
        let mask = (table - 1) as u32;
        self.c_start.clear(); self.c_start.resize(table + 1, 0);
        self.c_fill.resize(table, 0);
        {
            // (which cell a particle is in is its own business: side by side; counting them into the table is not)
            let q = &self.q[..];
            let (cell, pkey, pbkt) = (Shared::of(&mut self.cell), Shared::of(&mut self.pkey), Shared::of(&mut self.pbkt));
            self.act.par_chunks(CHUNK * 4).for_each(|chunk| { for &iu in chunk { let i = iu as usize; let o = i * 3;
                let (ix, iy, iz) = ((q[o] * ih).floor() as i32 + 512, (q[o + 1] * ih).floor() as i32 + 512, (q[o + 2] * ih).floor() as i32 + 512);
                // Safety: particle i is this thread's alone
                unsafe { *cell.at(o) = ix; *cell.at(o + 1) = iy; *cell.at(o + 2) = iz; *pkey.at(i) = key3(ix, iy, iz); *pbkt.at(i) = hash3(ix, iy, iz, mask); }
            } });
        }
        for a in 0..n_act { let bk = self.pbkt[self.act[a] as usize]; self.c_start[bk as usize + 1] += 1; }
        for c in 0..table { self.c_start[c + 1] += self.c_start[c]; self.c_fill[c] = self.c_start[c]; }
        for a in 0..n_act { let i = self.act[a]; let b = self.pbkt[i as usize] as usize; self.sorted[self.c_fill[b] as usize] = i; self.c_fill[b] += 1; }
        // within a bucket, keep each cell's particles together (two cells rarely share a bucket; when they do, order them by cell)
        for k in 1..n_act {
            let i = self.sorted[k];
            let (bk, key) = (self.pbkt[i as usize], self.pkey[i as usize]);
            let mut m = k;
            while m > 0 { let j = self.sorted[m - 1] as usize; if self.pbkt[j] != bk || self.pkey[j] <= key { break; } self.sorted[m] = self.sorted[m - 1]; m -= 1; }
            self.sorted[m] = i;
        }
        // positions in that order, and where each cell's run of particles starts and ends
        self.sp.resize(n_act * 3 + LANES, 0.0); // (room to read eight candidates from anywhere)
        self.runs.clear();
        self.first.clear(); self.first.resize(n_act, 0);
        {
            let (q, sorted, pkey, pbkt) = (&self.q[..], &self.sorted[..], &self.pkey[..], &self.pbkt[..]);
            let (sp, first) = (Shared::of(&mut self.sp), Shared::of(&mut self.first));
            (0..n_act).into_par_iter().with_min_len(CHUNK * 4).for_each(|k| {
                let i = sorted[k] as usize;
                // Safety: place k of the order is written by nobody else
                unsafe {
                    *sp.at(k) = q[i * 3]; *sp.at(n_act + k) = q[i * 3 + 1]; *sp.at(2 * n_act + k) = q[i * 3 + 2];
                    *first.at(k) = (k == 0 || pkey[i] != pkey[sorted[k - 1] as usize] || pbkt[i] != pbkt[sorted[k - 1] as usize]) as u8;
                }
            });
        }
        for k in 0..n_act { if self.first[k] != 0 { self.runs.push(k as u32); } }
        self.runs.push(n_act as u32);
        // one cell at a time: the particles of the 27 cells around it are looked up once and then tried against each of its own
        const RUNS: usize = 48;
        let found: Vec<(Vec<u32>, Vec<u32>)> = {
            let (sx, sy, sz) = (&self.sp[..n_act], &self.sp[n_act..2 * n_act], &self.sp[2 * n_act..3 * n_act]);
            let sp = &self.sp[..];
            let wide = self.wide;
            let (cell, pkey, c_start, sorted, runs) = (&self.cell[..], &self.pkey[..], &self.c_start[..], &self.sorted[..], &self.runs[..]);
            let n_runs = runs.len() - 1;
            (0..n_runs.div_ceil(RUNS)).into_par_iter().map(|c| {
                let (first, last) = (c * RUNS, ((c + 1) * RUNS).min(n_runs));
                let mut list: Vec<u32> = Vec::with_capacity((runs[last] - runs[first]) as usize * 40);
                let mut ends = Vec::with_capacity((runs[last] - runs[first]) as usize);
                let mut around = [(0u32, 0u32); 27];
                for run in first..last {
                    let (lo, hi) = (runs[run] as usize, runs[run + 1] as usize);
                    let o = sorted[lo] as usize * 3;
                    let (ix, iy, iz) = (cell[o], cell[o + 1], cell[o + 2]);
                    let mut n_around = 0;
                    for dz in -1..=1 { for dy in -1..=1 { for dx in -1..=1 {
                        let (jx, jy, jz) = (ix + dx, iy + dy, iz + dz);
                        let key = key3(jx, jy, jz); let bk = hash3(jx, jy, jz, mask) as usize;
                        let (mut a, mut b) = (c_start[bk] as usize, c_start[bk + 1] as usize);
                        while a < b && pkey[sorted[a] as usize] != key { a += 1; }
                        while b > a && pkey[sorted[b - 1] as usize] != key { b -= 1; }
                        if a < b { around[n_around] = (a as u32, b as u32); n_around += 1; }
                    } } }
                    let candidates: usize = around[..n_around].iter().map(|&(a, b)| (b - a) as usize).sum();
                    for k in lo..hi {
                        let (x, y, z) = (sx[k], sy[k], sz[k]);
                        // every candidate is written, and kept only if it is within reach: no branch for the processor to guess
                        list.reserve(candidates + LANES);
                        let mut n = list.len();
                        let out = list.as_mut_ptr();
                        #[cfg(target_arch = "x86_64")]
                        let done = wide && {
                            // Safety: sp and sorted have eight entries to spare, and that much room was reserved in the list
                            n = unsafe { near_wide(x, y, z, k, h2, &around[..n_around], sp.as_ptr(), sp.as_ptr().add(n_act), sp.as_ptr().add(2 * n_act), sorted.as_ptr(), out, n) };
                            true
                        };
                        #[cfg(not(target_arch = "x86_64"))]
                        let done = { let _ = (wide, sp); false };
                        if !done {
                            for &(a, b) in &around[..n_around] {
                                let (a, b) = (a as usize, b as usize);
                                let (cx, cy, cz, cj) = (&sx[a..b], &sy[a..b], &sz[a..b], &sorted[a..b]);
                                for m in 0..b - a {
                                    let (ex, ey, ez) = (x - cx[m], y - cy[m], z - cz[m]);
                                    // Safety: at most `candidates` writes past the old length, and that much room was reserved
                                    unsafe { *out.add(n) = cj[m]; }
                                    n += ((ex * ex + ey * ey + ez * ez < h2) & (a + m != k)) as usize;
                                }
                            }
                        }
                        unsafe { list.set_len(n); }
                        ends.push(n as u32);
                    }
                }
                (list, ends)
            }).collect()
        };
        // the lists one behind the other, in the order of the cells; every lot of cells copies its own stretch
        let mut starts: Vec<usize> = Vec::with_capacity(found.len() + 1);
        let mut total = 0usize;
        for (list, _) in &found { starts.push(total); total += list.len(); }
        self.nbr.clear();
        self.nbr.resize(total + LANES, 0); // (a few entries to spare: the lists are read eight entries at a time, and that may be from the last entry of the last list)
        {
            let (nbr, lo, hi) = (Shared::of(&mut self.nbr), Shared::of(&mut self.nbr_lo), Shared::of(&mut self.nbr_hi));
            let (sorted, runs) = (&self.sorted[..], &self.runs[..]);
            found.par_iter().zip(starts.par_iter()).enumerate().for_each(|(c, ((list, ends), &base))| {
                let first = runs[c * RUNS] as usize;
                let mut from = base as u32;
                // Safety: this lot's stretch of the lists and its own particles are written by nobody else
                unsafe {
                    std::ptr::copy_nonoverlapping(list.as_ptr(), nbr.at(base) as *mut u32, list.len());
                    for (k, &end) in ends.iter().enumerate() {
                        let i = sorted[first + k] as usize;
                        *lo.at(i) = from; *hi.at(i) = base as u32 + end; from = base as u32 + end;
                    }
                }
            });
        }
        drop(found);
        let n_nbr = total;
        self.last_pairs = n_nbr / 2;
        self.nw.resize(n_nbr + LANES, 0.0);
        if !self.wide { self.ngx.resize(n_nbr + LANES, 0.0); self.ngy.resize(n_nbr + LANES, 0.0); self.ngz.resize(n_nbr + LANES, 0.0); }
        self.prof[1] += t_prof.elapsed().as_secs_f64(); t_prof = std::time::Instant::now();

        // ---- springs form ----
        let hs2 = (1.5 * self.s) * (1.5 * self.s);
        // new springs are looked for on every other step (and at once for a block that has just appeared):
        // goo that flows makes and loses them all the time, one step sooner or later does not show
        self.steps = self.steps.wrapping_add(1);
        let look = self.steps & 1 == 0 || (0..slots).any(|b| self.bodies[b].fresh && self.bodies[b].state == 1);
        let wanted: Vec<Vec<(u32, u32)>> = if !look { Vec::new() } else {
            let (nbr, nbr_lo, nbr_hi, q, spr_n, spr_j, bodies, mats, born, clock) = (&self.nbr[..], &self.nbr_lo[..], &self.nbr_hi[..], &self.q[..], &self.spr_n[..], &self.spr_j[..], &self.bodies[..], &self.bmats[..], &self.born[..], self.clock);
            self.act.par_chunks(CHUNK).map(|chunk| {
                let mut out = Vec::new();
                for &iu in chunk {
                    let i = iu as usize; let bi = i / nb; let mi = bodies[bi].mat;
                    let (dynamic, fresh) = (mats[bi].dynamic_springs, bodies[bi].fresh);
                    // goo that does not flow only bonds when it is new: a whole block at once, or lumps
                    // that have just been added, to each other and to what they land on in their own body
                    let young = !dynamic && !fresh && clock < bodies[bi].young_until;
                    if !dynamic && !fresh && !young { continue; }
                    let oi = i * 3;
                    for e in nbr_lo[i]..nbr_hi[i] {
                        let j = nbr[e as usize] as usize;
                        if j < i { continue; } // each pair once, from its lower particle
                        let bj = j / nb;
                        if bodies[bj].mat != mi || !(dynamic || bi == bj) { continue; }
                        if young && clock - born[i] > YOUNG && clock - born[j] > YOUNG { continue; }
                        let oj = j * 3;
                        let (ex, ey, ez) = (q[oi] - q[oj], q[oi + 1] - q[oj + 1], q[oi + 2] - q[oj + 2]);
                        if ex * ex + ey * ey + ez * ez > hs2 { continue; }
                        if (spr_n[i] as usize).min(spr_n[j] as usize) < MAX_SPR && !spring_exists(spr_n, spr_j, i, j) { out.push((i as u32, j as u32)); }
                    }
                }
                out
            }).collect()
        };
        for &(iu, ju) in wanted.iter().flatten() {
            let (i, j) = (iu as usize, ju as usize);
            let (oi, oj) = (i * 3, j * 3);
            let (ex, ey, ez) = (self.q[oi] - self.q[oj], self.q[oi + 1] - self.q[oj + 1], self.q[oi + 2] - self.q[oj + 2]);
            let r2 = ex * ex + ey * ey + ez * ez;
            let (a, b) = if self.spr_n[i] <= self.spr_n[j] { (i, j) } else { (j, i) };
            let na = self.spr_n[a] as usize;
            if na >= MAX_SPR { continue; }
            self.spr_j[a * MAX_SPR + na] = b as u32; self.spr_l[a * MAX_SPR + na] = r2.sqrt().max(0.7 * self.s); self.spr_n[a] += 1;
            let (ba, bb) = ((a / nb) as u32, (b / nb) as u32);
            if ba != bb {
                if !self.partners[ba as usize].contains(&bb) { self.partners[ba as usize].push(bb); }
                if !self.partners[bb as usize].contains(&ba) { self.partners[bb as usize].push(ba); }
            }
        }
        drop(wanted);
        if look { for b in 0..slots { self.bodies[b].fresh = false; } }
        // Which springs can be relaxed side by side. A spring with both ends in one body is that body's own
        // business, and so is one whose far end is in goo that is asleep or on its way out (that end does not give
        // way): the bodies relax theirs side by side. A spring between two bodies that are both awake belongs to the
        // pair, and the pairs take turns, in rounds in which no body is in two pairs. Who goes when depends on the
        // bodies and their springs alone, never on the number of threads.
        let live: Vec<bool> = (0..slots).map(|b| self.bodies[b].state == 1 && self.bodies[b].awake).collect();
        let awake: Vec<u32> = (0..slots).filter(|&b| live[b] && self.bodies[b].count > 0).map(|b| b as u32).collect();
        // per awake body: its springs into other awake bodies as (the other body, the particle, which of its springs), by the other body
        let across: Vec<Vec<(u32, u32, u32)>> = {
            let (spr_n, spr_j, alive, live) = (&self.spr_n[..], &self.spr_j[..], &self.alive[..], &live[..]);
            awake.par_iter().map(|&bu| {
                let b = bu as usize;
                let mut out = Vec::new();
                for i in b * nb..(b + 1) * nb {
                    if alive[i] == 0 { continue; }
                    for k in 0..spr_n[i] as usize { let bj = spr_j[i * MAX_SPR + k] as usize / nb; if bj != b && live[bj] { out.push((bj as u32, i as u32, k as u32)); } }
                }
                out.sort_by_key(|e| e.0); // (keeps the order of the particles within a pair)
                out
            }).collect()
        };
        let pairs: Vec<Pair> = {
            // every run of springs from one body into another, under the pair of the two
            let mut runs: Vec<(u32, u32, bool, u32, u32, u32)> = Vec::new();
            for (at, list) in across.iter().enumerate() {
                let own = awake[at];
                let mut from = 0;
                while from < list.len() {
                    let other = list[from].0;
                    let mut to = from + 1;
                    while to < list.len() && list[to].0 == other { to += 1; }
                    runs.push((own.min(other), own.max(other), own > other, at as u32, from as u32, to as u32));
                    from = to;
                }
            }
            runs.sort_unstable();
            let mut pairs: Vec<Pair> = Vec::new();
            for (a, b, second, at, from, to) in runs {
                if pairs.last().map_or(true, |p| p.a != a || p.b != b) { pairs.push(Pair { a, b, runs: [(0, 0, 0); 2] }); }
                pairs.last_mut().unwrap().runs[second as usize] = (at, from, to);
            }
            pairs
        };
        // rounds: every pair goes into the first round in which neither of its bodies is taken yet
        let mut rounds: Vec<Vec<u32>> = Vec::new();
        let mut leftover: Vec<u32> = Vec::new();
        if !pairs.is_empty() {
            let mut taken = vec![0u128; slots];
            for (at, pair) in pairs.iter().enumerate() {
                let free = !(taken[pair.a as usize] | taken[pair.b as usize]);
                if free == 0 { leftover.push(at as u32); continue; } // (more than 128 neighbours: after the rounds, one by one)
                let round = free.trailing_zeros() as usize;
                taken[pair.a as usize] |= 1 << round; taken[pair.b as usize] |= 1 << round;
                if rounds.len() <= round { rounds.resize(round + 1, Vec::new()); }
                rounds[round].push(at as u32);
            }
        }
        self.prof[2] += t_prof.elapsed().as_secs_f64(); t_prof = std::time::Instant::now();

        // ---- constraint iterations ----
        let kern = Kern { h, h2, ks: self.ks, kp: self.kp, w0: self.w0, ir0: 1.0 / self.rho0, eps: self.eps, iwq: 1.0 / self.wq, kc: self.k_corr, max_d: 0.9 * self.s };
        let wide = self.wide;
        for it in 0..self.iters {
            let last = it + 1 == self.iters;
            // density and its multiplier; the kernel value and gradient towards every neighbour are kept for the correction
            let t_sub = std::time::Instant::now();
            {
                let lists = Lists { nbr: &self.nbr, lo: &self.nbr_lo, hi: &self.nbr_hi };
                let (q, mats) = (&self.q[..], &self.bmats[..]);
                let kept = Kept { w: Shared::of(&mut self.nw), gx: Shared::of(&mut self.ngx), gy: Shared::of(&mut self.ngy), gz: Shared::of(&mut self.ngz) };
                let (rho, lam) = (Shared::of(&mut self.rho), Shared::of(&mut self.lam));
                // Safety: a chunk's particles and the entries of their lists belong to the thread that has the chunk.
                // (The particles are taken in the order of their cells, which is the order their lists lie in:
                // every particle is worked out on its own here, so the order makes no difference to the numbers,
                // but walking the lists from one end to the other is a good deal quicker than jumping about in them.)
                self.sorted[..n_act].par_chunks(CHUNK).for_each(|chunk| unsafe {
                    #[cfg(target_arch = "x86_64")]
                    if wide { return density_wide(chunk, q, lists, &kern, mats, nb, if last { Some(kept.w) } else { None }, rho, lam); }
                    density_plain(chunk, q, lists, &kern, mats, nb, kept, rho, lam)
                });
            }
            self.prof[7] += t_sub.elapsed().as_secs_f64(); let t_sub = std::time::Instant::now();
            // position correction
            if wide {
                // (it works the kernel out again, from where the particles were a moment ago: it moves them itself)
                for &b in &awake { let all = b as usize * nb * 3..(b as usize + 1) * nb * 3; self.was[all.clone()].copy_from_slice(&self.q[all]); }
            }
            {
                let lists = Lists { nbr: &self.nbr, lo: &self.nbr_lo, hi: &self.nbr_hi };
                let (nw, ngx, ngy, ngz, lam, bodies, was) = (&self.nw[..], &self.ngx[..], &self.ngy[..], &self.ngz[..], &self.lam[..], &self.bodies[..], &self.was[..]);
                let q = Shared::of(&mut self.q);
                self.sorted[..n_act].par_chunks(CHUNK).for_each(|chunk| unsafe {
                    #[cfg(target_arch = "x86_64")]
                    if wide { return correct_wide(chunk, was, lists, lam, bodies, nb, &kern, q); }
                    let _ = was;
                    correct_plain(chunk, lists, nw, ngx, ngy, ngz, lam, bodies, nb, &kern, q)
                });
            }
            self.prof[8] += t_sub.elapsed().as_secs_f64(); let t_sub = std::time::Instant::now();
            // springs: first every body its own, side by side; then the pairs of bodies, round by round
            {
                let (p, alive, spr_n, spr_j, spr_l, bodies, live) = (&self.p[..], &self.alive[..], &self.spr_n[..], &self.spr_j[..], &self.spr_l[..], &self.bodies[..], &live[..]);
                let q = Shared::of(&mut self.q);
                // Safety: a thread writes the particles of its own body only (the far end of a spring into goo that is not awake is read, as it was before the step)
                awake.par_iter().for_each(|&bu| { let b = bu as usize;
                    let ksp = bodies[b].k_spring;
                    for i in b * nb..(b + 1) * nb {
                        if alive[i] == 0 { continue; }
                        for sidx in 0..spr_n[i] as usize {
                            let j = spr_j[i * MAX_SPR + sidx] as usize;
                            let fixed = !live[j / nb]; // asleep or on its way out: it does not give way
                            if !fixed && j / nb != b { continue; } // into another body that is awake: the pair's turn comes below
                            unsafe { relax(q, p, i, j, spr_l[i * MAX_SPR + sidx], ksp, fixed); }
                        }
                    }
                });
                // Safety: the pairs of one round have no body in common, and a pair writes the particles of its two bodies only
                let turn = |at: u32| { for &(list, from, to) in &pairs[at as usize].runs {
                    for &(_, iu, k) in &across[list as usize][from as usize..to as usize] {
                        let i = iu as usize; let si = i * MAX_SPR + k as usize;
                        unsafe { relax(q, p, i, spr_j[si] as usize, spr_l[si], bodies[i / nb].k_spring, false); }
                    }
                } };
                for round in &rounds { round.par_iter().for_each(|&at| turn(at)); }
                for &at in &leftover { turn(at); }
            }
            self.prof[9] += t_sub.elapsed().as_secs_f64(); let t_sub = std::time::Instant::now();
            // anchors pull, then colliders push
            {
                let walls = Walls { st: &self.st, cells: &self.st_cells, kin: &self.kin, kin_from: &self.kin_from, kin_near: &self.kin_near, nb: self.nb, rc: self.rc, rho_max: 1.25 * self.rho0 };
                let an = Anchors { kind: Shared::of(&mut self.anc_kind), id: Shared::of(&mut self.anc_id), pt: Shared::of(&mut self.anc_pt), loc: Shared::of(&mut self.anc_loc), stick: Shared::of(&mut self.anc_stick), axis: Shared::of(&mut self.anc_axis), fly: Shared::of(&mut self.fly) };
                let (q, anc_d) = (Shared::of(&mut self.q), Shared::of(&mut self.anc_d));
                let (p, rho, cool, mats, kin, kin_ids) = (&self.p[..], &self.rho[..], &self.anc_cool[..], &self.bmats[..], &self.kin[..], &self.kin_ids[..]);
                self.act.par_chunks(CHUNK).for_each(|chunk| { for &iu in chunk { let i = iu as usize; let o = i * 3; unsafe {
                    let kind = *an.kind.at(i);
                    if kind != 0 {
                        if kind == 2 {
                            let id = *an.id.at(i);
                            // (the first box with that id, as they come in the list)
                            let at = kin_ids.partition_point(|e| e.0 < id);
                            if at < kin_ids.len() && kin_ids[at].0 == id { let b = &kin[kin_ids[at].1 as usize]; for c in 0..3 { *an.pt.at(o + c) = b.min[c] + *an.loc.at(o + c); } }
                        }
                        let ak = mats[i / nb].adh_k;
                        for c in 0..3 {
                            let d = *q.at(o + c) - *an.pt.at(o + c);
                            if last { *anc_d.at(o + c) = d; } // how far everything else dragged it this iteration
                            *q.at(o + c) -= ak * d;
                        }
                    }
                    collide(&walls, an, q, p, rho[i], cool[i], i);
                } } });
            }
            self.prof[10] += t_sub.elapsed().as_secs_f64();
        }
        self.prof[3] += t_prof.elapsed().as_secs_f64(); t_prof = std::time::Instant::now();

        // ---- anchors creep / snap, springs flow / snap ----
        {
            let (q, alive, rho, anc_d, anc_stick, anc_axis, bodies, mats) = (&self.q[..], &self.alive[..], &self.rho[..], &self.anc_d[..], &self.anc_stick[..], &self.anc_axis[..], &self.bodies[..], &self.bmats[..]);
            let (anc_kind, anc_cool, anc_pt, anc_loc) = (Shared::of(&mut self.anc_kind), Shared::of(&mut self.anc_cool), Shared::of(&mut self.anc_pt), Shared::of(&mut self.anc_loc));
            let (spr_n, spr_j, spr_l) = (Shared::of(&mut self.spr_n), Shared::of(&mut self.spr_j), Shared::of(&mut self.spr_l));
            let rho_break = 1.6 * self.rho0;
            let (thread_thin, thread_reach) = (THREAD_THIN * self.rho0, THREAD_REACH * self.s);
            self.act.par_chunks(CHUNK).for_each(|chunk| { for &iu in chunk { let i = iu as usize; let o = i * 3; unsafe {
                let bd = &bodies[i / nb];
                let m = &mats[i / nb];
                if *anc_kind.at(i) != 0 {
                    let (ex, ey, ez) = (anc_d[o], anc_d[o + 1], anc_d[o + 2]);
                    let d = (ex * ex + ey * ey + ez * ez).sqrt();
                    if d > m.adh_break * anc_stick[i] || rho[i] > rho_break { *anc_kind.at(i) = 0; *anc_cool.at(i) = 0.35; }
                    else if m.adh_creep > 0.0 {
                        let cf = 1.0 - (-m.adh_creep * (0.25 + 0.75 * bd.melt) * dt).exp();
                        let e = [ex, ey, ez]; let ax = anc_axis[i] as usize;
                        for c in 0..3 { if c != ax { *anc_pt.at(o + c) += e[c] * cf; *anc_loc.at(o + c) += e[c] * cf; } } // slides along the surface only
                    }
                }
                let mut sidx = 0usize;
                while sidx < *spr_n.at(i) as usize {
                    let si = i * MAX_SPR + sidx;
                    let j = *spr_j.at(si) as usize; let oj = j * 3;
                    let mut l = *spr_l.at(si);
                    let (ex, ey, ez) = (q[o] - q[oj], q[o + 1] - q[oj + 1], q[o + 2] - q[oj + 2]);
                    let r = (ex * ex + ey * ey + ez * ez).sqrt();
                    if bd.plast > 0.0 {
                        let dl = r - l; let y = m.yield_ * l;
                        if dl.abs() > y { l += (1.0 - (-bd.plast * dt).exp()) * (dl - y * dl.signum()); *spr_l.at(si) = l; }
                    }
                    // honey drawn out to a single file of particles: the thread snaps, and what hung on it simply falls
                    let thread = m.dynamic_springs && r > thread_reach && rho[i] < thread_thin && rho[j] < thread_thin;
                    let gone = alive[j] == 0 || r > bd.brk * l || l > bd.max_l || thread;
                    if gone { let end = i * MAX_SPR + *spr_n.at(i) as usize - 1; *spr_j.at(si) = *spr_j.at(end); *spr_l.at(si) = *spr_l.at(end); *spr_n.at(i) -= 1; }
                    else { sidx += 1; }
                }
            } } });
        }
        self.prof[4] += t_prof.elapsed().as_secs_f64(); t_prof = std::time::Instant::now();

        // ---- velocities, viscosity, commit ----
        let idt = 1.0 / dt;
        let max_move = 0.3f32; // blocks per step; stops a crushed particle from being shot across the map
        {
            let walls = Walls { st: &self.st, cells: &self.st_cells, kin: &self.kin, kin_from: &self.kin_from, kin_near: &self.kin_near, nb: self.nb, rc: self.rc, rho_max: 1.25 * self.rho0 };
            let an = Anchors { kind: Shared::of(&mut self.anc_kind), id: Shared::of(&mut self.anc_id), pt: Shared::of(&mut self.anc_pt), loc: Shared::of(&mut self.anc_loc), stick: Shared::of(&mut self.anc_stick), axis: Shared::of(&mut self.anc_axis), fly: Shared::of(&mut self.fly) };
            let (p, rho, cool) = (&self.p[..], &self.rho[..], &self.anc_cool[..]);
            let (q, v) = (Shared::of(&mut self.q), Shared::of(&mut self.v));
            self.act.par_chunks(CHUNK).for_each(|chunk| { for &iu in chunk { let i = iu as usize; let o = i * 3; unsafe {
                let (ex, ey, ez) = (*q.at(o) - p[o], *q.at(o + 1) - p[o + 1], *q.at(o + 2) - p[o + 2]);
                let d2 = ex * ex + ey * ey + ez * ez;
                if d2 > max_move * max_move {
                    let sc = max_move / d2.sqrt(); *q.at(o) = p[o] + ex * sc; *q.at(o + 1) = p[o + 1] + ey * sc; *q.at(o + 2) = p[o + 2] + ez * sc;
                    // holding it back must not leave it inside a collider that moved further than that in one
                    // step (a piston head seen by a simulation that is running behind): out it goes again,
                    // keeping only the speed limit
                    collide(&walls, an, q, p, rho[i], cool[i], i);
                }
                let (vx, vy, vz) = ((*q.at(o) - p[o]) * idt, (*q.at(o + 1) - p[o + 1]) * idt, (*q.at(o + 2) - p[o + 2]) * idt);
                let v2 = vx * vx + vy * vy + vz * vz; let vmax = max_move * idt;
                let sc = if v2 > vmax * vmax { vmax / v2.sqrt() } else { 1.0 };
                *v.at(o) = vx * sc; *v.at(o + 1) = vy * sc; *v.at(o + 2) = vz * sc;
            } } });
        }
        // kernel value at 1.3 particle spacings: closer than that counts as touching
        let w_touch = { let r = 1.3 * self.s; let t = self.h * self.h - r * r; self.kp * t * t * t };
        for _ in 0..self.visc_iters {
            {
                let (nbr, nbr_lo, nbr_hi, nw, v, rho, mats) = (&self.nbr[..], &self.nbr_lo[..], &self.nbr_hi[..], &self.nw[..], &self.v[..], &self.rho[..], &self.bmats[..]);
                let (dpx, dpy, dpz, land) = (Shared::of(&mut self.dpx), Shared::of(&mut self.dpy), Shared::of(&mut self.dpz), Shared::of(&mut self.land));
                let fly = &self.fly[..];
                let (sup, anc_kind, anc_id) = (&self.sup[..], &self.anc_kind[..], &self.anc_id[..]);
                let sup_n = Shared::of(&mut self.sup_n);
                self.sorted[..n_act].par_chunks(CHUNK).for_each(|chunk| { for &iu in chunk { let i = iu as usize; let oi = i * 3;
                    let (mut x, mut y, mut z) = (0.0f32, 0.0f32, 0.0f32);
                    let k = mats[i / nb].xsph / rho[i];
                    let mut landed = false;
                    // held by a block or a piston part itself (a creature holds nothing up), or through its neighbours
                    let mut held = if anc_kind[i] == 1 || (anc_kind[i] == 2 && anc_id[i] > 0) { 1.0f32 } else { 0.0 };
                    for e in nbr_lo[i] as usize..nbr_hi[i] as usize {
                        let w = nw[e]; if w == 0.0 { continue; }
                        let j = nbr[e] as usize; let oj = j * 3;
                        held = held.max(sup[j] * SUPPORT_HOP);
                        let ws = k * w;
                        x += (v[oj] - v[oi]) * ws; y += (v[oj + 1] - v[oi + 1]) * ws; z += (v[oj + 2] - v[oi + 2]) * ws;
                        // touching goo that has already come to rest: this particle has landed too
                        landed |= w > w_touch && fly[j] == 0 && v[oj] * v[oj] + v[oj + 1] * v[oj + 1] + v[oj + 2] * v[oj + 2] < 2.25;
                    }
                    if landed && fly[i] != 0 { unsafe { *land.at(i) = 1; } }
                    unsafe { *dpx.at(i) = x; *dpy.at(i) = y; *dpz.at(i) = z; *sup_n.at(i) = held; }
                } });
            }
            {
                let (dpx, dpy, dpz, sup_n) = (&self.dpx[..], &self.dpy[..], &self.dpz[..], &self.sup_n[..]);
                let (v, sup, land, fly) = (Shared::of(&mut self.v), Shared::of(&mut self.sup), Shared::of(&mut self.land), Shared::of(&mut self.fly));
                // Safety: particle i is this thread's alone
                self.act.par_chunks(CHUNK * 4).for_each(|chunk| { for &iu in chunk { let i = iu as usize; let o = i * 3; unsafe {
                    *v.at(o) += dpx[i]; *v.at(o + 1) += dpy[i]; *v.at(o + 2) += dpz[i];
                    *sup.at(i) = sup_n[i];
                    if *land.at(i) != 0 { *land.at(i) = 0; *fly.at(i) = 0; }
                } } });
            }
        }
        let kill_y = self.kill_y;
        let mut vsum = vec![0.0f32; slots];
        // only bodies that were simulated this step get their bounds rebuilt; one that is shrinking away keeps its own
        let old_bb: Vec<[f32; 6]> = self.bodies.iter().map(|b| b.bb).collect();
        for b in 0..slots { if self.bodies[b].state == 1 && self.bodies[b].awake { self.bodies[b].bb = [f32::MAX, f32::MAX, f32::MAX, f32::MIN, f32::MIN, f32::MIN]; self.bodies[b].anchored = 0; } }
        {
            // every body takes its particles' new places, and adds up its own speed and bounds in the order of its particles
            let (q, v) = (&self.q[..], &self.v[..]);
            let (p, alive, scale, spr_n, anc_kind) = (Shared::of(&mut self.p), Shared::of(&mut self.alive), Shared::of(&mut self.scale), Shared::of(&mut self.spr_n), Shared::of(&mut self.anc_kind));
            let done: Vec<(f32, [f32; 6], u32)> = awake.par_iter().map(|&bu| {
                let b = bu as usize;
                let (mut speed, mut bb, mut anchored) = (0.0f32, [f32::MAX, f32::MAX, f32::MAX, f32::MIN, f32::MIN, f32::MIN], 0u32);
                // Safety: the particles of body b are this thread's alone
                for i in b * nb..(b + 1) * nb { unsafe {
                    if *alive.at(i) == 0 { continue; }
                    let o = i * 3;
                    let mut sp = 0.0;
                    for c in 0..3 { *p.at(o + c) = q[o + c]; sp += v[o + c] * v[o + c]; }
                    if *p.at(o + 1) < kill_y || !sp.is_finite() { *alive.at(i) = 0; *scale.at(i) = 0.0; *spr_n.at(i) = 0; *anc_kind.at(i) = 0; continue; }
                    speed += sp;
                    for c in 0..3 { bb[c] = bb[c].min(*p.at(o + c)); bb[c + 3] = bb[c + 3].max(*p.at(o + c)); }
                    if *anc_kind.at(i) != 0 { anchored += 1; }
                } }
                (speed, bb, anchored)
            }).collect();
            for (&bu, (speed, bb, anchored)) in awake.iter().zip(done) {
                let b = bu as usize;
                vsum[b] = speed; self.bodies[b].bb = bb; self.bodies[b].anchored = anchored;
            }
        }
        // a body that lost its last particle this step is freed on the next one; until then its bounds stay real numbers
        for b in 0..slots { if self.bodies[b].bb[0] > self.bodies[b].bb[3] { self.bodies[b].bb = old_bb[b]; } }

        self.prof[5] += t_prof.elapsed().as_secs_f64(); t_prof = std::time::Instant::now();
        // ---- sleep / wake ----
        for b in 0..slots {
            let m = self.bmats[b];
            let bd = &mut self.bodies[b];
            if bd.state == 0 || !bd.awake { continue; }
            let vrms = (vsum[b] / bd.count.max(1) as f32).sqrt();
            bd.quiet = bd.state == 1 && vrms < m.sleep_v && bd.age > m.melt_time + 1.0 && bd.pending == 0;
            bd.calm = if bd.quiet { bd.calm + 1 } else { 0 };
        }
        let moving_kin: Vec<KBox> = self.kin.iter().copied().filter(|b| b.vel != [0.0; 3]).collect();
        for b in 0..slots {
            if self.bodies[b].state == 0 || !self.bodies[b].awake { continue; }
            let bb = self.bodies[b].bb;
            let mut may_sleep = self.bodies[b].calm > CALM_STEPS;
            for c in 0..slots {
                if c == b || self.bodies[c].state == 0 { continue; }
                let cb = self.bodies[c].bb;
                if !(0..3).all(|a| bb[a] < cb[a + 3] + 0.3 && bb[a + 3] > cb[a] - 0.3) { continue; }
                if !self.bodies[b].quiet && !self.bodies[c].awake { self.bodies[c].awake = true; self.bodies[c].calm = 0; }
                if self.bodies[c].awake && !self.bodies[c].quiet { may_sleep = false; }
            }
            if moving_kin.iter().any(|k| (0..3).all(|a| k.max[a] > bb[a] - 0.6 && k.min[a] < bb[a + 3] + 0.6)) { may_sleep = false; self.bodies[b].calm = 0; }
            if may_sleep { self.bodies[b].awake = false; let nbv = b * nb * 3; for x in self.v[nbv..nbv + nb * 3].iter_mut() { *x = 0.0; } }
        }
        self.prof[6] += t_prof.elapsed().as_secs_f64();
    }
}
