//! Headless scenarios for eyeballing the simulation: `cargo run --release --example viz -- <scenario> <out.ppm>`
//! Writes a contact sheet of side views (x right, y up; the faint row below is the top view).

use physicsbruhh_native::mesh;
use physicsbruhh_native::sim::*;
use std::io::Write;

const CW: usize = 330; const SH: usize = 170; const TH: usize = 200; const COLS: usize = 5; const SC: f32 = 70.0;

struct Sheet { w: usize, h: usize, px: Vec<u8> }
impl Sheet {
    fn new(rows: usize) -> Sheet { let (w, h) = (CW * COLS, (SH + TH) * rows); Sheet { w, h, px: vec![22; w * h * 3] } }
    fn put(&mut self, x: i32, y: i32, c: [u8; 3]) { if x < 0 || y < 0 || x as usize >= self.w || y as usize >= self.h { return; } let o = (y as usize * self.w + x as usize) * 3; for k in 0..3 { self.px[o + k] = self.px[o + k].max(c[k]); } }
    fn rect(&mut self, x0: f32, y0: f32, x1: f32, y1: f32, c: [u8; 3]) {
        let (a, b, cc, d) = (x0.round() as i32, y0.round() as i32, x1.round() as i32, y1.round() as i32);
        for x in a..=cc { self.put(x, b, c); self.put(x, d, c); } for y in b..=d { self.put(a, y, c); self.put(cc, y, c); }
    }
}

fn snap(sh: &mut Sheet, idx: usize, w: &World, statics: &[f32], cx: f32) {
    let (col, row) = (idx % COLS, idx / COLS);
    let (x0, y0) = ((col * CW) as f32 + CW as f32 * 0.5, (row * (SH + TH)) as f32 + SH as f32 - 30.0);
    let tz = (row * (SH + TH) + SH + TH / 2) as f32;
    for b in statics.chunks(7) { if b[2] < 0.6 && b[5] > 0.4 { sh.rect(x0 + (b[0] - cx) * SC, y0 - b[4] * SC, x0 + (b[3] - cx) * SC, y0 - b[1] * SC, [70, 70, 80]); } }
    for k in &w.kin { sh.rect(x0 + (k.min[0] - cx) * SC, y0 - k.max[1] * SC, x0 + (k.max[0] - cx) * SC, y0 - k.min[1] * SC, [230, 60, 60]); }
    for i in 0..w.maxp {
        if w.alive[i] == 0 { continue; }
        let (x, y, z) = (w.p[i * 3], w.p[i * 3 + 1], w.p[i * 3 + 2]);
        let slime = w.bodies[i / w.nb].mat == SLIME;
        let shade = (90.0 + (z * 150.0).clamp(0.0, 165.0)) as u8;
        let c = if slime { [60, shade, 60] } else { [255, shade, 40] };
        let (u, v) = ((x0 + (x - cx) * SC) as i32, (y0 - y * SC) as i32);
        if (u as usize) > col * CW + 1 && (u as usize) < (col + 1) * CW - 2 { sh.put(u, v, c); sh.put(u + 1, v, c); sh.put(u, v + 1, c); sh.put(u + 1, v + 1, c);
            let hs = (60.0 + y * 190.0).clamp(0.0, 255.0) as u8; let tv = (tz + (z - 0.5) * SC) as i32;
            if tv > (row * (SH + TH) + SH) as i32 { sh.put(u, tv, [hs / 2, hs / 3, 20]); } }
    }
    for (a, b) in w.long_springs(1.9 * w.s) {
        for k in 0..=24 { let f = k as f32 / 24.0; let (x, y) = (a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f);
            let u = (x0 + (x - cx) * SC) as i32; if (u as usize) > col * CW + 1 && (u as usize) < (col + 1) * CW - 2 { sh.put(u, (y0 - y * SC) as i32, [120, 120, 255]); } }
    }
    for y in 0..(SH + TH) { sh.put((col * CW) as i32, (row * (SH + TH) + y) as i32, [90, 90, 90]); }
}

fn floor(x0: i32, x1: i32) -> Vec<f32> { let mut v = Vec::new(); for x in x0..x1 { for z in -3..4 { v.extend_from_slice(&[x as f32, -1.0, z as f32, x as f32 + 1.0, 0.0, z as f32 + 1.0, 1.0]); } } v }

/// A piston head plate whose front face is at `face` (moving along x), `dir` = +1 faces right.
fn plate(id: i32, face: f32, dir: f32, vel: f32, stick: f32) -> [f32; 11] {
    let (a, b) = if dir > 0.0 { (face - 0.25, face) } else { (face, face + 0.25) };
    [id as f32, a, 0.0, 0.0, b, 1.0, 1.0, vel, 0.0, 0.0, stick]
}

fn main() {
    let args: Vec<String> = std::env::args().collect();
    let scen = args.get(1).map(|s| s.as_str()).unwrap_or("honey");
    let out = args.get(2).cloned().unwrap_or_else(|| "viz.ppm".into());
    let n: usize = args.get(3).and_then(|s| s.parse().ok()).unwrap_or(12);
    let mut w = World::new(n, 6);
    // quick tuning without recompiling: H_spring_k0=0.5 S_adh_break=0.4 ... in the environment
    macro_rules! tune { ($($f:ident),*) => { for (pre, mi) in [("H_", 0usize), ("S_", 1usize)] { $( if let Ok(v) = std::env::var(format!("{}{}", pre, stringify!($f))) { w.mats[mi].$f = v.parse().unwrap(); } )* } } }
    tune!(melt_time, damp_fresh, damp_melted, spring_k0, spring_k1, plast0, plast1, yield_, break_strain, coh_k, coh_lim, xsph, adh_k, adh_creep, adh_break, set_after, set_rate, tip, spring_max_l, shock_k, shock_plast, shock_decay, shock_break, shock_max_l, thread_sag);
    let mut statics = floor(-5, 6);
    let times: Vec<f32>;
    let mut cx = 0.5;
    // piston script: (id, dir, stick, [(time, face position)...]) with linear motion between keys
    let mut pistons: Vec<(i32, f32, f32, Vec<(f32, f32)>)> = Vec::new();
    match scen {
        "honey" => { w.add_body(HONEY, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0); times = vec![0.0, 0.25, 0.5, 0.8, 1.1, 1.5, 2.0, 3.0, 5.0, 9.0]; }
        "slime" => { w.add_body(SLIME, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0); times = vec![0.0, 0.1, 0.2, 0.35, 0.5, 0.8, 1.2, 2.0, 3.0, 5.0]; }
        "slime_push" | "honey_push" => {
            // piston base in cell x=-2, goo block in cell x=-1; extend at 1.0 s, retract (sticky) at 2.5 s
            statics.extend_from_slice(&[-2.0, 0.0, 0.0, -1.0, 1.0, 1.0, 1.0]);
            w.add_body(if scen == "slime_push" { SLIME } else { HONEY }, -1.0, 0.0, 0.0, 1.0, 0.0, 0.0);
            pistons.push((1, 1.0, 3.0, vec![(0.0, -1.0), (1.0, -1.0), (1.1, 0.0), (2.5, 0.0), (2.6, -1.0), (99.0, -1.0)]));
            times = vec![0.9, 1.05, 1.12, 1.3, 1.8, 2.45, 2.55, 2.62, 2.8, 4.0]; cx = 0.0;
        }
        "pull" | "pull_slime" => {
            // two extended sticky pistons facing each other with goo between; both retract at 4 s
            statics.extend_from_slice(&[-2.0, 0.0, 0.0, -1.0, 1.0, 1.0, 1.0]);
            statics.extend_from_slice(&[2.0, 0.0, 0.0, 3.0, 1.0, 1.0, 1.0]);
            w.add_body(if scen == "pull" { HONEY } else { SLIME }, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0);
            pistons.push((1, 1.0, 3.0, vec![(0.0, 0.0), (4.0, 0.0), (4.1, -1.0), (99.0, -1.0)]));
            pistons.push((2, -1.0, 3.0, vec![(0.0, 1.0), (4.0, 1.0), (4.1, 2.0), (99.0, 2.0)]));
            times = vec![3.9, 4.03, 4.07, 4.12, 4.2, 4.4, 4.8, 5.5, 7.0, 10.0];
        }
        _ => { eprintln!("unknown scenario"); return; }
    }
    w.set_static(&statics, statics.len() / 7);
    let mut sh = Sheet::new(2);
    let (mut ti, mut t, mut ms_sum, mut ms_max, mut steps) = (0usize, 0.0f32, 0.0f64, 0.0f64, 0u32);
    let total = times.last().copied().unwrap() + 0.01;
    let mut mesh_buf: Vec<f32> = Vec::new();
    while t <= total {
        if ti < times.len() && t >= times[ti] - 1e-4 {
            snap(&mut sh, ti, &w, &statics, cx);
            mesh_buf.clear(); let t0 = std::time::Instant::now(); let quads = mesh::build(&w, HONEY, &mut mesh_buf) + mesh::build(&w, SLIME, &mut mesh_buf);
            let bd = w.bodies[0];
            let lc = w.link_counts();
            println!("t={:.2} awake={} pairs={} quads={} mesh={:.2}ms bb=[{:.2},{:.2},{:.2}]..[{:.2},{:.2},{:.2}] anchors st={} kin={} springs={} shock={:.2}", t, w.awake_count(), w.last_pairs, quads, t0.elapsed().as_secs_f64() * 1e3, bd.bb[0], bd.bb[1], bd.bb[2], bd.bb[3], bd.bb[4], bd.bb[5], lc.0, lc.1, lc.2, bd.shock);
            ti += 1;
        }
        if !pistons.is_empty() {
            let mut data: Vec<f32> = Vec::new();
            for (id, dir, stick, keys) in &pistons {
                let k = keys.windows(2).find(|k| t >= k[0].0 && t < k[1].0).unwrap();
                let f = (t - k[0].0) / (k[1].0 - k[0].0);
                let face = k[0].1 + (k[1].1 - k[0].1) * f; let vel = (k[1].1 - k[0].1) / (k[1].0 - k[0].0);
                data.extend_from_slice(&plate(*id, face, *dir, vel, *stick));
            }
            w.set_kinematic(&data, pistons.len());
        }
        let t0 = std::time::Instant::now();
        w.step(DT + 1e-6);
        let ms = t0.elapsed().as_secs_f64() * 1e3; ms_sum += ms; if steps > 3 && ms > ms_max { ms_max = ms; } steps += 1;
        t += DT;
    }
    println!("avg {:.2} ms/step, max {:.2}", ms_sum / steps as f64, ms_max);
    let mut f = std::fs::File::create(&out).unwrap();
    write!(f, "P6\n{} {}\n255\n", sh.w, sh.h).unwrap(); f.write_all(&sh.px).unwrap();
}
