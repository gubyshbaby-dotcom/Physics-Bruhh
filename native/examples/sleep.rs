//! How long a freshly placed block stays awake (simulated seconds), and how fast it still moves on the way.
use physicsbruhh_native::sim::*;
fn main() {
    for (name, mat) in [("honey", HONEY), ("slime", SLIME)] {
        let mut w = World::new(12, 4);
        let mut f = Vec::new();
        for x in -5..6 { for z in -3..4 { f.extend_from_slice(&[x as f32, -1.0, z as f32, x as f32 + 1.0, 0.0, z as f32 + 1.0, 1.0]); } }
        w.set_static(&f, f.len() / 7);
        w.add_body(mat, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0);
        let mut t = 0.0f32;
        let mut line = String::new();
        while w.bodies[0].awake && t < 120.0 {
            let before = w.p.clone();
            for _ in 0..60 { w.step(DT + 1e-6); }
            t += 1.0;
            let mut far = 0.0f32;
            for i in 0..w.maxp { if w.alive[i] != 0 { let d = (0..3).map(|a| (w.p[i * 3 + a] - before[i * 3 + a]).powi(2)).sum::<f32>().sqrt(); far = far.max(d); } }
            let bb = w.bodies[0].bb;
            if (t as i32) % 2 == 0 || !w.bodies[0].awake { line += &format!(" {}s:{:.3}/{:.2}", t as i32, far, bb[3] - bb[0]); }
        }
        println!("{} sleeps after about {} s; fastest particle's travel per second / width:{}", name, t, line);
    }
}
