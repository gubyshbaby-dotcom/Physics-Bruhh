//! Surface extraction: particles -> quads.
//!
//! The implicit surface follows Zhu & Bridson: phi(x) = |x - xbar(x)| - r, where xbar is the
//! kernel-weighted mean of nearby particle positions. Unlike summed metaballs it does not bloat
//! where goo is thick, and a lone droplet still comes out as a small sphere.
//! Polygonisation is "surface nets": one vertex per grid cell that the surface crosses, one quad
//! per grid edge that changes sign. It needs no lookup tables and yields quads directly.

use crate::sim::World;
use rayon::prelude::*;

/// 4 vertices * (position xyz + normal xyz). The normal's length is 1 + the depth of goo behind
/// the vertex in blocks, so the renderer can make thick goo darker than a thin film.
pub const FLOATS_PER_QUAD: usize = 24;
const DEPTH_STEPS: usize = 16;
// Stretched goo is filled in along its springs, so it stays one smooth surface instead of coming
// apart into slices (jelly) or hanging in the air as loose blobs (honey pulled into strands).
// Jelly: nearest-neighbour springs (rest length up to BRIDGE_REST spacings) stretched to between
// BRIDGE_FROM and BRIDGE_UPTO spacings get samples of weight BRIDGE_WEIGHT.
const BRIDGE_REST: f32 = 1.2;
const BRIDGE_FROM: f32 = 1.6;
const BRIDGE_UPTO: f32 = 6.5;
const BRIDGE_WEIGHT: f32 = 0.7;
// Honey: every second taut spring longer than STRAND_FROM spacings (and no longer than STRAND_UPTO
// blocks) becomes a strand, thinner the further it is pulled, drooping a little in the middle.
const STRAND_FROM: f32 = 1.9;
const STRAND_UPTO: f32 = 4.0;
const STRAND_DROOP: f32 = 0.2;
/// Extra samples one puddle may get; beyond that the longest strands simply are not drawn.
const BRIDGE_BUDGET: usize = 12_000;

struct Grid { org: [f32; 3] }

/// Floats per puddle in the list [`build_parts`] fills: first quad, number of quads, then the box all of it lies in (least x, y, z, greatest x, y, z).
pub const PART_FLOATS: usize = 8;

/// Appends quads for every live body of `mat` to `out`. Returns the number of quads added.
pub fn build(w: &World, mat: u8, out: &mut Vec<f32>) -> usize {
    build_parts(w, mat, out, &mut Vec::new())
}

/// [`build`], which also says where in `out` each puddle is and what box it lies in ([`PART_FLOATS`]
/// numbers a puddle, the quads counted from the first one this call added): whoever draws the
/// quads can leave out the puddles that are not in view.
pub fn build_parts(w: &World, mat: u8, out: &mut Vec<f32>, parts: &mut Vec<f32>) -> usize {
    parts.clear();
    crate::sim::on_pool(|| build_on_pool(w, mat, out, parts))
}

/// Meshes kept from the last call: a puddle nobody touched since then is not meshed again.
#[derive(Default)]
pub struct Cache { entries: Vec<Entry> }
struct Entry { mat: u8, key: Vec<(u32, u32)>, quads: Vec<f32> }

fn build_on_pool(w: &World, mat: u8, out: &mut Vec<f32>, parts: &mut Vec<f32>) -> usize {
    let start = out.len();
    let slots = w.slots;
    // group bodies whose bounds touch, so merged puddles become one seamless surface
    let mut ids: Vec<usize> = (0..slots).filter(|&b| w.bodies[b].state != 0 && w.bodies[b].mat == mat && w.bodies[b].count > 0).collect();
    let mut group: Vec<usize> = (0..ids.len()).collect();
    fn find(g: &mut Vec<usize>, mut a: usize) -> usize { while g[a] != a { g[a] = g[g[a]]; a = g[a]; } a }
    let pad = 3.0 * w.s;
    // (from west to east: a body is only tried against those that begin before it ends, so a thousand
    // puddles far apart are not each tried against all the others)
    ids.sort_by(|&a, &b| w.bodies[a].bb[0].total_cmp(&w.bodies[b].bb[0]).then(a.cmp(&b)));
    for a in 0..ids.len() {
        let x = &w.bodies[ids[a]].bb;
        for b in a + 1..ids.len() {
            let y = &w.bodies[ids[b]].bb;
            if y[0] >= x[3] + pad { break; }
            if (0..3).all(|k| x[k] < y[k + 3] + pad && x[k + 3] > y[k] - pad) { let (ra, rb) = (find(&mut group, a), find(&mut group, b)); group[ra] = rb; }
        }
    }
    // the puddles, each with its bodies in the order of their slots, and the puddles in the order of their first body
    let mut of_root: Vec<u32> = vec![u32::MAX; ids.len()];
    let mut clusters: Vec<Vec<usize>> = Vec::new();
    let mut order: Vec<usize> = (0..ids.len()).collect();
    order.sort_by_key(|&a| ids[a]);
    for a in order {
        let root = find(&mut group, a);
        if of_root[root] == u32::MAX { of_root[root] = clusters.len() as u32; clusters.push(Vec::new()); }
        clusters[of_root[root] as usize].push(ids[a]);
    }
    // a puddle is described by its bodies and how often each has changed; unchanged ones come from the cache
    let keys: Vec<Vec<(u32, u32)>> = clusters.iter().map(|c| c.iter().map(|&b| (b as u32, w.bodies[b].rev)).collect()).collect();
    let mut cache = w.mesh_cache.lock().unwrap_or_else(|e| e.into_inner());
    let kept: std::collections::HashMap<(u32, u32), usize> = cache.entries.iter().enumerate().filter(|(_, e)| e.mat == mat && !e.key.is_empty()).map(|(i, e)| (e.key[0], i)).collect();
    let mut found: Vec<Option<Vec<f32>>> = keys.iter().map(|k| kept.get(&k[0]).filter(|&&i| &cache.entries[i].key == k).map(|&i| std::mem::take(&mut cache.entries[i].quads))).collect();
    // the others are meshed side by side
    let todo: Vec<usize> = (0..clusters.len()).filter(|&c| found[c].is_none()).collect();
    let fresh: Vec<Vec<f32>> = todo.par_iter().map(|&c| { let mut part = Vec::new(); mesh_cluster(w, &clusters[c], &mut part); part }).collect();
    for (c, part) in todo.into_iter().zip(fresh) { found[c] = Some(part); }
    cache.entries.retain(|e| e.mat != mat);
    for ((key, part), bodies) in keys.into_iter().zip(found).zip(&clusters) {
        let quads = part.unwrap_or_default();
        if !quads.is_empty() {
            // where the puddle is: its bodies' bounds, and room for the surface around the outermost particles and for strands to droop
            let (mut lo, mut hi) = ([f32::MAX; 3], [f32::MIN; 3]);
            for &b in bodies { let bb = &w.bodies[b].bb; for k in 0..3 { lo[k] = lo[k].min(bb[k]); hi[k] = hi[k].max(bb[k + 3]); } }
            parts.extend_from_slice(&[((out.len() - start) / FLOATS_PER_QUAD) as f32, (quads.len() / FLOATS_PER_QUAD) as f32,
                lo[0] - pad, lo[1] - pad - STRAND_DROOP, lo[2] - pad, hi[0] + pad, hi[1] + pad, hi[2] + pad]);
        }
        out.extend_from_slice(&quads);
        cache.entries.push(Entry { mat, key, quads });
    }
    (out.len() - start) / FLOATS_PER_QUAD
}

fn mesh_cluster(w: &World, bodies: &[usize], out: &mut Vec<f32>) {
    let s = w.s;
    let rk = 2.2 * s;            // kernel radius
    let rad = 0.92 * s;          // surface offset from the weighted centre
    let (mut lo, mut hi) = ([f32::MAX; 3], [f32::MIN; 3]);
    for &b in bodies { let bb = &w.bodies[b].bb; for k in 0..3 { lo[k] = lo[k].min(bb[k]); hi[k] = hi[k].max(bb[k + 3]); } }
    if lo[0] > hi[0] { return; }
    if bodies.iter().any(|&b| w.mats[w.bodies[b].mat as usize].dynamic_springs) { lo[1] -= STRAND_DROOP; } // room for strands to droop
    let mut c = s;
    let mut dim = [0usize; 3];
    loop { // coarsen until the grid is a sane size
        let mut cells = 1usize;
        for k in 0..3 { dim[k] = ((hi[k] - lo[k] + 2.0 * (rk + c)) / c).ceil() as usize + 1; cells = cells.saturating_mul(dim[k]); }
        if cells <= 3_000_000 { break; }
        c *= 1.26;
    }
    let g = Grid { org: [lo[0] - rk - c, lo[1] - rk - c, lo[2] - rk - c] };
    let (nx, ny, nz) = (dim[0], dim[1], dim[2]);
    let nn = nx * ny * nz;
    let mut acc = vec![0.0f32; nn * 4]; // sum w, sum w*x, sum w*y, sum w*z
    let ic = 1.0 / c; let irk2 = 1.0 / (rk * rk);
    let mut splat = |p: [f32; 3], sc: f32| {
        let mut r0 = [0usize; 3]; let mut r1 = [0usize; 3];
        for k in 0..3 {
            let a = ((p[k] - rk - g.org[k]) * ic).ceil().max(0.0) as usize;
            let e = ((p[k] + rk - g.org[k]) * ic).floor();
            if e < 0.0 { return; }
            r0[k] = a; r1[k] = (e as usize).min(dim[k] - 1);
            if r0[k] > r1[k] { return; }
        }
        for z in r0[2]..=r1[2] { let dz = g.org[2] + z as f32 * c - p[2];
            for y in r0[1]..=r1[1] { let dy = g.org[1] + y as f32 * c - p[1];
                for x in r0[0]..=r1[0] { let dx = g.org[0] + x as f32 * c - p[0];
                    let t = 1.0 - (dx * dx + dy * dy + dz * dz) * irk2;
                    if t <= 0.0 { continue; }
                    let wgt = t * t * t * sc; let o = (x + nx * (y + ny * z)) * 4;
                    acc[o] += wgt; acc[o + 1] += wgt * p[0]; acc[o + 2] += wgt * p[1]; acc[o + 3] += wgt * p[2];
                }
            }
        }
    };
    let mut budget = BRIDGE_BUDGET;
    for &b in bodies {
        let m = &w.mats[w.bodies[b].mat as usize];
        let jelly = !m.dynamic_springs;
        for i in b * w.nb..(b + 1) * w.nb {
            if w.alive[i] == 0 { continue; }
            let sc = w.scale[i]; if sc <= 0.01 { continue; }
            let p = [w.p[i * 3], w.p[i * 3 + 1], w.p[i * 3 + 2]];
            splat(p, sc);
            for k in 0..w.spr_n[i] as usize {
                let si = i * crate::sim::MAX_SPR + k;
                let rest = w.spr_l[si];
                if jelly && rest > BRIDGE_REST * s { continue; } // nearest neighbours only: one bridge per gap is enough
                let j = w.spr_j[si] as usize;
                if w.alive[j] == 0 { continue; }
                let d = [w.p[j * 3] - p[0], w.p[j * 3 + 1] - p[1], w.p[j * 3 + 2] - p[2]];
                let len = (d[0] * d[0] + d[1] * d[1] + d[2] * d[2]).sqrt();
                let (n, wsc, droop);
                if jelly {
                    if len < BRIDGE_FROM * s || len > BRIDGE_UPTO * s { continue; }
                    n = ((len / (1.1 * s)) as usize).min(5); wsc = BRIDGE_WEIGHT; droop = 0.0;
                } else {
                    // only springs that are both long and taut: relaxed long springs sit inside the honey anyway
                    if len < STRAND_FROM * s || len > STRAND_UPTO || (len < 2.9 * s && len < 1.3 * rest) { continue; }
                    if (i.wrapping_mul(31) ^ j.wrapping_mul(17)) % 2 != 0 { continue; }
                    n = ((len / (0.9 * s)) as usize).min(40);
                    wsc = (1.5 * s / len).clamp(0.2, 0.6);
                    droop = (m.thread_sag * len).min(STRAND_DROOP);
                }
                if n > budget { continue; }
                budget -= n;
                let wsc = wsc * sc.min(w.scale[j]);
                for step in 1..=n {
                    let t = step as f32 / (n + 1) as f32;
                    splat([p[0] + d[0] * t, p[1] + d[1] * t - droop * 4.0 * t * (1.0 - t), p[2] + d[2] * t], wsc);
                }
            }
        }
    }
    // signed distance-like field at grid nodes (negative inside)
    let mut phi = vec![rk; nn];
    let org = g.org;
    phi.par_chunks_mut(nx * ny).enumerate().for_each(|(z, slice)| { for y in 0..ny { for x in 0..nx {
        let n = x + nx * (y + ny * z); let wsum = acc[n * 4];
        if wsum > 1e-5 {
            let inv = 1.0 / wsum;
            let (dx, dy, dz) = (org[0] + x as f32 * c - acc[n * 4 + 1] * inv, org[1] + y as f32 * c - acc[n * 4 + 2] * inv, org[2] + z as f32 * c - acc[n * 4 + 3] * inv);
            // thin spots (few particles nearby) shrink a little so stray droplets stay small
            let r = rad * (0.55 + 0.45 * (wsum * 0.9).min(1.0));
            slice[x + nx * y] = (dx * dx + dy * dy + dz * dz).sqrt() - r;
        }
    } } });
    drop(acc);
    // one vertex per crossed cell
    let (cx, cy, cz) = (nx - 1, ny - 1, nz - 1);
    let mut vid = vec![u32::MAX; cx * cy * cz];
    let mut verts: Vec<f32> = Vec::new(); // xyz + normal
    const CO: [[usize; 3]; 8] = [[0, 0, 0], [1, 0, 0], [0, 1, 0], [1, 1, 0], [0, 0, 1], [1, 0, 1], [0, 1, 1], [1, 1, 1]];
    const ED: [[usize; 2]; 12] = [[0, 1], [2, 3], [4, 5], [6, 7], [0, 2], [1, 3], [4, 6], [5, 7], [0, 4], [1, 5], [2, 6], [3, 7]];
    for z in 0..cz { for y in 0..cy { for x in 0..cx {
        let mut f = [0.0f32; 8]; let mut m = 0u32;
        for k in 0..8 { f[k] = phi[(x + CO[k][0]) + nx * ((y + CO[k][1]) + ny * (z + CO[k][2]))]; if f[k] < 0.0 { m |= 1 << k; } }
        if m == 0 || m == 255 { continue; }
        let (mut px, mut py, mut pz, mut cnt) = (0.0f32, 0.0f32, 0.0f32, 0.0f32);
        for e in ED.iter() {
            let (a, b) = (e[0], e[1]);
            if (f[a] < 0.0) == (f[b] < 0.0) { continue; }
            let t = f[a] / (f[a] - f[b]);
            px += CO[a][0] as f32 + t * (CO[b][0] as f32 - CO[a][0] as f32);
            py += CO[a][1] as f32 + t * (CO[b][1] as f32 - CO[a][1] as f32);
            pz += CO[a][2] as f32 + t * (CO[b][2] as f32 - CO[a][2] as f32);
            cnt += 1.0;
        }
        let inv = 1.0 / cnt;
        let gx = (f[1] + f[3] + f[5] + f[7]) - (f[0] + f[2] + f[4] + f[6]);
        let gy = (f[2] + f[3] + f[6] + f[7]) - (f[0] + f[1] + f[4] + f[5]);
        let gz = (f[4] + f[5] + f[6] + f[7]) - (f[0] + f[1] + f[2] + f[3]);
        let gl = (gx * gx + gy * gy + gz * gz).sqrt().max(1e-6);
        vid[x + cx * (y + cy * z)] = (verts.len() / 6) as u32;
        let (ux, uy, uz) = (gx / gl, gy / gl, gz / gl);
        let (vx, vy, vz) = (x as f32 + px * inv, y as f32 + py * inv, z as f32 + pz * inv);
        // how much goo lies behind this point, measured straight inwards: the renderer tints by it
        let mut depth = 0.0f32;
        for st in 1..=DEPTH_STEPS {
            let (sx, sy, sz) = ((vx - ux * st as f32 + 0.5).floor(), (vy - uy * st as f32 + 0.5).floor(), (vz - uz * st as f32 + 0.5).floor());
            if sx < 0.0 || sy < 0.0 || sz < 0.0 || sx >= nx as f32 || sy >= ny as f32 || sz >= nz as f32 { break; }
            if phi[sx as usize + nx * (sy as usize + ny * sz as usize)] >= 0.0 { break; }
            depth = st as f32 * c;
        }
        let nl = 1.0 + depth;
        verts.extend_from_slice(&[g.org[0] + vx * c, g.org[1] + vy * c, g.org[2] + vz * c, ux * nl, uy * nl, uz * nl]);
    } } }
    // one quad per sign-changing grid edge
    let quad = |a: u32, b: u32, c2: u32, d: u32, flip: bool, out: &mut Vec<f32>| {
        if a == u32::MAX || b == u32::MAX || c2 == u32::MAX || d == u32::MAX { return; }
        let order = if flip { [d, c2, b, a] } else { [a, b, c2, d] };
        for v in order { let o = v as usize * 6; out.extend_from_slice(&verts[o..o + 6]); }
    };
    let cell = |x: usize, y: usize, z: usize| vid[x + cx * (y + cy * z)];
    for z in 0..nz { for y in 0..ny { for x in 0..nx {
        let f0 = phi[x + nx * (y + ny * z)];
        let in0 = f0 < 0.0;
        if x + 1 < nx && y >= 1 && z >= 1 && y < ny - 1 && z < nz - 1 {
            if in0 != (phi[x + 1 + nx * (y + ny * z)] < 0.0) { quad(cell(x, y - 1, z - 1), cell(x, y, z - 1), cell(x, y, z), cell(x, y - 1, z), !in0, out); }
        }
        if y + 1 < ny && x >= 1 && z >= 1 && x < nx - 1 && z < nz - 1 {
            if in0 != (phi[x + nx * (y + 1 + ny * z)] < 0.0) { quad(cell(x - 1, y, z - 1), cell(x - 1, y, z), cell(x, y, z), cell(x, y, z - 1), !in0, out); }
        }
        if z + 1 < nz && x >= 1 && y >= 1 && x < nx - 1 && y < ny - 1 {
            if in0 != (phi[x + nx * (y + ny * (z + 1))] < 0.0) { quad(cell(x - 1, y - 1, z), cell(x, y - 1, z), cell(x, y, z), cell(x - 1, y, z), !in0, out); }
        }
    } } }
}
