//! Goo simulation core: position-based fluid + viscoelastic spring network + surface adhesion.
//!
//! Units are blocks and seconds. All coordinates are local to an origin the caller picks, so
//! single precision stays accurate far from the world centre.
//!
//! Every body is `n^3` particles. Two materials share one world:
//!  * honey — starts as a soft jelly cube, its springs turn plastic as it "melts", ends as a puddle;
//!  * slime — keeps its spring lattice forever, so it stays an elastic blob.
//! Colliders are axis-aligned boxes: static ones (blocks) and kinematic ones (moving piston parts).
//! Particles that touch a collider get anchored to it; anchors creep (honey slides slowly) and
//! snap when overstretched, which is what makes goo cling to pistons and pull into strands.

pub const MAX_SPR: usize = 16;
pub const HONEY: u8 = 0;
pub const SLIME: u8 = 1;
pub const DT: f32 = 1.0 / 60.0;

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
}

impl Material {
    pub fn honey() -> Material {
        Material {
            melt_time: 3.5, damp_fresh: 1.2, damp_melted: 11.0,
            spring_k0: 0.5, spring_k1: 0.03, plast0: 0.7, plast1: 20.0, yield_: 0.03,
            dynamic_springs: true, break_strain: 4.0, spring_max_l: 0.21, shock_k: 0.42, shock_plast: 0.6, shock_decay: 1.1, shock_break: 16.0, shock_max_l: 1.7, thread_sag: 0.16,
            coh_k: 0.25, coh_lim: 0.35, xsph: 0.35,
            adh_k: 0.9, adh_creep: 9.0, adh_break: 0.30,
            set_after: 11.0, set_rate: 0.3, tip: 2.05,
        }
    }
    pub fn slime() -> Material {
        Material {
            melt_time: 0.0, damp_fresh: 2.2, damp_melted: 2.2,
            spring_k0: 0.45, spring_k1: 0.45, plast0: 0.0, plast1: 0.0, yield_: 0.0,
            dynamic_springs: false, break_strain: 8.0, spring_max_l: 9.0, shock_k: 0.45, shock_plast: 0.0, shock_decay: 0.3, shock_break: 12.0, shock_max_l: 9.0, thread_sag: 0.03,
            coh_k: 0.15, coh_lim: 0.3, xsph: 0.25,
            adh_k: 0.9, adh_creep: 0.0, adh_break: 0.16,
            set_after: 0.0, set_rate: 0.0, tip: 0.0,
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
    pub bb: [f32; 6],
}

#[derive(Clone, Copy, Debug)]
pub struct SBox { pub min: [f32; 3], pub max: [f32; 3], pub stick: f32 }

#[derive(Clone, Copy, Debug)]
pub struct KBox { pub id: i32, pub min: [f32; 3], pub max: [f32; 3], pub pmin: [f32; 3], pub pmax: [f32; 3], pub vel: [f32; 3], pub stick: f32, pub t: f32 }

pub struct World {
    pub n: usize, pub nb: usize, pub slots: usize, pub maxp: usize,
    pub s: f32, pub h: f32, pub side: f32, pub rc: f32,
    pub mats: [Material; 2],
    pub gravity: f32, pub iters: usize, pub visc_iters: usize, pub eps: f32, pub k_corr: f32,
    pub kill_y: f32,
    pub p: Vec<f32>, q: Vec<f32>, v: Vec<f32>, pub alive: Vec<u8>, pub scale: Vec<f32>,
    anc_kind: Vec<u8>, anc_id: Vec<i32>, anc_pt: Vec<f32>, anc_loc: Vec<f32>, anc_stick: Vec<f32>, anc_cool: Vec<f32>, anc_d: Vec<f32>, anc_axis: Vec<u8>,
    pub(crate) spr_n: Vec<u8>, pub(crate) spr_j: Vec<u32>, pub(crate) spr_l: Vec<f32>,
    rho: Vec<f32>, lam: Vec<f32>, sgx: Vec<f32>, sgy: Vec<f32>, sgz: Vec<f32>, sg2: Vec<f32>,
    dpx: Vec<f32>, dpy: Vec<f32>, dpz: Vec<f32>, damp: Vec<f32>,
    act: Vec<u32>,
    mask: u32, c_start: Vec<u32>, c_fill: Vec<u32>, sorted: Vec<u32>, pkey: Vec<u32>, pbkt: Vec<u32>, cell: Vec<i32>,
    pi: Vec<u32>, pj: Vec<u32>, pg: Vec<f32>, pw: Vec<f32>,
    kp: f32, ks: f32, w0: f32, rho0: f32, wq: f32,
    pub bodies: Vec<Body>,
    st: Vec<SBox>, st_org: [i32; 3], st_dim: [i32; 3], st_start: Vec<u32>, st_items: Vec<u32>,
    pub kin: Vec<KBox>,
    pub clock: f32, acc: f32, seed: u32,
    pub last_pairs: usize,
}

#[inline] fn smooth(t: f32) -> f32 { let t = t.clamp(0.0, 1.0); t * t * (3.0 - 2.0 * t) }
#[inline] fn hash3(x: i32, y: i32, z: i32, mask: u32) -> u32 {
    ((x.wrapping_mul(73856093)) ^ (y.wrapping_mul(19349663)) ^ (z.wrapping_mul(83492791))) as u32 & mask
}
#[inline] fn key3(x: i32, y: i32, z: i32) -> u32 { ((x & 1023) | ((y & 1023) << 10) | ((z & 1023) << 20)) as u32 }

const HX: [i32; 14] = [0, 1, -1, 0, 1, -1, 0, 1, -1, 0, 1, -1, 0, 1];
const HY: [i32; 14] = [0, 0, 1, 1, 1, -1, -1, -1, 0, 0, 0, 1, 1, 1];
const HZ: [i32; 14] = [0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 1, 1, 1, 1];

/// Push `q` out of the box if it is inside (box grown by `rc`). The exit face is the one the
/// particle came from last step when that is known, so fast pistons shove goo ahead of them
/// instead of letting it tunnel out the back.
#[inline]
fn resolve_box(q: &mut [f32; 3], p: &[f32; 3], bmin: &[f32; 3], bmax: &[f32; 3], pmin: &[f32; 3], pmax: &[f32; 3], rc: f32) -> u8 {
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
        if p[a] <= pmin[a] - rc + 1e-4 && q[a] > bmin[a] - rc { let pen = q[a] - (bmin[a] - rc); if pen < best { best = pen; ba = a; hi = false; } }
    }
    if ba < 3 { q[ba] = if hi { bmax[ba] + rc } else { bmin[ba] - rc }; return ba as u8; }
    // started inside (spawned there, or squeezed in): leave through the nearest face
    for a in 0..3 { if q[a] <= bmin[a] - rc || q[a] >= bmax[a] + rc { return 3; } }
    let (mut best, mut ba, mut hi) = (f32::MAX, 0usize, false);
    for a in 0..3 {
        let pen_lo = q[a] - (bmin[a] - rc); if pen_lo < best { best = pen_lo; ba = a; hi = false; }
        let pen_hi = (bmax[a] + rc) - q[a]; if pen_hi < best { best = pen_hi; ba = a; hi = true; }
    }
    q[ba] = if hi { bmax[ba] + rc } else { bmin[ba] - rc };
    ba as u8
}

impl World {
    pub fn new(n: usize, max_bodies: usize) -> World {
        let n = n.clamp(6, 16);
        let side = 0.97f32;
        let s = side / n as f32;
        let nb = n * n * n;
        let slots = max_bodies.max(1);
        let maxp = nb * slots;
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
        let mut table = 1usize; while table < maxp * 2 { table <<= 1; }
        World {
            n, nb, slots, maxp, s, h, side, rc: s * 0.5,
            mats: [Material::honey(), Material::slime()],
            gravity: 9.8, iters: 3, visc_iters: 1, eps: 60.0, k_corr: 2.0e-4, kill_y: -64.0,
            p: vec![0.0; maxp * 3], q: vec![0.0; maxp * 3], v: vec![0.0; maxp * 3], alive: vec![0; maxp], scale: vec![0.0; maxp],
            anc_kind: vec![0; maxp], anc_id: vec![0; maxp], anc_pt: vec![0.0; maxp * 3], anc_loc: vec![0.0; maxp * 3], anc_stick: vec![0.0; maxp], anc_cool: vec![0.0; maxp], anc_d: vec![0.0; maxp * 3], anc_axis: vec![3; maxp],
            spr_n: vec![0; maxp], spr_j: vec![0; maxp * MAX_SPR], spr_l: vec![0.0; maxp * MAX_SPR],
            rho: vec![0.0; maxp], lam: vec![0.0; maxp], sgx: vec![0.0; maxp], sgy: vec![0.0; maxp], sgz: vec![0.0; maxp], sg2: vec![0.0; maxp],
            dpx: vec![0.0; maxp], dpy: vec![0.0; maxp], dpz: vec![0.0; maxp], damp: vec![0.0; maxp],
            act: Vec::with_capacity(maxp),
            mask: (table - 1) as u32, c_start: vec![0; table + 1], c_fill: vec![0; table], sorted: vec![0; maxp], pkey: vec![0; maxp], pbkt: vec![0; maxp], cell: vec![0; maxp * 3],
            pi: Vec::new(), pj: Vec::new(), pg: Vec::new(), pw: Vec::new(),
            kp, ks, w0, rho0, wq,
            bodies: vec![Body::default(); slots],
            st: Vec::new(), st_org: [0; 3], st_dim: [0; 3], st_start: vec![0], st_items: Vec::new(),
            kin: Vec::new(),
            clock: 0.0, acc: 0.0, seed: 0x9E3779B9, last_pairs: 0,
        }
    }

    fn rnd(&mut self) -> f32 { self.seed = self.seed.wrapping_mul(1664525).wrapping_add(1013904223); ((self.seed >> 8) & 0xffff) as f32 / 65536.0 }

    fn free_body(&mut self, b: usize) {
        self.bodies[b] = Body::default();
        let (lo, hi) = (b * self.nb, (b + 1) * self.nb);
        for i in lo..hi { self.alive[i] = 0; self.scale[i] = 0.0; self.spr_n[i] = 0; self.anc_kind[i] = 0; }
        // drop springs other bodies still hold to these particles (the slot may be reused)
        for i in 0..self.maxp {
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

    /// Adds a body occupying the block cell whose minimum corner is (x, y, z).
    /// `age` > 6 s spawns honey already melted (used when an old puddle comes back into view).
    pub fn add_body(&mut self, mat: u8, x: f32, y: f32, z: f32, dirx: f32, dirz: f32, age: f32) -> i32 {
        let mat = if mat == SLIME { SLIME } else { HONEY };
        let mut slot = self.bodies.iter().position(|b| b.state == 0);
        if slot.is_none() { // evict the oldest sleeping body, else the oldest one
            let mut best: Option<usize> = None; let mut bt = f32::MAX;
            for (i, b) in self.bodies.iter().enumerate() { if !b.awake && b.born < bt { bt = b.born; best = Some(i); } }
            if best.is_none() { for (i, b) in self.bodies.iter().enumerate() { if b.born < bt { bt = b.born; best = Some(i); } } }
            if let Some(b) = best { self.free_body(b); slot = Some(b); }
        }
        let slot = match slot { Some(s) => s, None => return -1 };
        let m = self.mats[mat as usize];
        let (n, s, side, nb) = (self.n, self.s, self.side, self.nb);
        let base = slot * nb;
        let puddle = mat == HONEY && age > 6.0;
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
        } } }
        self.bodies[slot] = Body {
            state: 1, mat, awake: true, age: age.max(0.0), born: self.clock, fade: 1.0, fresh: !puddle, count: nb as u32,
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

    pub fn awake_count(&self) -> u32 { self.bodies.iter().filter(|b| b.state != 0 && b.awake).count() as u32 }

    /// Static colliders: `count` boxes of 7 floats (min xyz, max xyz, stick).
    pub fn set_static(&mut self, data: &[f32], count: usize) {
        self.st.clear();
        let count = count.min(data.len() / 7);
        let (mut lo, mut hi) = ([i32::MAX; 3], [i32::MIN; 3]);
        for k in 0..count {
            let d = &data[k * 7..k * 7 + 7];
            let b = SBox { min: [d[0], d[1], d[2]], max: [d[3], d[4], d[5]], stick: d[6] };
            for a in 0..3 { lo[a] = lo[a].min(b.min[a].floor() as i32 - 1); hi[a] = hi[a].max(b.max[a].ceil() as i32 + 1); }
            self.st.push(b);
        }
        if self.st.is_empty() { self.st_dim = [0; 3]; self.st_start.clear(); self.st_start.push(0); self.st_items.clear(); }
        else {
            self.st_org = lo;
            for a in 0..3 { self.st_dim[a] = (hi[a] - lo[a]).clamp(1, 96); }
            let cells = (self.st_dim[0] * self.st_dim[1] * self.st_dim[2]) as usize;
            let mut cnt = vec![0u32; cells + 1];
            for pass in 0..2 {
                if pass == 1 {
                    let mut run = 0u32; for c in 0..=cells { let t = cnt[c]; cnt[c] = run; run += t; }
                    self.st_start = cnt.clone(); self.st_items = vec![0; run as usize];
                }
                for (bi, b) in self.st.iter().enumerate() {
                    let mut r = [[0i32; 2]; 3];
                    for a in 0..3 {
                        r[a][0] = ((b.min[a] - 0.01).floor() as i32 - self.st_org[a]).clamp(0, self.st_dim[a] - 1);
                        r[a][1] = ((b.max[a] + 0.01).floor() as i32 - self.st_org[a]).clamp(0, self.st_dim[a] - 1);
                    }
                    for z in r[2][0]..=r[2][1] { for y in r[1][0]..=r[1][1] { for x in r[0][0]..=r[0][1] {
                        let c = (x + self.st_dim[0] * (y + self.st_dim[1] * z)) as usize;
                        if pass == 0 { cnt[c] += 1; } else { self.st_items[cnt[c] as usize] = bi as u32; cnt[c] += 1; }
                    } } }
                }
            }
        }
        // anchors whose surface is gone let go
        for i in 0..self.maxp {
            if self.alive[i] != 0 && self.anc_kind[i] == 1 {
                let o = i * 3; let pt = [self.anc_pt[o], self.anc_pt[o + 1], self.anc_pt[o + 2]];
                if !self.near_static(&pt, self.rc + 0.06) { self.anc_kind[i] = 0; }
            }
        }
    }

    fn near_static(&self, pt: &[f32; 3], tol: f32) -> bool {
        if self.st.is_empty() { return false; }
        let mut c = [0i32; 3];
        for a in 0..3 { c[a] = pt[a].floor() as i32 - self.st_org[a]; }
        for dz in -1..=1 { for dy in -1..=1 { for dx in -1..=1 {
            let (x, y, z) = (c[0] + dx, c[1] + dy, c[2] + dz);
            if x < 0 || y < 0 || z < 0 || x >= self.st_dim[0] || y >= self.st_dim[1] || z >= self.st_dim[2] { continue; }
            let ci = (x + self.st_dim[0] * (y + self.st_dim[1] * z)) as usize;
            for k in self.st_start[ci]..self.st_start[ci + 1] {
                let b = &self.st[self.st_items[k as usize] as usize];
                if (0..3).all(|a| pt[a] > b.min[a] - tol && pt[a] < b.max[a] + tol) { return true; }
            }
        } } }
        false
    }

    /// Kinematic colliders: `count` boxes of 11 floats (id, min xyz, max xyz, velocity xyz, stick).
    /// Boxes missing from a new list are dropped; goo anchored to them stays where it was.
    pub fn set_kinematic(&mut self, data: &[f32], count: usize) {
        let count = count.min(data.len() / 11);
        let mut next: Vec<KBox> = Vec::with_capacity(count);
        for k in 0..count {
            let d = &data[k * 11..k * 11 + 11];
            let id = d[0] as i32;
            let (min, max) = ([d[1], d[2], d[3]], [d[4], d[5], d[6]]);
            let prev = self.kin.iter().find(|b| b.id == id);
            let (pmin, pmax) = match prev { Some(b) => (b.min, b.max), None => (min, max) };
            next.push(KBox { id, min, max, pmin, pmax, vel: [d[7], d[8], d[9]], stick: d[10], t: 0.0 });
        }
        for i in 0..self.maxp {
            if self.alive[i] != 0 && self.anc_kind[i] == 2 && !next.iter().any(|b| b.id == self.anc_id[i]) {
                self.anc_kind[i] = 1; // becomes a plain world anchor at its last position
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

    fn collide(&mut self, i: usize) {
        let o = i * 3;
        let mut q = [self.q[o], self.q[o + 1], self.q[o + 2]];
        let p = [self.p[o], self.p[o + 1], self.p[o + 2]];
        let rc = self.rc;
        let mut hit: u8 = 0; let mut hit_id = 0i32; let mut hit_stick = 0.0f32; let mut hit_min = [0.0f32; 3]; let mut hit_axis = 3u8;
        if !self.st.is_empty() {
            let mut r = [[0i32; 2]; 3]; let mut inside = true;
            for a in 0..3 {
                r[a][0] = (q[a] - rc).floor() as i32 - self.st_org[a]; r[a][1] = (q[a] + rc).floor() as i32 - self.st_org[a];
                if r[a][1] < 0 || r[a][0] >= self.st_dim[a] { inside = false; }
                r[a][0] = r[a][0].max(0); r[a][1] = r[a][1].min(self.st_dim[a] - 1);
            }
            if inside {
                for z in r[2][0]..=r[2][1] { for y in r[1][0]..=r[1][1] { for x in r[0][0]..=r[0][1] {
                    let ci = (x + self.st_dim[0] * (y + self.st_dim[1] * z)) as usize;
                    for k in self.st_start[ci]..self.st_start[ci + 1] {
                        let b = self.st[self.st_items[k as usize] as usize];
                        let ax = resolve_box(&mut q, &p, &b.min, &b.max, &b.min, &b.max, rc);
                        if ax < 3 { hit = 1; hit_stick = b.stick; hit_axis = ax; }
                    }
                } } }
            }
        }
        for k in 0..self.kin.len() {
            let b = self.kin[k];
            let ax = resolve_box(&mut q, &p, &b.min, &b.max, &b.pmin, &b.pmax, rc);
            if ax < 3 { hit = 2; hit_id = b.id; hit_stick = b.stick; hit_min = b.min; hit_axis = ax; }
        }
        self.q[o] = q[0]; self.q[o + 1] = q[1]; self.q[o + 2] = q[2];
        if hit != 0 && self.anc_kind[i] == 0 && self.anc_cool[i] <= 0.0 && hit_stick > 0.0 && self.rho[i] < 1.25 * self.rho0 {
            self.anc_kind[i] = hit; self.anc_id[i] = hit_id; self.anc_stick[i] = hit_stick; self.anc_axis[i] = hit_axis;
            for a in 0..3 { self.anc_pt[o + a] = q[a]; self.anc_loc[o + a] = q[a] - hit_min[a]; }
        }
    }

    fn spring_exists(&self, a: usize, b: usize) -> bool {
        let (na, nb) = (self.spr_n[a] as usize, self.spr_n[b] as usize);
        self.spr_j[a * MAX_SPR..a * MAX_SPR + na].iter().any(|&j| j as usize == b) || self.spr_j[b * MAX_SPR..b * MAX_SPR + nb].iter().any(|&j| j as usize == a)
    }

    fn step_once(&mut self) {
        let dt = DT;
        self.clock += dt;
        let (nb, slots) = (self.nb, self.slots);

        // moving colliders wake anything they come near
        for k in 0..self.kin.len() {
            let b = self.kin[k];
            if b.vel[0] == 0.0 && b.vel[1] == 0.0 && b.vel[2] == 0.0 { continue; }
            for bd in self.bodies.iter_mut() {
                if bd.state == 0 { continue; }
                if !bd.awake && (0..3).all(|a| b.max[a] > bd.bb[a] - 0.6 && b.min[a] < bd.bb[a + 3] + 0.6) { bd.awake = true; bd.calm = 0; }
                if (0..3).all(|a| b.max[a] > bd.bb[a] - 0.3 && b.min[a] < bd.bb[a + 3] + 0.3) { bd.shock = 1.0; }
            }
        }

        // ---- bodies ----
        self.act.clear();
        for b in 0..slots {
            if self.bodies[b].state == 0 || !self.bodies[b].awake { continue; }
            let m = self.mats[self.bodies[b].mat as usize];
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
            let mut fade_done = false;
            if bd.state == 2 { bd.fade -= dt / 0.6; if bd.fade <= 0.0 { fade_done = true; } }
            let (fade, fading, grounded, kd) = (bd.fade, bd.state == 2, bd.anchored > 0 || bd.shock > 0.05, bd.kd);
            if fade_done { self.free_body(b); continue; }
            let mut cnt = 0u32;
            for i in b * nb..(b + 1) * nb {
                if self.alive[i] != 0 {
                    self.act.push(i as u32); cnt += 1;
                    self.damp[i] = if grounded { kd } else { 0.15 };
                    if fading { self.scale[i] = fade; }
                }
            }
            self.bodies[b].count = cnt;
            if cnt == 0 { self.free_body(b); }
        }
        if self.act.is_empty() { return; }
        let n_act = self.act.len();

        // ---- kinematic colliders move ----
        for b in self.kin.iter_mut() {
            b.pmin = b.min; b.pmax = b.max; b.t += dt;
            if b.t > 0.3 { b.vel = [0.0; 3]; }
            for a in 0..3 { b.min[a] += b.vel[a] * dt; b.max[a] += b.vel[a] * dt; }
        }

        // ---- predict ----
        let g = self.gravity;
        for a in 0..n_act {
            let i = self.act[a] as usize; let o = i * 3;
            let (x, z) = (self.p[o], self.p[o + 2]);
            let mut k = self.damp[i];
            if k > 0.5 { k *= 0.72 + 0.34 * ((x * 6.1 + 1.9 * (z * 2.3).sin()).sin() + (z * 3.7 + x * 1.4).sin()); }
            let f = (-k * dt).exp();
            self.v[o] *= f; self.v[o + 1] = (self.v[o + 1] - g * dt) * f; self.v[o + 2] *= f;
            for c in 0..3 { self.q[o + c] = self.p[o + c] + self.v[o + c] * dt; }
            if self.anc_cool[i] > 0.0 { self.anc_cool[i] -= dt; }
        }
        for a in 0..n_act { let i = self.act[a] as usize; self.collide(i); }

        // ---- neighbour pairs (hash grid, half stencil) ----
        let (h, h2, mask) = (self.h, self.h * self.h, self.mask);
        let ih = 1.0 / h;
        for c in self.c_start.iter_mut() { *c = 0; }
        for a in 0..n_act {
            let i = self.act[a] as usize; let o = i * 3;
            let (ix, iy, iz) = ((self.q[o] * ih).floor() as i32 + 512, (self.q[o + 1] * ih).floor() as i32 + 512, (self.q[o + 2] * ih).floor() as i32 + 512);
            self.cell[o] = ix; self.cell[o + 1] = iy; self.cell[o + 2] = iz;
            self.pkey[i] = key3(ix, iy, iz);
            let bk = hash3(ix, iy, iz, mask); self.pbkt[i] = bk; self.c_start[bk as usize + 1] += 1;
        }
        let table = mask as usize + 1;
        for c in 0..table { self.c_start[c + 1] += self.c_start[c]; self.c_fill[c] = self.c_start[c]; }
        for a in 0..n_act { let i = self.act[a]; let b = self.pbkt[i as usize] as usize; self.sorted[self.c_fill[b] as usize] = i; self.c_fill[b] += 1; }
        self.pi.clear(); self.pj.clear();
        for a in 0..n_act {
            let i = self.act[a] as usize; let o = i * 3;
            let (x, y, z) = (self.q[o], self.q[o + 1], self.q[o + 2]);
            let (ix, iy, iz) = (self.cell[o], self.cell[o + 1], self.cell[o + 2]);
            for c in 0..14 {
                let (jx, jy, jz) = (ix + HX[c], iy + HY[c], iz + HZ[c]);
                let key = key3(jx, jy, jz); let bk = hash3(jx, jy, jz, mask) as usize;
                for k in self.c_start[bk]..self.c_start[bk + 1] {
                    let j = self.sorted[k as usize] as usize;
                    if self.pkey[j] != key || (c == 0 && j <= i) { continue; }
                    let oj = j * 3; let (ex, ey, ez) = (x - self.q[oj], y - self.q[oj + 1], z - self.q[oj + 2]);
                    if ex * ex + ey * ey + ez * ez < h2 { self.pi.push(i as u32); self.pj.push(j as u32); }
                }
            }
        }
        let n_pair = self.pi.len();
        self.last_pairs = n_pair;
        self.pg.resize(n_pair * 3, 0.0); self.pw.resize(n_pair, 0.0);

        // ---- springs form ----
        let hs2 = (1.5 * self.s) * (1.5 * self.s);
        for k in 0..n_pair {
            let (i, j) = (self.pi[k] as usize, self.pj[k] as usize);
            let (bi, bj) = (i / nb, j / nb);
            let (mi, mj) = (self.bodies[bi].mat, self.bodies[bj].mat);
            if mi != mj { continue; }
            let m = self.mats[mi as usize];
            if !(m.dynamic_springs || (bi == bj && self.bodies[bi].fresh)) { continue; }
            let (oi, oj) = (i * 3, j * 3);
            let (ex, ey, ez) = (self.q[oi] - self.q[oj], self.q[oi + 1] - self.q[oj + 1], self.q[oi + 2] - self.q[oj + 2]);
            let r2 = ex * ex + ey * ey + ez * ez;
            if r2 > hs2 { continue; }
            let (a, b) = if self.spr_n[i] <= self.spr_n[j] { (i, j) } else { (j, i) };
            let na = self.spr_n[a] as usize;
            if na >= MAX_SPR || self.spring_exists(i, j) { continue; }
            self.spr_j[a * MAX_SPR + na] = b as u32; self.spr_l[a * MAX_SPR + na] = r2.sqrt().max(0.7 * self.s); self.spr_n[a] += 1;
        }
        for b in 0..slots { self.bodies[b].fresh = false; }

        // ---- constraint iterations ----
        let (ir0, eps, kc, iwq, kp, ks, w0) = (1.0 / self.rho0, self.eps, self.k_corr, 1.0 / self.wq, self.kp, self.ks, self.w0);
        let max_d = 0.9 * self.s;
        for it in 0..self.iters {
            let last = it + 1 == self.iters;
            for a in 0..n_act { let i = self.act[a] as usize; self.rho[i] = w0; self.sgx[i] = 0.0; self.sgy[i] = 0.0; self.sgz[i] = 0.0; self.sg2[i] = 0.0; self.dpx[i] = 0.0; self.dpy[i] = 0.0; self.dpz[i] = 0.0; }
            for k in 0..n_pair {
                let (i, j) = (self.pi[k] as usize, self.pj[k] as usize); let (oi, oj) = (i * 3, j * 3);
                let (ex, ey, ez) = (self.q[oi] - self.q[oj], self.q[oi + 1] - self.q[oj + 1], self.q[oi + 2] - self.q[oj + 2]);
                let r2 = ex * ex + ey * ey + ez * ez;
                if r2 >= h2 { self.pw[k] = 0.0; continue; }
                let r = r2.sqrt() + 1e-7; let t = h2 - r2; let w = kp * t * t * t; let u = h - r; let gm = -ks * u * u / r;
                let (gx, gy, gz) = (gm * ex, gm * ey, gm * ez); let g2 = gx * gx + gy * gy + gz * gz;
                self.pw[k] = w; self.pg[k * 3] = gx; self.pg[k * 3 + 1] = gy; self.pg[k * 3 + 2] = gz;
                self.rho[i] += w; self.rho[j] += w;
                self.sgx[i] += gx; self.sgy[i] += gy; self.sgz[i] += gz; self.sgx[j] -= gx; self.sgy[j] -= gy; self.sgz[j] -= gz;
                self.sg2[i] += g2; self.sg2[j] += g2;
            }
            for a in 0..n_act {
                let i = self.act[a] as usize;
                let m = &self.mats[self.bodies[i / nb].mat as usize];
                let mut c = self.rho[i] * ir0 - 1.0;
                if c < 0.0 { c = c.max(-m.coh_lim) * m.coh_k; }
                let den = (self.sg2[i] + self.sgx[i] * self.sgx[i] + self.sgy[i] * self.sgy[i] + self.sgz[i] * self.sgz[i]) * ir0 * ir0 + eps;
                self.lam[i] = -c / den;
            }
            for k in 0..n_pair {
                let w = self.pw[k]; if w == 0.0 { continue; }
                let (i, j) = (self.pi[k] as usize, self.pj[k] as usize);
                let mut qq = w * iwq; qq *= qq; qq *= qq;
                let f = (self.lam[i] + self.lam[j] - kc * qq) * ir0;
                let (fx, fy, fz) = (f * self.pg[k * 3], f * self.pg[k * 3 + 1], f * self.pg[k * 3 + 2]);
                self.dpx[i] += fx; self.dpy[i] += fy; self.dpz[i] += fz; self.dpx[j] -= fx; self.dpy[j] -= fy; self.dpz[j] -= fz;
            }
            for a in 0..n_act {
                let i = self.act[a] as usize; let o = i * 3;
                let (mut x, mut y, mut z) = (self.dpx[i], self.dpy[i], self.dpz[i]);
                let l2 = x * x + y * y + z * z;
                if l2 > max_d * max_d { let sc = max_d / l2.sqrt(); x *= sc; y *= sc; z *= sc; }
                self.q[o] += x; self.q[o + 1] += y; self.q[o + 2] += z;
            }
            // springs
            for a in 0..n_act {
                let i = self.act[a] as usize; let oi = i * 3;
                let ksp = self.bodies[i / nb].k_spring;
                for sidx in 0..self.spr_n[i] as usize {
                    let j = self.spr_j[i * MAX_SPR + sidx] as usize; let oj = j * 3;
                    let l = self.spr_l[i * MAX_SPR + sidx];
                    let fixed = !self.bodies[j / nb].awake;
                    let (jx, jy, jz) = if fixed { (self.p[oj], self.p[oj + 1], self.p[oj + 2]) } else { (self.q[oj], self.q[oj + 1], self.q[oj + 2]) };
                    let (ex, ey, ez) = (self.q[oi] - jx, self.q[oi + 1] - jy, self.q[oi + 2] - jz);
                    let r = (ex * ex + ey * ey + ez * ez).sqrt() + 1e-7;
                    let c = ksp * (r - l) / r * if fixed { 1.0 } else { 0.5 };
                    self.q[oi] -= ex * c; self.q[oi + 1] -= ey * c; self.q[oi + 2] -= ez * c;
                    if !fixed { self.q[oj] += ex * c; self.q[oj + 1] += ey * c; self.q[oj + 2] += ez * c; }
                }
            }
            // anchors pull, then colliders push
            for a in 0..n_act {
                let i = self.act[a] as usize; let o = i * 3;
                let kind = self.anc_kind[i];
                if kind != 0 {
                    if kind == 2 {
                        if let Some(b) = self.kin.iter().find(|b| b.id == self.anc_id[i]) { for c in 0..3 { self.anc_pt[o + c] = b.min[c] + self.anc_loc[o + c]; } }
                    }
                    let ak = self.mats[self.bodies[i / nb].mat as usize].adh_k;
                    for c in 0..3 {
                        let d = self.q[o + c] - self.anc_pt[o + c];
                        if last { self.anc_d[o + c] = d; } // how far everything else dragged it this iteration
                        self.q[o + c] -= ak * d;
                    }
                }
                self.collide(i);
            }
        }

        // ---- anchors creep / snap, springs flow / snap ----
        for a in 0..n_act {
            let i = self.act[a] as usize; let o = i * 3;
            let bd = self.bodies[i / nb];
            let m = self.mats[bd.mat as usize];
            if self.anc_kind[i] != 0 {
                let (ex, ey, ez) = (self.anc_d[o], self.anc_d[o + 1], self.anc_d[o + 2]);
                let d = (ex * ex + ey * ey + ez * ez).sqrt();
                if d > m.adh_break * self.anc_stick[i] || self.rho[i] > 1.6 * self.rho0 { self.anc_kind[i] = 0; self.anc_cool[i] = 0.35; }
                else if m.adh_creep > 0.0 {
                    let cf = 1.0 - (-m.adh_creep * (0.25 + 0.75 * bd.melt) * dt).exp();
                    let e = [ex, ey, ez]; let ax = self.anc_axis[i] as usize;
                    for c in 0..3 { if c != ax { self.anc_pt[o + c] += e[c] * cf; self.anc_loc[o + c] += e[c] * cf; } } // slides along the surface only
                }
            }
            let mut sidx = 0usize;
            while sidx < self.spr_n[i] as usize {
                let si = i * MAX_SPR + sidx;
                let j = self.spr_j[si] as usize; let oj = j * 3;
                let mut l = self.spr_l[si];
                let (ex, ey, ez) = (self.q[o] - self.q[oj], self.q[o + 1] - self.q[oj + 1], self.q[o + 2] - self.q[oj + 2]);
                let r = (ex * ex + ey * ey + ez * ez).sqrt();
                if bd.plast > 0.0 {
                    let dl = r - l; let y = m.yield_ * l;
                    if dl.abs() > y { l += (1.0 - (-bd.plast * dt).exp()) * (dl - y * dl.signum()); self.spr_l[si] = l; }
                }
                let gone = self.alive[j] == 0 || r > bd.brk * l || l > bd.max_l;
                if gone { let last = i * MAX_SPR + self.spr_n[i] as usize - 1; self.spr_j[si] = self.spr_j[last]; self.spr_l[si] = self.spr_l[last]; self.spr_n[i] -= 1; }
                else { sidx += 1; }
            }
        }

        // ---- velocities, viscosity, commit ----
        let idt = 1.0 / dt;
        let max_move = 0.3f32; // blocks per step; stops a crushed particle from being shot across the map
        for a in 0..n_act {
            let i = self.act[a] as usize; let o = i * 3;
            let (ex, ey, ez) = (self.q[o] - self.p[o], self.q[o + 1] - self.p[o + 1], self.q[o + 2] - self.p[o + 2]);
            let d2 = ex * ex + ey * ey + ez * ez;
            if d2 > max_move * max_move { let sc = max_move / d2.sqrt(); self.q[o] = self.p[o] + ex * sc; self.q[o + 1] = self.p[o + 1] + ey * sc; self.q[o + 2] = self.p[o + 2] + ez * sc; }
            for c in 0..3 { self.v[o + c] = (self.q[o + c] - self.p[o + c]) * idt; }
        }
        for _ in 0..self.visc_iters {
            for a in 0..n_act { let i = self.act[a] as usize; self.dpx[i] = 0.0; self.dpy[i] = 0.0; self.dpz[i] = 0.0; }
            for k in 0..n_pair {
                let w = self.pw[k]; if w == 0.0 { continue; }
                let (i, j) = (self.pi[k] as usize, self.pj[k] as usize); let (oi, oj) = (i * 3, j * 3);
                let (ex, ey, ez) = (self.v[oj] - self.v[oi], self.v[oj + 1] - self.v[oi + 1], self.v[oj + 2] - self.v[oi + 2]);
                let wi = self.mats[self.bodies[i / nb].mat as usize].xsph * w / self.rho[i];
                let wj = self.mats[self.bodies[j / nb].mat as usize].xsph * w / self.rho[j];
                self.dpx[i] += ex * wi; self.dpy[i] += ey * wi; self.dpz[i] += ez * wi;
                self.dpx[j] -= ex * wj; self.dpy[j] -= ey * wj; self.dpz[j] -= ez * wj;
            }
            for a in 0..n_act { let i = self.act[a] as usize; let o = i * 3; self.v[o] += self.dpx[i]; self.v[o + 1] += self.dpy[i]; self.v[o + 2] += self.dpz[i]; }
        }
        let kill_y = self.kill_y;
        let mut vsum = vec![0.0f32; slots];
        for b in 0..slots { if self.bodies[b].awake { self.bodies[b].bb = [f32::MAX, f32::MAX, f32::MAX, f32::MIN, f32::MIN, f32::MIN]; self.bodies[b].anchored = 0; } }
        for a in 0..n_act {
            let i = self.act[a] as usize; let o = i * 3; let b = i / nb;
            let mut sp = 0.0;
            for c in 0..3 { self.p[o + c] = self.q[o + c]; sp += self.v[o + c] * self.v[o + c]; }
            if self.p[o + 1] < kill_y || !sp.is_finite() { self.alive[i] = 0; self.scale[i] = 0.0; self.spr_n[i] = 0; self.anc_kind[i] = 0; continue; }
            vsum[b] += sp;
            let bd = &mut self.bodies[b];
            for c in 0..3 { bd.bb[c] = bd.bb[c].min(self.p[o + c]); bd.bb[c + 3] = bd.bb[c + 3].max(self.p[o + c]); }
            if self.anc_kind[i] != 0 { bd.anchored += 1; }
        }

        // ---- sleep / wake ----
        for b in 0..slots {
            let m = self.mats[self.bodies[b].mat as usize];
            let bd = &mut self.bodies[b];
            if bd.state == 0 || !bd.awake { continue; }
            let vrms = (vsum[b] / bd.count.max(1) as f32).sqrt();
            bd.quiet = bd.state == 1 && vrms < 0.012 && bd.age > m.melt_time + 1.0;
            bd.calm = if bd.quiet { bd.calm + 1 } else { 0 };
        }
        let moving_kin: Vec<KBox> = self.kin.iter().copied().filter(|b| b.vel != [0.0; 3]).collect();
        for b in 0..slots {
            if self.bodies[b].state == 0 || !self.bodies[b].awake { continue; }
            let bb = self.bodies[b].bb;
            let mut may_sleep = self.bodies[b].calm > 120;
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
    }
}
