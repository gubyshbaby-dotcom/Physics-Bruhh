//! When does a lone block of honey / slime come to rest? `cargo run --release --example sleep`
use physicsbruhh_native::sim::*;
fn main() {
    for mat in [HONEY, SLIME] {
        let mut w = World::new(12, 2);
        let mut floor = Vec::new();
        for x in -6..7 { for z in -6..7 { floor.extend_from_slice(&[x as f32, -1.0, z as f32, x as f32 + 1.0, 0.0, z as f32 + 1.0, 1.0]); } }
        w.set_static(&floor, floor.len() / 7);
        w.add_body(mat, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0);
        let mut asleep_at = -1.0f32;
        for step in 0..3600 {
            w.step(DT + 1e-6);
            if step % 120 == 119 || (asleep_at < 0.0 && w.awake_count() == 0) {
                let b = w.bodies[0].bb;
                if asleep_at < 0.0 && w.awake_count() == 0 { asleep_at = (step + 1) as f32 * DT; }
                if step % 120 == 119 && (step + 1) / 60 <= 20 { println!("mat {} t={:>2}s width {:.2} x {:.2} height {:.2} awake {}", mat, (step + 1) / 60, b[3] - b[0], b[5] - b[2], b[4], w.awake_count()); }
            }
        }
        println!("mat {} asleep at {:.1} s", mat, asleep_at);
    }
}
