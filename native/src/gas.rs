//! Smoke and fire on a grid: the processor's version of the gas solver.
//!
//! The game runs the same steps on the graphics card where it can (`shaders/core/gas_*.fsh`, one
//! pass over a texture per step below); this file is what is used where it cannot, and what the
//! numbers are tuned and tested on. The two are written to do the same thing with the same
//! numbers ([`Params`]), in the same order:
//!
//! 1. how the air spins (curl of the velocity), kept for step 2;
//! 2. the velocity carried along by itself, then pushed: hot gas up, smoke-laden gas a little
//!    down, small whirls kept from dying out (vorticity confinement), a little unrest over flames;
//! 3. how much the velocity piles gas up or thins it out in each cell, less what explosions add;
//! 4. the pressure that undoes that (Jacobi rounds, starting from last step's pressure);
//! 5. the velocity with that pressure taken off: gas goes round blocks instead of into them;
//! 6. smoke, heat, fuel and the colour of the flame carried along by the velocity; fuel that is
//!    hot enough burns into heat and smoke; heat is lost, smoke thins; burning blocks, burning
//!    creatures and explosions put new fuel, heat and smoke in.
//!
//! Two grids over the same box of the world: a coarse one for velocity and pressure, and one
//! `ratio` times finer for what is seen. Flow is cheap to compute coarsely and hard to tell from a
//! fine one once the smoke it carries is sharp; pressure is the expensive part, and this way it
//! costs an eighth.
//!
//! Lengths are in blocks, time in seconds. Cells are numbered `x + nx * (z + nz * y)`: one layer
//! of constant height after another, which is also how the grid lies in its texture.

use crate::sim::on_pool;
use rayon::prelude::*;

/// The numbers of the model. The Java side holds the same list (`GasParams`) and hands it to the
/// shaders; `SimCheck` fails when the two lists differ.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Params {
    /// Upward acceleration of gas at flame heat (heat 1), blocks/s².
    pub buoyancy: f32,
    /// Downward acceleration per unit of smoke.
    pub weight: f32,
    /// Heat lost per second, as a share of the heat (slow, keeps warm smoke rising for a while).
    pub cool: f32,
    /// Heat lost per second by glowing, times heat cubed (fast, what ends a flame).
    pub cool4: f32,
    /// Heat above which fuel burns.
    pub ignite: f32,
    /// Share of the fuel in a cell that burns per second once lit.
    pub burn: f32,
    /// Heat from burning one unit of fuel.
    pub heat: f32,
    /// Smoke from burning one unit of fuel.
    pub soot: f32,
    /// Smoke lost per second, as a share.
    pub smoke_fade: f32,
    /// Velocity lost per second, as a share.
    pub drag: f32,
    /// Strength of the push that keeps small whirls alive, 1/s.
    pub confine: f32,
    /// How much burning gas swells: divergence per unit of fuel burnt per second.
    pub swell: f32,
    /// Random sideways push over a source, blocks/s².
    pub unrest: f32,
    /// Rounds of the pressure solve.
    pub rounds: f32,
    /// Cells from the edge of the box over which gas fades out.
    pub edge: f32,
    /// Most smoke, heat and fuel a cell holds.
    pub most_smoke: f32,
    pub most_heat: f32,
    pub most_fuel: f32,
}

impl Default for Params {
    fn default() -> Self {
        Params {
            buoyancy: 14.0, weight: 0.6, cool: 0.15, cool4: 5.5, ignite: 0.22, burn: 3.5, heat: 2.6, soot: 0.12,
            smoke_fade: 0.14, drag: 0.25, confine: 9.0, swell: 1.6, unrest: 5.0, rounds: 16.0, edge: 3.0,
            most_smoke: 4.0, most_heat: 1.5, most_fuel: 2.0,
        }
    }
}

pub const PARAM_COUNT: usize = 18;

impl Params {
    pub fn to_array(&self) -> [f32; PARAM_COUNT] {
        [self.buoyancy, self.weight, self.cool, self.cool4, self.ignite, self.burn, self.heat, self.soot, self.smoke_fade, self.drag,
         self.confine, self.swell, self.unrest, self.rounds, self.edge, self.most_smoke, self.most_heat, self.most_fuel]
    }
    pub fn from_slice(v: &[f32]) -> Option<Params> {
        if v.len() < PARAM_COUNT || v[..PARAM_COUNT].iter().any(|x| !x.is_finite()) { return None; }
        Some(Params {
            buoyancy: v[0], weight: v[1], cool: v[2], cool4: v[3], ignite: v[4], burn: v[5], heat: v[6], soot: v[7], smoke_fade: v[8], drag: v[9],
            confine: v[10], swell: v[11], unrest: v[12], rounds: v[13], edge: v[14], most_smoke: v[15], most_heat: v[16], most_fuel: v[17],
        })
    }
}

/// What a block of the world is to the gas (second byte of its four in the world map).
pub mod kind {
    pub const NONE: u8 = 0;
    /// A block of fire: flames over all of it.
    pub const FIRE: u8 = 1;
    /// A lit campfire: flames in the middle, a lot of smoke.
    pub const CAMPFIRE: u8 = 2;
    /// A torch: a thread of smoke.
    pub const TORCH: u8 = 3;
    /// The air right over lava: heat, a haze.
    pub const OVER_LAVA: u8 = 4;
    /// The air right over a lit furnace: smoke as from a chimney.
    pub const CHIMNEY: u8 = 5;
    /// Added to any of the above: the flame is the blue of soul fire.
    pub const SOUL: u8 = 16;
}

/// Bytes per block in the world map: solid (0 or 1), kind, strength (0..255 for 0..2), light (sky * 16 + block).
pub const WORLD_BYTES: usize = 4;

/// A source that is not a block: a burning creature, an explosion.
#[derive(Clone, Copy, Debug, Default)]
pub struct Blob {
    /// Centre, in blocks from the corner of the box, and radius.
    pub pos: [f32; 3],
    pub radius: f32,
    /// Fuel and smoke put in per second, heat the gas is brought up to, share of soul fire.
    pub fuel: f32,
    pub smoke: f32,
    pub heat: f32,
    pub soul: f32,
    /// Velocity the gas inside is dragged towards (blocks/s), and how much gas is made per second (an explosion).
    pub vel: [f32; 3],
    pub expand: f32,
}

pub const BLOB_FLOATS: usize = 12;

impl Blob {
    pub fn from_slice(v: &[f32]) -> Blob {
        Blob { pos: [v[0], v[1], v[2]], radius: v[3], fuel: v[4], smoke: v[5], heat: v[6], soul: v[7], vel: [v[8], v[9], v[10]], expand: v[11] }
    }
}

pub struct Gas {
    /// Size of the box in blocks.
    pub bx: usize, pub by: usize, pub bz: usize,
    /// Coarse cells per block, fine cells per coarse cell.
    pub cpb: usize, pub ratio: usize,
    /// Coarse grid.
    pub nx: usize, pub ny: usize, pub nz: usize,
    /// Fine grid.
    pub fx: usize, pub fy: usize, pub fz: usize,
    /// Velocity on the coarse grid, blocks/s (the fourth number is not used).
    pub vel: Vec<[f32; 4]>,
    vel2: Vec<[f32; 4]>,
    /// How the air spins, and how fast (fourth number).
    curl: Vec<[f32; 4]>,
    pub p: Vec<f32>, q: Vec<f32>, rhs: Vec<f32>,
    solid: Vec<u8>,
    /// Smoke, heat, fuel, and how much of the fuel and heat is soul fire's, on the fine grid.
    pub gas: Vec<[f32; 4]>,
    gas2: Vec<[f32; 4]>,
    gas3: Vec<[f32; 4]>,
    pub world: Vec<u8>,
    pub params: Params,
    pub time: f32,
    /// Whether smoke and heat are carried along the sharper way (two passes instead of one).
    pub sharp: bool,
}

#[inline]
fn hash(x: u32, y: u32, z: u32, t: u32) -> f32 {
    let mut h = x.wrapping_mul(0x9E37_79B1) ^ y.wrapping_mul(0x85EB_CA77) ^ z.wrapping_mul(0xC2B2_AE3D) ^ t.wrapping_mul(0x27D4_EB2F);
    h ^= h >> 15; h = h.wrapping_mul(0x2C1B_3C6D); h ^= h >> 12; h = h.wrapping_mul(0x297A_2D39); h ^= h >> 15;
    (h >> 8) as f32 / 16_777_216.0
}

/// A number between 0 and 1 for a place that changes smoothly in time, `rate` times a second.
#[inline]
pub fn flicker(x: u32, y: u32, z: u32, time: f32, rate: f32) -> f32 {
    let t = time * rate;
    let k = t.floor();
    let f = t - k;
    let f = f * f * (3.0 - 2.0 * f);
    let k = k as u32;
    hash(x, y, z, k) * (1.0 - f) + hash(x, y, z, k.wrapping_add(1)) * f
}

/// The eight cells around a point given in cells (the middle of cell `i` is at `i + 0.5`), and the share of each.
#[inline]
fn around(nx: usize, ny: usize, nz: usize, x: f32, y: f32, z: f32) -> ([usize; 8], [f32; 8]) {
    let gx = (x - 0.5).max(0.0).min(nx as f32 - 1.0);
    let gy = (y - 0.5).max(0.0).min(ny as f32 - 1.0);
    let gz = (z - 0.5).max(0.0).min(nz as f32 - 1.0);
    let (x0, y0, z0) = (gx as usize, gy as usize, gz as usize);
    let (x1, y1, z1) = ((x0 + 1).min(nx - 1), (y0 + 1).min(ny - 1), (z0 + 1).min(nz - 1));
    let (tx, ty, tz) = (gx - x0 as f32, gy - y0 as f32, gz - z0 as f32);
    let (a, b, c, d) = (nx * (z0 + nz * y0), nx * (z1 + nz * y0), nx * (z0 + nz * y1), nx * (z1 + nz * y1));
    let (sx, sy, sz) = (1.0 - tx, 1.0 - ty, 1.0 - tz);
    ([a + x0, a + x1, b + x0, b + x1, c + x0, c + x1, d + x0, d + x1],
     [sx * sz * sy, tx * sz * sy, sx * tz * sy, tx * tz * sy, sx * sz * ty, tx * sz * ty, sx * tz * ty, tx * tz * ty])
}

/// Value of a field of four numbers a cell at a point, between the eight cells around it.
#[inline]
fn tri4(f: &[[f32; 4]], nx: usize, ny: usize, nz: usize, x: f32, y: f32, z: f32) -> [f32; 4] {
    let (at, share) = around(nx, ny, nz, x, y, z);
    let mut out = [0.0f32; 4];
    for k in 0..8 {
        let v = &f[at[k]];
        for c in 0..4 { out[c] += v[c] * share[k]; }
    }
    out
}

impl Gas {
    /// A box of `bx * by * bz` blocks with `cpb` coarse cells to the block and `ratio` fine cells to the coarse one.
    pub fn new(bx: usize, by: usize, bz: usize, cpb: usize, ratio: usize) -> Gas {
        let (bx, by, bz, cpb, ratio) = (bx.max(2), by.max(2), bz.max(2), cpb.clamp(1, 8), ratio.clamp(1, 4));
        let (nx, ny, nz) = (bx * cpb, by * cpb, bz * cpb);
        let (fx, fy, fz) = (nx * ratio, ny * ratio, nz * ratio);
        let (n, f) = (nx * ny * nz, fx * fy * fz);
        Gas {
            bx, by, bz, cpb, ratio, nx, ny, nz, fx, fy, fz,
            vel: vec![[0.0; 4]; n], vel2: vec![[0.0; 4]; n], curl: vec![[0.0; 4]; n],
            p: vec![0.0; n], q: vec![0.0; n], rhs: vec![0.0; n], solid: vec![0; n],
            gas: vec![[0.0; 4]; f], gas2: vec![[0.0; 4]; f], gas3: vec![[0.0; 4]; f],
            world: vec![0; bx * by * bz * WORLD_BYTES], params: Params::default(), time: 0.0, sharp: true,
        }
    }

    #[inline] pub fn block(&self, x: usize, y: usize, z: usize) -> usize { (x + self.bx * (z + self.bz * y)) * WORLD_BYTES }
    #[inline] pub fn coarse(&self, x: usize, y: usize, z: usize) -> usize { x + self.nx * (z + self.nz * y) }
    #[inline] pub fn fine(&self, x: usize, y: usize, z: usize) -> usize { x + self.fx * (z + self.fz * y) }

    /// Makes a block solid or not, and a source or not (`strength` 1 is the usual).
    pub fn set_block(&mut self, x: usize, y: usize, z: usize, solid: bool, kind: u8, strength: f32) {
        let i = self.block(x, y, z);
        self.world[i] = solid as u8;
        self.world[i + 1] = kind;
        self.world[i + 2] = (strength * 127.5).round().clamp(0.0, 255.0) as u8;
    }

    /// The whole map at once, as the game hands it over.
    pub fn set_world(&mut self, map: &[u8]) {
        let n = self.world.len().min(map.len());
        self.world[..n].copy_from_slice(&map[..n]);
    }

    pub fn clear(&mut self) {
        self.vel.iter_mut().for_each(|v| *v = [0.0; 4]);
        self.gas.iter_mut().for_each(|v| *v = [0.0; 4]);
        self.p.iter_mut().for_each(|v| *v = 0.0);
    }

    /// The box moved by whole blocks: what is in it stays where it is in the world.
    pub fn shift(&mut self, dx: i32, dy: i32, dz: i32) {
        if dx == 0 && dy == 0 && dz == 0 { return; }
        let c = self.cpb as i32;
        let f = c * self.ratio as i32;
        moved(&mut self.vel, self.nx, self.ny, self.nz, dx * c, dy * c, dz * c, [0.0; 4]);
        moved(&mut self.p, self.nx, self.ny, self.nz, dx * c, dy * c, dz * c, 0.0);
        moved(&mut self.gas, self.fx, self.fy, self.fz, dx * f, dy * f, dz * f, [0.0; 4]);
    }

    /// One step of `dt` seconds.
    pub fn step(&mut self, dt: f32, blobs: &[Blob]) {
        if !(dt > 0.0) { return; }
        let dt = dt.min(0.1);
        on_pool(|| {
            self.mark_solid();
            self.spin();
            self.velocity(dt, blobs);
            self.divergence(dt, blobs);
            self.pressure();
            self.project();
            self.scalars(dt, blobs);
        });
        self.time += dt;
    }

    fn mark_solid(&mut self) {
        let (nx, nz, cpb, bx, bz) = (self.nx, self.nz, self.cpb, self.bx, self.bz);
        let world = &self.world;
        self.solid.par_chunks_mut(nx * nz).enumerate().for_each(|(y, layer)| {
            for z in 0..nz {
                for x in 0..nx {
                    layer[x + nx * z] = world[(x / cpb + bx * (z / cpb + bz * (y / cpb))) * WORLD_BYTES];
                }
            }
        });
    }

    fn spin(&mut self) {
        let (nx, ny, nz) = (self.nx, self.ny, self.nz);
        let k = 0.5 * self.cpb as f32;
        let v = &self.vel;
        self.curl.par_chunks_mut(nx * nz).enumerate().for_each(|(y, out)| {
            for z in 0..nz {
                for x in 0..nx {
                    let at = |x: usize, y: usize, z: usize| x + nx * (z + nz * y);
                    let (xl, xr) = (&v[at(x.saturating_sub(1), y, z)], &v[at((x + 1).min(nx - 1), y, z)]);
                    let (yl, yr) = (&v[at(x, y.saturating_sub(1), z)], &v[at(x, (y + 1).min(ny - 1), z)]);
                    let (zl, zr) = (&v[at(x, y, z.saturating_sub(1))], &v[at(x, y, (z + 1).min(nz - 1))]);
                    let wx = ((yr[2] - yl[2]) - (zr[1] - zl[1])) * k;
                    let wy = ((zr[0] - zl[0]) - (xr[2] - xl[2])) * k;
                    let wz = ((xr[1] - xl[1]) - (yr[0] - yl[0])) * k;
                    out[x + nx * z] = [wx, wy, wz, (wx * wx + wy * wy + wz * wz).sqrt()];
                }
            }
        });
    }

    fn velocity(&mut self, dt: f32, blobs: &[Blob]) {
        let (nx, ny, nz, fx, fy, fz) = (self.nx, self.ny, self.nz, self.fx, self.fy, self.fz);
        let (cpb, bx, bz) = (self.cpb, self.bx, self.bz);
        let (c, r, h) = (cpb as f32, self.ratio as f32, 1.0 / cpb as f32);
        let pr = self.params;
        let keep = (-pr.drag * dt).exp();
        let time = self.time;
        let (vel, curl, gas, solid, world) = (&self.vel, &self.curl, &self.gas, &self.solid, &self.world);
        let layer = nx * nz;
        self.vel2.par_chunks_mut(layer).enumerate().for_each(|(y, out)| {
            for z in 0..nz {
                for x in 0..nx {
                    let o = x + nx * z;
                    let i = o + layer * y;
                    if solid[i] != 0 { out[o] = [0.0; 4]; continue; }
                    let (px, py, pz) = (x as f32 + 0.5, y as f32 + 0.5, z as f32 + 0.5);
                    // carried along by itself
                    let u = vel[i];
                    let a = tri4(vel, nx, ny, nz, px - dt * u[0] * c, py - dt * u[1] * c, pz - dt * u[2] * c);
                    let (mut ax, mut ay, mut az) = (a[0] * keep, a[1] * keep, a[2] * keep);
                    // hot gas up, smoke down
                    let g = tri4(gas, fx, fy, fz, px * r, py * r, pz * r);
                    ay += dt * (pr.buoyancy * g[1] - pr.weight * g[0]);
                    // small whirls kept alive
                    if x > 0 && x < nx - 1 && y > 0 && y < ny - 1 && z > 0 && z < nz - 1 {
                        let (gx, gy, gz) = (curl[i + 1][3] - curl[i - 1][3], curl[i + layer][3] - curl[i - layer][3], curl[i + nx][3] - curl[i - nx][3]);
                        let len = (gx * gx + gy * gy + gz * gz).sqrt();
                        if len > 1.0e-5 {
                            let k = dt * pr.confine * h / len;
                            let w = curl[i];
                            ax += k * (gy * w[2] - gz * w[1]);
                            ay += k * (gz * w[0] - gx * w[2]);
                            az += k * (gx * w[1] - gy * w[0]);
                        }
                    }
                    // unrest over a source: without it a flame in still air is a straight candle
                    let b = (x / cpb + bx * (z / cpb + bz * (y / cpb))) * WORLD_BYTES;
                    if world[b + 1] != 0 {
                        let much = dt * pr.unrest * world[b + 2] as f32 / 127.5;
                        ax += much * (flicker(x as u32, y as u32, z as u32, time, 7.0) - 0.5);
                        az += much * (flicker(z as u32, y as u32, x as u32 + 977, time, 7.0) - 0.5);
                    }
                    // creatures and explosions drag the gas with them
                    for blob in blobs {
                        let (dx, dy, dz) = (px * h - blob.pos[0], py * h - blob.pos[1], pz * h - blob.pos[2]);
                        let d2 = dx * dx + dy * dy + dz * dz;
                        if d2 < blob.radius * blob.radius {
                            let k = (1.0 - d2 / (blob.radius * blob.radius)) * (1.0 - (-8.0 * dt).exp());
                            ax += (blob.vel[0] - ax) * k; ay += (blob.vel[1] - ay) * k; az += (blob.vel[2] - az) * k;
                        }
                    }
                    out[o] = [ax, ay, az, 0.0];
                }
            }
        });
        std::mem::swap(&mut self.vel, &mut self.vel2);
    }

    fn divergence(&mut self, dt: f32, blobs: &[Blob]) {
        let (nx, ny, nz, fx, fy, fz) = (self.nx, self.ny, self.nz, self.fx, self.fy, self.fz);
        let (c, r, h) = (self.cpb as f32, self.ratio as f32, 1.0 / self.cpb as f32);
        let pr = self.params;
        let burning = 1.0 - (-pr.burn * dt).exp();
        let (vel, solid, gas) = (&self.vel, &self.solid, &self.gas);
        let layer = nx * nz;
        self.rhs.par_chunks_mut(layer).enumerate().for_each(|(y, rhs)| {
            for z in 0..nz {
                for x in 0..nx {
                    let o = x + nx * z;
                    let i = o + layer * y;
                    if solid[i] != 0 { rhs[o] = 0.0; continue; }
                    // a neighbour that is a block does not move; outside the box the gas moves as it does in this cell (the edge lets through what comes)
                    let v = |j: usize, inside: bool, axis: usize| if !inside { vel[i][axis] } else if solid[j] != 0 { 0.0 } else { vel[j][axis] };
                    let d = (v(i + 1, x + 1 < nx, 0) - v(i.wrapping_sub(1), x > 0, 0)
                        + v(i + layer, y + 1 < ny, 1) - v(i.wrapping_sub(layer), y > 0, 1)
                        + v(i + nx, z + 1 < nz, 2) - v(i.wrapping_sub(nx), z > 0, 2)) * 0.5 * c;
                    // gas that burns takes more room; an explosion makes a lot of gas at once
                    let (px, py, pz) = (x as f32 + 0.5, y as f32 + 0.5, z as f32 + 0.5);
                    let mut made = 0.0;
                    if pr.swell > 0.0 {
                        let g = tri4(gas, fx, fy, fz, px * r, py * r, pz * r);
                        if g[1] > pr.ignite { made += pr.swell * g[2] * burning / dt; }
                    }
                    for blob in blobs {
                        if blob.expand == 0.0 { continue; }
                        let (dx, dy, dz) = (px * h - blob.pos[0], py * h - blob.pos[1], pz * h - blob.pos[2]);
                        let d2 = dx * dx + dy * dy + dz * dz;
                        if d2 < blob.radius * blob.radius { made += blob.expand * (1.0 - d2 / (blob.radius * blob.radius)); }
                    }
                    rhs[o] = d - made;
                }
            }
        });
    }

    fn pressure(&mut self) {
        let (nx, ny, nz) = (self.nx, self.ny, self.nz);
        let h2 = 1.0 / (self.cpb * self.cpb) as f32;
        let layer = nx * nz;
        let rounds = (self.params.rounds.max(1.0) as usize + 1) & !1; // even: the result ends up where it started
        for _ in 0..rounds {
            let (p, rhs, solid) = (&self.p, &self.rhs, &self.solid);
            self.q.par_chunks_mut(layer).enumerate().for_each(|(y, q)| {
                for z in 0..nz {
                    for x in 0..nx {
                        let o = x + nx * z;
                        let i = o + layer * y;
                        if solid[i] != 0 { q[o] = 0.0; continue; }
                        // a block has the pressure of the cell beside it (nothing is pushed into it); outside the box it is zero
                        let at = |j: usize, inside: bool| if !inside { 0.0 } else if solid[j] != 0 { p[i] } else { p[j] };
                        q[o] = (at(i + 1, x + 1 < nx) + at(i.wrapping_sub(1), x > 0) + at(i + layer, y + 1 < ny) + at(i.wrapping_sub(layer), y > 0)
                            + at(i + nx, z + 1 < nz) + at(i.wrapping_sub(nx), z > 0) - h2 * rhs[i]) / 6.0;
                    }
                }
            });
            std::mem::swap(&mut self.p, &mut self.q);
        }
    }

    fn project(&mut self) {
        let (nx, ny, nz) = (self.nx, self.ny, self.nz);
        let k = 0.5 * self.cpb as f32;
        let layer = nx * nz;
        let (p, solid) = (&self.p, &self.solid);
        self.vel.par_chunks_mut(layer).enumerate().for_each(|(y, vel)| {
            for z in 0..nz {
                for x in 0..nx {
                    let o = x + nx * z;
                    let i = o + layer * y;
                    if solid[i] != 0 { continue; }
                    let at = |j: usize, inside: bool| if !inside { 0.0 } else if solid[j] != 0 { p[i] } else { p[j] };
                    let wall = |j: usize, inside: bool| inside && solid[j] != 0;
                    let (xr, xl) = (x + 1 < nx, x > 0);
                    let (yr, yl) = (y + 1 < ny, y > 0);
                    let (zr, zl) = (z + 1 < nz, z > 0);
                    let mut ax = vel[o][0] - (at(i + 1, xr) - at(i.wrapping_sub(1), xl)) * k;
                    let mut ay = vel[o][1] - (at(i + layer, yr) - at(i.wrapping_sub(layer), yl)) * k;
                    let mut az = vel[o][2] - (at(i + nx, zr) - at(i.wrapping_sub(nx), zl)) * k;
                    // along a block, not into it
                    if (ax > 0.0 && wall(i + 1, xr)) || (ax < 0.0 && wall(i.wrapping_sub(1), xl)) { ax = 0.0; }
                    if (ay > 0.0 && wall(i + layer, yr)) || (ay < 0.0 && wall(i.wrapping_sub(layer), yl)) { ay = 0.0; }
                    if (az > 0.0 && wall(i + nx, zr)) || (az < 0.0 && wall(i.wrapping_sub(nx), zl)) { az = 0.0; }
                    vel[o] = [ax, ay, az, 0.0];
                }
            }
        });
    }

    /// Where the gas in a fine cell came from a step ago (in fine cells), or the cell itself if that is inside a block.
    #[inline]
    fn came_from(&self, x: usize, y: usize, z: usize, dt: f32) -> (f32, f32, f32) {
        let fpb = (self.cpb * self.ratio) as f32;
        let inv_r = 1.0 / self.ratio as f32;
        let (px, py, pz) = (x as f32 + 0.5, y as f32 + 0.5, z as f32 + 0.5);
        let u = tri4(&self.vel, self.nx, self.ny, self.nz, px * inv_r, py * inv_r, pz * inv_r);
        let (qx, qy, qz) = (px - dt * u[0] * fpb, py - dt * u[1] * fpb, pz - dt * u[2] * fpb);
        let cell = 1.0 / fpb;
        let (jx, jy, jz) = ((qx * cell).max(0.0) as usize, (qy * cell).max(0.0) as usize, (qz * cell).max(0.0) as usize);
        if jx < self.bx && jy < self.by && jz < self.bz && self.world[(jx + self.bx * (jz + self.bz * jy)) * WORLD_BYTES] != 0 { (px, py, pz) } else { (qx, qy, qz) }
    }

    fn scalars(&mut self, dt: f32, blobs: &[Blob]) {
        let (fx, fy, fz) = (self.fx, self.fy, self.fz);
        let (bx, bz) = (self.bx, self.bz);
        let fpb = self.cpb * self.ratio;
        let cell = 1.0 / fpb as f32;
        let pr = self.params;
        let time = self.time;
        let burning = 1.0 - (-pr.burn * dt).exp();
        let thin = (-pr.smoke_fade * dt).exp();
        let layer = fx * fz;
        let sharp = self.sharp;

        // carried along: where each cell's gas was a step ago
        let mut first = std::mem::take(&mut self.gas2);
        {
            let this = &*self;
            first.par_chunks_mut(layer).enumerate().for_each(|(y, out)| {
                for z in 0..fz {
                    for x in 0..fx {
                        if this.world[(x / fpb + bx * (z / fpb + bz * (y / fpb))) * WORLD_BYTES] != 0 { out[x + fx * z] = [0.0; 4]; continue; }
                        let (qx, qy, qz) = this.came_from(x, y, z, dt);
                        out[x + fx * z] = tri4(&this.gas, fx, fy, fz, qx, qy, qz);
                    }
                }
            });
        }
        let mut next = std::mem::take(&mut self.gas3);
        {
            let this = &*self;
            let (gas, world, first) = (&this.gas, &this.world, &first);
            next.par_chunks_mut(layer).enumerate().for_each(|(y, out)| {
                for z in 0..fz {
                    for x in 0..fx {
                        let o = x + fx * z;
                        let (kx, ky, kz) = (x / fpb, y / fpb, z / fpb);
                        let b = (kx + bx * (kz + bz * ky)) * WORLD_BYTES;
                        if world[b] != 0 { out[o] = [0.0; 4]; continue; }
                        let (px, py, pz) = (x as f32 + 0.5, y as f32 + 0.5, z as f32 + 0.5);
                        let mut v = first[o + layer * y];
                        if sharp {
                            // carrying is a blur; carried back again it shows how much of one, and half of that is put back,
                            // within what the cells the gas came from held
                            let (qx, qy, qz) = this.came_from(x, y, z, dt);
                            let back = tri4(first, fx, fy, fz, 2.0 * px - qx, 2.0 * py - qy, 2.0 * pz - qz);
                            let (at, _) = around(fx, fy, fz, qx, qy, qz);
                            let was = gas[o + layer * y];
                            for c in 0..4 {
                                let (mut lo, mut hi) = (f32::MAX, f32::MIN);
                                for k in 0..8 { let w = gas[at[k]][c]; lo = lo.min(w); hi = hi.max(w); }
                                v[c] = (v[c] + 0.5 * (was[c] - back[c])).max(lo).min(hi);
                            }
                        }
                        // the fourth number is how much of the fuel and heat here is soul fire's: an amount, so that mixing with clean air leaves the share alone
                        let (mut s, mut t, mut fu, mut bl) = (v[0], v[1], v[2], v[3]);
                        let share = |bl: f32, fu: f32, t: f32| if fu + t > 1.0e-6 { (bl / (fu + t)).clamp(0.0, 1.0) } else { 0.0 };
                        // fuel that is hot enough burns
                        if t > pr.ignite && fu > 0.0 {
                            let burnt = fu * burning;
                            bl += (pr.heat - 1.0) * burnt * share(bl, fu, t);
                            fu -= burnt; t += pr.heat * burnt; s += pr.soot * burnt;
                        }
                        // heat is lost, smoke thins
                        let cooled = t / (1.0 + dt * (pr.cool + pr.cool4 * t * t * t));
                        bl -= (t - cooled) * share(bl, fu, t);
                        t = cooled;
                        s *= thin;
                        // blocks that burn
                        let k = world[b + 1];
                        if k != 0 {
                            let much = world[b + 2] as f32 / 127.5;
                            let (ix, iy, iz) = (px * cell - kx as f32, py * cell - ky as f32, pz * cell - kz as f32);
                            let blue = if k & kind::SOUL != 0 { 1.0 } else { 0.0 };
                            let live = 0.55 + 0.9 * flicker(x as u32 / 2, y as u32 / 2, z as u32 / 2, time, 5.0);
                            let [fuel_in, smoke_in, heat_to] = source(k & 15, ix, iy, iz, 0.5 * cell, px * cell, pz * cell, time, live);
                            fu += dt * much * fuel_in;
                            bl += dt * much * fuel_in * blue;
                            s += dt * much * smoke_in;
                            let to = heat_to * much.min(1.0);
                            if to > t { bl += (to - t) * blue; t = to; }
                        }
                        // creatures that burn, explosions
                        for blob in blobs {
                            let (dx, dy, dz) = (px * cell - blob.pos[0], py * cell - blob.pos[1], pz * cell - blob.pos[2]);
                            let d2 = dx * dx + dy * dy + dz * dz;
                            if d2 < blob.radius * blob.radius {
                                let near = 1.0 - d2 / (blob.radius * blob.radius);
                                fu += dt * near * blob.fuel;
                                bl += dt * near * blob.fuel * blob.soul;
                                s += dt * near * blob.smoke;
                                let to = blob.heat * near;
                                if to > t { bl += (to - t) * blob.soul; t = to; }
                            }
                        }
                        // nothing at the edge of the box: gas that leaves it is gone, and is not seen to be cut off
                        let edge = (x.min(fx - 1 - x).min(z.min(fz - 1 - z)).min(y.min(fy - 1 - y)) as f32 / pr.edge).min(1.0);
                        let (t, fu) = ((t * edge).clamp(0.0, pr.most_heat), (fu * edge).clamp(0.0, pr.most_fuel));
                        out[o] = [(s * edge).clamp(0.0, pr.most_smoke), t, fu, (bl * edge).clamp(0.0, t + fu)];
                    }
                }
            });
        }
        self.gas2 = first;
        self.gas3 = std::mem::replace(&mut self.gas, next);
    }

    /// The fine grid as a picture the renderer reads: layers side by side, `tiles` to a row, four bytes a cell:
    /// smoke (square root of its share of the most), heat (share of the most), share of soul fire, fuel (share of the most).
    pub fn write_atlas(&self, out: &mut [u8], tiles: usize) {
        let (fx, fy, fz) = (self.fx, self.fy, self.fz);
        let tiles = tiles.max(1);
        let width = tiles * fx;
        let rows = (fy + tiles - 1) / tiles;
        if out.len() < width * rows * fz * 4 { return; }
        let pr = self.params;
        let gas = &self.gas;
        on_pool(|| {
            out[..width * rows * fz * 4].par_chunks_mut(width * fz * 4).enumerate().for_each(|(row, band)| {
                for tile in 0..tiles {
                    let y = row * tiles + tile;
                    if y >= fy { break; }
                    for z in 0..fz {
                        let from = fx * (z + fz * y);
                        let to = (z * width + tile * fx) * 4;
                        for x in 0..fx {
                            let (v, o) = (&gas[from + x], to + x * 4);
                            band[o] = ((v[0] / pr.most_smoke).max(0.0).sqrt() * 255.0 + 0.5) as u8;
                            band[o + 1] = ((v[1] / pr.most_heat).clamp(0.0, 1.0) * 255.0 + 0.5) as u8;
                            band[o + 2] = ((v[3] / (v[1] + v[2] + 1.0e-4)).clamp(0.0, 1.0) * 255.0 + 0.5) as u8;
                            band[o + 3] = ((v[2] / pr.most_fuel).clamp(0.0, 1.0) * 255.0 + 0.5) as u8;
                        }
                    }
                }
            });
        });
    }

    /// Smoke in all, heat in all (both in blocks' worth), the hottest cell, the fastest cell (blocks/s).
    pub fn totals(&self) -> [f32; 4] {
        let cell = 1.0 / ((self.cpb * self.ratio) as f32).powi(3);
        let smoke: f64 = self.gas.iter().map(|v| v[0] as f64).sum();
        let heat: f64 = self.gas.iter().map(|v| v[1] as f64).sum();
        let hottest = self.gas.iter().map(|v| v[1]).fold(0.0, f32::max);
        let fastest = self.vel.iter().map(|v| v[0] * v[0] + v[1] * v[1] + v[2] * v[2]).fold(0.0, f32::max);
        [smoke as f32 * cell, heat as f32 * cell, hottest, fastest.sqrt()]
    }

    /// Smoke in each layer of the fine grid, from the bottom up (in blocks' worth).
    pub fn smoke_by_height(&self) -> Vec<f32> {
        let cell = 1.0 / ((self.cpb * self.ratio) as f32).powi(3);
        self.gas.chunks(self.fx * self.fz).map(|layer| layer.iter().map(|v| v[0] as f64).sum::<f64>() as f32 * cell).collect()
    }

    /// The greatest piling up or thinning out left in the velocity (1/s), over cells not next to a block or the edge.
    pub fn worst_divergence(&self) -> f32 {
        let (nx, ny, nz) = (self.nx, self.ny, self.nz);
        let layer = nx * nz;
        let k = 0.5 * self.cpb as f32;
        let mut worst = 0.0f32;
        let v = &self.vel;
        for y in 1..ny - 1 { for z in 1..nz - 1 { for x in 1..nx - 1 {
            let i = x + nx * z + layer * y;
            if [i, i + 1, i - 1, i + nx, i - nx, i + layer, i - layer].iter().any(|&j| self.solid[j] != 0) { continue; }
            worst = worst.max(((v[i + 1][0] - v[i - 1][0] + v[i + layer][1] - v[i - layer][1] + v[i + nx][2] - v[i - nx][2]) * k).abs());
        } } }
        worst
    }
}

/// A number between 0 and 1 that changes smoothly from place to place (`x`, `z` in any unit: one lump per unit) and in time.
#[inline]
pub fn lumps(x: f32, z: f32, time: f32, rate: f32) -> f32 {
    let (x0, z0) = (x.floor(), z.floor());
    let (tx, tz) = (x - x0, z - z0);
    let (tx, tz) = (tx * tx * (3.0 - 2.0 * tx), tz * tz * (3.0 - 2.0 * tz));
    let (xi, zi) = (x0 as i32 as u32, z0 as i32 as u32);
    let at = |dx: u32, dz: u32| flicker(xi.wrapping_add(dx), 7, zi.wrapping_add(dz), time, rate);
    (at(0, 0) * (1.0 - tx) + at(1, 0) * tx) * (1.0 - tz) + (at(0, 1) * (1.0 - tx) + at(1, 1) * tx) * tz
}

/// What a burning block puts into a fine cell: fuel and smoke per second, and the heat the cell is brought up to.
/// `ix, iy, iz` is the middle of the cell inside the block (0..1 each way), `half` half the cell's width (in blocks),
/// `wx, wz` the place in the world (blocks), `live` the flicker of the moment (about 0.5..1.5). Fire does not burn evenly
/// over a surface: fuel comes up in a few jets that wander, and each becomes a tongue of flame. Layers and discs are
/// counted by how much of the cell they cover, so a coarse grid gets the same amounts as a fine one.
#[inline]
pub fn source(kind: u8, ix: f32, iy: f32, iz: f32, half: f32, wx: f32, wz: f32, time: f32, live: f32) -> [f32; 3] {
    let (dx, dz) = (ix - 0.5, iz - 0.5);
    let off = (dx * dx + dz * dz).sqrt();
    let layer = |lo: f32, hi: f32| (((iy + half).min(hi) - (iy - half).max(lo)) / (2.0 * half)).clamp(0.0, 1.0);
    let disc = |r: f32| ((r + half - off) / (2.0 * half)).clamp(0.0, 1.0);
    match kind {
        kind::FIRE => {
            let low = layer(0.0, 0.3);
            if low <= 0.0 { return [0.0; 3]; }
            let jet = ((lumps(wx * 3.0, wz * 3.0, time, 1.6) - 0.3) / 0.4).clamp(0.0, 1.0);
            let jet = jet * jet * (3.0 - 2.0 * jet);
            [11.0 * jet * live * low, 0.0, if layer(0.0, 0.15) > 0.0 { 0.5 * jet } else { 0.0 }]
        }
        kind::CAMPFIRE => {
            let cover = layer(0.15, 0.6) * disc(0.3);
            if cover <= 0.0 { return [0.0; 3]; }
            let jet = ((lumps(wx * 4.0, wz * 4.0, time, 1.6) - 0.25) / 0.4).clamp(0.0, 1.0);
            [11.0 * cover * jet * live, 3.0 * cover, if layer(0.15, 0.35) > 0.0 { 0.5 * disc(0.3) * jet } else { 0.0 }]
        }
        kind::TORCH => { let cover = layer(0.6, 0.95) * disc(0.12); if cover > 0.0 { [0.0, 0.7 * cover, 0.16] } else { [0.0; 3] } }
        kind::OVER_LAVA => { let cover = layer(0.0, 0.3); if cover > 0.0 { [0.0, 0.15 * live * cover, 0.2 * live] } else { [0.0; 3] } }
        kind::CHIMNEY => { let cover = layer(0.0, 0.3) * disc(0.26); if cover > 0.0 { [0.0, 5.0 * live * cover, 0.35] } else { [0.0; 3] } }
        _ => [0.0; 3],
    }
}

/// A field moved by whole cells, the box having moved the other way: what was at `x + d` is now at `x`.
fn moved<T: Copy>(f: &mut Vec<T>, nx: usize, ny: usize, nz: usize, dx: i32, dy: i32, dz: i32, empty: T) {
    let mut out = vec![empty; f.len()];
    for y in 0..ny as i32 {
        let sy = y + dy;
        if sy < 0 || sy >= ny as i32 { continue; }
        for z in 0..nz as i32 {
            let sz = z + dz;
            if sz < 0 || sz >= nz as i32 { continue; }
            let (x0, x1) = ((-dx).max(0), (nx as i32 - dx).min(nx as i32));
            if x0 >= x1 { continue; }
            let to = nx * (z as usize + nz * y as usize);
            let from = (nx * (sz as usize + nz * sy as usize)) as i32;
            out[to + x0 as usize..to + x1 as usize].copy_from_slice(&f[(from + x0 + dx) as usize..(from + x1 + dx) as usize]);
        }
    }
    *f = out;
}
