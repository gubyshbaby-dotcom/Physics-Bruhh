//! Surface extraction: particles -> quads.
//!
//! The implicit surface follows Zhu & Bridson: phi(x) = |x - xbar(x)| - r, where xbar is the
//! kernel-weighted mean of nearby particle positions. Unlike summed metaballs it does not bloat
//! where goo is thick, and a lone droplet still comes out as a small sphere.
//! Polygonisation is "surface nets": one vertex per grid cell that the surface crosses, one quad
//! per grid edge that changes sign. It needs no lookup tables and yields quads directly.

use crate::sim::World;

pub const FLOATS_PER_QUAD: usize = 24; // 4 vertices * (position xyz + normal xyz)

struct Grid { org: [f32; 3], dim: [usize; 3], c: f32 }

/// Appends quads for every live body of `mat` to `out`. Returns the number of quads added.
pub fn build(w: &World, mat: u8, out: &mut Vec<f32>) -> usize {
    let start = out.len();
    let slots = w.slots;
    // group bodies whose bounds touch, so merged puddles become one seamless surface
    let ids: Vec<usize> = (0..slots).filter(|&b| w.bodies[b].state != 0 && w.bodies[b].mat == mat && w.bodies[b].count > 0).collect();
    let mut group: Vec<usize> = (0..ids.len()).collect();
    fn find(g: &mut Vec<usize>, mut a: usize) -> usize { while g[a] != a { g[a] = g[g[a]]; a = g[a]; } a }
    let pad = 3.0 * w.s;
    for a in 0..ids.len() { for b in a + 1..ids.len() {
        let (x, y) = (&w.bodies[ids[a]].bb, &w.bodies[ids[b]].bb);
        if (0..3).all(|k| x[k] < y[k + 3] + pad && x[k + 3] > y[k] - pad) { let (ra, rb) = (find(&mut group, a), find(&mut group, b)); group[ra] = rb; }
    } }
    let mut done = vec![false; ids.len()];
    for a in 0..ids.len() {
        let root = find(&mut group, a);
        if done[root] { continue; }
        done[root] = true;
        let members: Vec<usize> = (0..ids.len()).filter(|&b| find(&mut group, b) == root).map(|b| ids[b]).collect();
        mesh_cluster(w, &members, out);
    }
    threads(w, mat, out);
    (out.len() - start) / FLOATS_PER_QUAD
}

/// Springs stretched far beyond the particle spacing are drawn as thin drooping threads: at piston
/// speed goo tears faster than particles can flow into the gap, and the threads are what is left
/// hanging between the two sides until they snap.
fn threads(w: &World, mat: u8, out: &mut Vec<f32>) {
    let s = w.s;
    let min_len = 1.9 * s;
    let sag = w.mats[mat as usize].thread_sag;
    let mut budget = 6000usize; // quads
    for b in 0..w.slots {
        if w.bodies[b].state == 0 || w.bodies[b].mat != mat { continue; }
        for i in b * w.nb..(b + 1) * w.nb {
            if w.alive[i] == 0 { continue; }
            for k in 0..w.spr_n[i] as usize {
                let j = w.spr_j[i * crate::sim::MAX_SPR + k] as usize;
                if w.alive[j] == 0 { continue; }
                let a = [w.p[i * 3], w.p[i * 3 + 1], w.p[i * 3 + 2]];
                let c = [w.p[j * 3], w.p[j * 3 + 1], w.p[j * 3 + 2]];
                let d = [c[0] - a[0], c[1] - a[1], c[2] - a[2]];
                let len = (d[0] * d[0] + d[1] * d[1] + d[2] * d[2]).sqrt();
                // only springs that are both long and taut: relaxed long springs sit inside the goo
                if len < min_len || len > 4.0 || (len < 2.9 * s && len < 1.3 * w.spr_l[i * crate::sim::MAX_SPR + k]) { continue; }
                if (i.wrapping_mul(31) ^ j.wrapping_mul(17)) % 3 != 0 { continue; } // a third of them is plenty
                if budget < 4 { return; }
                budget -= 4;
                let half = (0.42 * s * (min_len / len).sqrt()).clamp(0.006, 0.03) * w.scale[i].min(w.scale[j]);
                let mid = [a[0] + d[0] * 0.5, a[1] + d[1] * 0.5 - sag * len, a[2] + d[2] * 0.5];
                for seg in 0..2 {
                    let (p0, p1) = if seg == 0 { (a, mid) } else { (mid, c) };
                    let t = [p1[0] - p0[0], p1[1] - p0[1], p1[2] - p0[2]];
                    // two ribbons at right angles so the thread is visible from any side
                    let up = if t[1].abs() > 0.9 * (t[0] * t[0] + t[1] * t[1] + t[2] * t[2]).sqrt() { [1.0, 0.0, 0.0] } else { [0.0, 1.0, 0.0] };
                    let mut u = [t[1] * up[2] - t[2] * up[1], t[2] * up[0] - t[0] * up[2], t[0] * up[1] - t[1] * up[0]];
                    let ul = (u[0] * u[0] + u[1] * u[1] + u[2] * u[2]).sqrt().max(1e-6); for x in u.iter_mut() { *x /= ul; }
                    let mut v = [t[1] * u[2] - t[2] * u[1], t[2] * u[0] - t[0] * u[2], t[0] * u[1] - t[1] * u[0]];
                    let vl = (v[0] * v[0] + v[1] * v[1] + v[2] * v[2]).sqrt().max(1e-6); for x in v.iter_mut() { *x /= vl; }
                    for (side, nrm) in [(u, v), (v, u)] {
                        let corners = [
                            [p0[0] - side[0] * half, p0[1] - side[1] * half, p0[2] - side[2] * half],
                            [p0[0] + side[0] * half, p0[1] + side[1] * half, p0[2] + side[2] * half],
                            [p1[0] + side[0] * half, p1[1] + side[1] * half, p1[2] + side[2] * half],
                            [p1[0] - side[0] * half, p1[1] - side[1] * half, p1[2] - side[2] * half],
                        ];
                        for cn in corners { out.extend_from_slice(&[cn[0], cn[1], cn[2], nrm[0], nrm[1], nrm[2]]); }
                    }
                }
            }
        }
    }
}

fn mesh_cluster(w: &World, bodies: &[usize], out: &mut Vec<f32>) {
    let s = w.s;
    let rk = 2.2 * s;            // kernel radius
    let rad = 0.92 * s;          // surface offset from the weighted centre
    let (mut lo, mut hi) = ([f32::MAX; 3], [f32::MIN; 3]);
    for &b in bodies { let bb = &w.bodies[b].bb; for k in 0..3 { lo[k] = lo[k].min(bb[k]); hi[k] = hi[k].max(bb[k + 3]); } }
    if lo[0] > hi[0] { return; }
    let mut c = s;
    let mut dim = [0usize; 3];
    loop { // coarsen until the grid is a sane size
        let mut cells = 1usize;
        for k in 0..3 { dim[k] = ((hi[k] - lo[k] + 2.0 * (rk + c)) / c).ceil() as usize + 1; cells = cells.saturating_mul(dim[k]); }
        if cells <= 3_000_000 { break; }
        c *= 1.26;
    }
    let g = Grid { org: [lo[0] - rk - c, lo[1] - rk - c, lo[2] - rk - c], dim, c };
    let (nx, ny, nz) = (dim[0], dim[1], dim[2]);
    let nn = nx * ny * nz;
    let mut acc = vec![0.0f32; nn * 4]; // sum w, sum w*x, sum w*y, sum w*z
    let ic = 1.0 / c; let irk2 = 1.0 / (rk * rk);
    for &b in bodies {
        for i in b * w.nb..(b + 1) * w.nb {
            if w.alive[i] == 0 { continue; }
            let sc = w.scale[i]; if sc <= 0.01 { continue; }
            let p = [w.p[i * 3], w.p[i * 3 + 1], w.p[i * 3 + 2]];
            let mut r0 = [0usize; 3]; let mut r1 = [0usize; 3]; let mut skip = false;
            for k in 0..3 {
                let a = ((p[k] - rk - g.org[k]) * ic).ceil().max(0.0) as usize;
                let e = ((p[k] + rk - g.org[k]) * ic).floor();
                if e < 0.0 { skip = true; break; }
                r0[k] = a; r1[k] = (e as usize).min(dim[k] - 1);
                if r0[k] > r1[k] { skip = true; break; }
            }
            if skip { continue; }
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
        }
    }
    // signed distance-like field at grid nodes (negative inside)
    let mut phi = vec![rk; nn];
    for z in 0..nz { for y in 0..ny { for x in 0..nx {
        let n = x + nx * (y + ny * z); let wsum = acc[n * 4];
        if wsum > 1e-5 {
            let inv = 1.0 / wsum;
            let (dx, dy, dz) = (g.org[0] + x as f32 * c - acc[n * 4 + 1] * inv, g.org[1] + y as f32 * c - acc[n * 4 + 2] * inv, g.org[2] + z as f32 * c - acc[n * 4 + 3] * inv);
            // thin spots (few particles nearby) shrink a little so stray droplets stay small
            let r = rad * (0.55 + 0.45 * (wsum * 0.9).min(1.0));
            phi[n] = (dx * dx + dy * dy + dz * dz).sqrt() - r;
        }
    } } }
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
        verts.extend_from_slice(&[g.org[0] + (x as f32 + px * inv) * c, g.org[1] + (y as f32 + py * inv) * c, g.org[2] + (z as f32 + pz * inv) * c, gx / gl, gy / gl, gz / gl]);
    } } }
    // one quad per sign-changing grid edge
    let mut quad = |a: u32, b: u32, c2: u32, d: u32, flip: bool, out: &mut Vec<f32>| {
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
