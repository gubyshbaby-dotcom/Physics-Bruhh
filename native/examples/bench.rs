//! Timing of the solver and the mesher on a busy scene: `cargo run --release --example bench -- [bodies] [seconds]`

use physicsbruhh_native::mesh;
use physicsbruhh_native::sim::*;

fn main() {
    let args: Vec<String> = std::env::args().collect();
    let bodies: usize = args.get(1).and_then(|s| s.parse().ok()).unwrap_or(8);
    let seconds: f32 = args.get(2).and_then(|s| s.parse().ok()).unwrap_or(3.0);
    let mut w = World::new(12, bodies.max(1));
    let mut floor = Vec::new();
    for x in -8..24 { for z in -6..8 { floor.extend_from_slice(&[x as f32, -1.0, z as f32, x as f32 + 1.0, 0.0, z as f32 + 1.0, 1.0]); } }
    w.set_static(&floor, floor.len() / 7);
    // a row of blocks, alternately honey and slime, two blocks apart so each melts on its own, plus a merged honey pair
    for b in 0..bodies {
        let mat = if b % 3 == 2 { SLIME } else { HONEY };
        let x = if b == 1 { 1.0 } else { b as f32 * 2.0 };
        w.add_body(mat, x, 0.0, 0.0, 1.0, 0.0, 0.0);
    }
    let steps = (seconds * 60.0) as usize;
    let t0 = std::time::Instant::now();
    let mut pairs = 0usize;
    for _ in 0..steps { w.step(DT + 1e-6); pairs += w.last_pairs; }
    let step_ms = t0.elapsed().as_secs_f64() * 1e3 / steps as f64;
    let mut out = Vec::new();
    let t1 = std::time::Instant::now();
    let mut quads = 0;
    for _ in 0..20 { out.clear(); quads = mesh::build(&w, HONEY, &mut out) + mesh::build(&w, SLIME, &mut out); }
    let mesh_ms = t1.elapsed().as_secs_f64() * 1e3 / 20.0;
    let total: f64 = w.prof.iter().sum();
    let names = ["predict+collide", "pairs", "springs form", "constraints", "anchors/springs post", "velocity/visc/commit", "sleep"];
    println!("{} threads, {} bodies, {} awake at the end, {:.0} pairs/step, {} quads", threads(), bodies, w.awake_count(), pairs as f64 / steps as f64, quads);
    println!("step {:.2} ms, mesh {:.2} ms", step_ms, mesh_ms);
    for (k, n) in names.iter().enumerate() { println!("  {:<22} {:5.1} %", n, w.prof[k] / total * 100.0); }

    // every bit of every position: the same whatever the number of threads
    let mut sum = 0xcbf29ce484222325u64;
    for v in &w.p { sum = (sum ^ v.to_bits() as u64).wrapping_mul(0x100000001b3); }
    let b = w.bodies[0].bb;
    println!("body 0 bounds {:.4} {:.4} {:.4} .. {:.4} {:.4} {:.4}, positions checksum {:016x}", b[0], b[1], b[2], b[3], b[4], b[5], sum);
}
