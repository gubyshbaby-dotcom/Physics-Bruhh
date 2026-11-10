//! Writes the particles of a small show scene to a file (x, y, z, size + 2 for slime; little-endian
//! floats), for looking at the liquid shaders outside the game: `cargo run --release --example dump -- out.bin [seconds]`
use physicsbruhh_native::sim::*;
use std::io::Write;

fn main() {
    let args: Vec<String> = std::env::args().collect();
    let out = args.get(1).cloned().unwrap_or_else(|| "particles.bin".into());
    let seconds: f32 = args.get(2).and_then(|s| s.parse().ok()).unwrap_or(6.0);
    let mut w = World::new(12, 8);
    let mut st = Vec::new();
    for x in -6..10 { for z in -6..8 { st.extend_from_slice(&[x as f32, -1.0, z as f32, x as f32 + 1.0, 0.0, z as f32 + 1.0, 1.0]); } }
    // two piston bases with a gap of three blocks, a dispenser one block up
    st.extend_from_slice(&[-2.0, 0.0, 0.0, -1.0, 1.0, 1.0, 2.0]);
    st.extend_from_slice(&[2.0, 0.0, 0.0, 3.0, 1.0, 1.0, 2.0]);
    st.extend_from_slice(&[-3.0, 0.0, 3.0, -2.0, 2.0, 4.0, 1.0]);
    w.set_static(&st, st.len() / 7);
    // slime pulled into a band between the two sticky heads
    w.add_body(SLIME, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0);
    let mut shown = 0.0f32;
    let steps = (seconds * 60.0) as usize;
    // a honey block that melts, a slime block that just sits there
    w.add_body(HONEY, 4.0, 0.0, 3.0, 1.0, 0.3, 0.0);
    w.add_body(SLIME, 5.0, 0.0, -2.0, 1.0, 0.0, 0.0);
    let (mut stream, mut lumps) = (usize::MAX, usize::MAX);
    for step in 0..steps {
        if step % 3 == 0 {
            let t = step / 3;
            let next = if t >= 40 { (shown + 0.5).min(1.0) } else { 0.0 };
            let mut kin: Vec<f32> = Vec::new();
            if shown < 1.0 || next < 1.0 {
                for (id, base, dir) in [(1.0f32, -2.0f32, 1.0f32), (3.0, 2.0, -1.0)] {
                    let (v, off, head) = (-(next - shown) * 20.0 * dir, -dir * shown, base + dir);
                    let plate = if dir > 0.0 { (head + 0.75, head + 1.0) } else { (head, head + 0.25) };
                    kin.extend_from_slice(&[id, plate.0 + off, 0.0, 0.0, plate.1 + off, 1.0, 1.0, v, 0.0, 0.0, 8.0, if dir > 0.0 { 1.0 } else { 2.0 }]);
                }
            }
            shown = next;
            w.set_static(&st, st.len() / 7);
            w.set_kinematic(&kin, kin.len() / KIN_FLOATS);
        }
        // two bottles of honey from the dispenser, a little before the end; three slime balls earlier
        if step + 100 == steps || step + 190 == steps {
            if stream == usize::MAX { stream = w.add_empty(HONEY, -2.0, 1.0, 3.0) as usize; }
            w.emit(stream, [-1.95, 1.5, 3.5], [3.2, 0.4, 0.3], 432, false);
        }
        if step == 120 || step == 150 || step == 180 {
            if lumps == usize::MAX { lumps = w.add_empty(SLIME, 0.0, 1.0, 5.0) as usize; }
            w.emit(lumps, [0.2, 1.4, 5.5], [3.5, 1.0, -0.4], 192, true);
        }
        w.step(DT + 1e-6);
    }
    let mut buf = vec![0.0f32; (w.maxp + PARTICLE_FILLERS) * 4];
    let n = w.particles(&mut buf).max(0) as usize;
    let mut f = std::fs::File::create(&out).unwrap();
    for v in &buf[..n * 4] { f.write_all(&v.to_le_bytes()).unwrap(); }
    println!("{} particles to {}, spacing {}", n, out, w.s);
}
