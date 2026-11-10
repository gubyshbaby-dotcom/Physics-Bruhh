//! Behavioural checks for the simulation core. They pin down what the mod relies on: honey melts
//! into a puddle, slime keeps its shape, pistons shove goo about one block without launching it,
//! and goo clings to a retracting sticky piston.

use physicsbruhh_native::mesh;
use physicsbruhh_native::sim::*;

fn floor() -> Vec<f32> {
    let mut v = Vec::new();
    for x in -5..6 { for z in -3..4 { v.extend_from_slice(&[x as f32, -1.0, z as f32, x as f32 + 1.0, 0.0, z as f32 + 1.0, 1.0]); } }
    v
}
fn plate(id: i32, face: f32, dir: f32, vel: f32, stick: f32) -> [f32; 11] {
    let (a, b) = if dir > 0.0 { (face - 0.25, face) } else { (face, face + 0.25) };
    [id as f32, a, 0.0, 0.0, b, 1.0, 1.0, vel, 0.0, 0.0, stick]
}
fn run(w: &mut World, secs: f32) { for _ in 0..(secs * 60.0) as usize { w.step(DT + 1e-6); } }
fn all_finite_above_floor(w: &World) {
    for i in 0..w.maxp { if w.alive[i] != 0 { for c in 0..3 { assert!(w.p[i * 3 + c].is_finite()); } assert!(w.p[i * 3 + 1] > -0.01, "particle under the floor: {}", w.p[i * 3 + 1]); } }
}
fn centre_x(w: &World) -> f32 { let (mut s, mut n) = (0.0, 0.0); for i in 0..w.maxp { if w.alive[i] != 0 { s += w.p[i * 3]; n += 1.0; } } s / n }

#[test]
fn honey_melts_into_a_puddle() {
    let mut w = World::new(12, 4);
    let f = floor(); w.set_static(&f, f.len() / 7);
    assert_eq!(w.add_body(HONEY, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0), 0);
    run(&mut w, 1.0);
    let early = w.bodies[0].bb;
    assert!(early[4] > 0.7, "should still be block-like after 1 s, top = {}", early[4]);
    run(&mut w, 9.0);
    let bb = w.bodies[0].bb;
    assert!(bb[4] < 0.45, "should have flattened, top = {}", bb[4]);
    assert!(bb[3] - bb[0] > 1.5, "should have spread, width = {}", bb[3] - bb[0]);
    assert_eq!(w.bodies[0].count, 1728);
    all_finite_above_floor(&w);
    run(&mut w, 25.0);
    assert!(!w.bodies[0].awake, "a settled puddle must go to sleep");
}

#[test]
fn slime_keeps_its_shape_and_sleeps() {
    let mut w = World::new(12, 4);
    let f = floor(); w.set_static(&f, f.len() / 7);
    w.add_body(SLIME, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0);
    run(&mut w, 6.0);
    let bb = w.bodies[0].bb;
    assert!(bb[4] > 0.75 && bb[4] < 1.05, "top = {}", bb[4]);
    assert!(bb[3] - bb[0] < 1.15, "width = {}", bb[3] - bb[0]);
    assert!(!w.bodies[0].awake);
    all_finite_above_floor(&w);
}

#[test]
fn piston_pushes_slime_about_one_block_and_pulls_it_back() {
    let mut w = World::new(12, 4);
    let mut f = floor(); f.extend_from_slice(&[-2.0, 0.0, 0.0, -1.0, 1.0, 1.0, 1.0]);
    w.set_static(&f, f.len() / 7);
    w.add_body(SLIME, -1.0, 0.0, 0.0, 1.0, 0.0, 0.0);
    run(&mut w, 1.0);
    let x0 = centre_x(&w);
    for k in 0..6 { let face = -1.0 + (k as f32) / 6.0; w.set_kinematic(&plate(1, face, 1.0, 10.0, 3.0), 1); w.step(DT + 1e-6); }
    for _ in 0..90 { w.set_kinematic(&plate(1, 0.0, 1.0, 0.0, 3.0), 1); w.step(DT + 1e-6); }
    let x1 = centre_x(&w);
    assert!(x1 - x0 > 0.6 && x1 - x0 < 1.4, "pushed by {}", x1 - x0);
    let bb = w.bodies[0].bb;
    assert!(bb[3] < 2.2, "slime was launched: max x = {}", bb[3]);
    // sticky retraction drags it back
    for k in 0..6 { let face = 0.0 - (k as f32) / 6.0; w.set_kinematic(&plate(1, face, 1.0, -10.0, 3.0), 1); w.step(DT + 1e-6); }
    for _ in 0..120 { w.set_kinematic(&plate(1, -1.0, 1.0, 0.0, 3.0), 1); w.step(DT + 1e-6); }
    let x2 = centre_x(&w);
    assert!(x1 - x2 > 0.5, "sticky piston should pull the slime back, moved {}", x1 - x2);
    all_finite_above_floor(&w);
}

#[test]
fn honey_strings_between_retracting_pistons() {
    let mut w = World::new(12, 4);
    let mut f = floor(); f.extend_from_slice(&[-2.0, 0.0, 0.0, -1.0, 1.0, 1.0, 1.0]); f.extend_from_slice(&[2.0, 0.0, 0.0, 3.0, 1.0, 1.0, 1.0]);
    w.set_static(&f, f.len() / 7);
    w.add_body(HONEY, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0);
    let both = |a: f32, b: f32, v: f32| { let mut d = plate(1, a, 1.0, -v, 3.0).to_vec(); d.extend_from_slice(&plate(2, b, -1.0, v, 3.0)); d };
    for _ in 0..240 { w.set_kinematic(&both(0.0, 1.0, 0.0), 2); w.step(DT + 1e-6); }
    for k in 0..6 { let t = k as f32 / 6.0; w.set_kinematic(&both(-t, 1.0 + t, 10.0), 2); w.step(DT + 1e-6); }
    for _ in 0..6 { w.set_kinematic(&both(-1.0, 2.0, 0.0), 2); w.step(DT + 1e-6); }
    let (_, kin, _) = w.link_counts();
    assert!(kin > 40, "honey should stay stuck to the plates, anchors = {}", kin);
    let threads = w.long_springs(2.9 * w.s).len();
    assert!(threads > 50, "expected honey threads across the gap, got {}", threads);
    for _ in 0..600 { w.set_kinematic(&both(-1.0, 2.0, 0.0), 2); w.step(DT + 1e-6); }
    assert!(w.long_springs(0.5).len() < threads / 4, "threads should snap with time");
    all_finite_above_floor(&w);
}

#[test]
fn mesh_of_a_fresh_block_is_about_one_block() {
    let mut w = World::new(12, 4);
    let f = floor(); w.set_static(&f, f.len() / 7);
    w.add_body(SLIME, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0);
    w.step(DT + 1e-6);
    let mut out = Vec::new();
    let quads = mesh::build(&w, SLIME, &mut out);
    assert!(quads > 300 && quads < 4000, "quads = {}", quads);
    assert_eq!(out.len(), quads * mesh::FLOATS_PER_QUAD);
    let (mut lo, mut hi) = ([f32::MAX; 3], [f32::MIN; 3]);
    for v in out.chunks(6) { for c in 0..3 { assert!(v[c].is_finite()); lo[c] = lo[c].min(v[c]); hi[c] = hi[c].max(v[c]); }
        let nl = (v[3] * v[3] + v[4] * v[4] + v[5] * v[5]).sqrt(); assert!((nl - 1.0).abs() < 1e-3); }
    for c in 0..3 { let size = hi[c] - lo[c]; assert!(size > 0.9 && size < 1.1, "axis {} size {}", c, size); }
    // quads must wind counter-clockwise seen from outside, so the game's back-face culling keeps them
    let (mut good, mut bad) = (0, 0);
    for qd in out.chunks(24) {
        let v = |k: usize| [qd[k * 6], qd[k * 6 + 1], qd[k * 6 + 2]];
        let (a, b, d) = (v(0), v(1), v(3));
        let (e1, e2) = ([b[0] - a[0], b[1] - a[1], b[2] - a[2]], [d[0] - a[0], d[1] - a[1], d[2] - a[2]]);
        let n = [e1[1] * e2[2] - e1[2] * e2[1], e1[2] * e2[0] - e1[0] * e2[2], e1[0] * e2[1] - e1[1] * e2[0]];
        if n[0] * qd[3] + n[1] * qd[4] + n[2] * qd[5] > 0.0 { good += 1; } else { bad += 1; }
    }
    assert!(bad * 50 < good, "winding: {} outward, {} inward", good, bad);
    assert_eq!(mesh::build(&w, HONEY, &mut out), 0);
}

#[test]
fn old_honey_spawns_as_a_puddle_and_bodies_are_recycled() {
    let mut w = World::new(10, 2);
    let f = floor(); w.set_static(&f, f.len() / 7);
    assert_eq!(w.add_body(HONEY, 0.0, 0.0, 0.0, 1.0, 0.0, 100.0), 0);
    run(&mut w, 2.0);
    assert!(w.bodies[0].bb[4] < 0.45);
    assert_eq!(w.add_body(SLIME, 3.0, 0.0, 0.0, 1.0, 0.0, 0.0), 1);
    let third = w.add_body(SLIME, -3.0, 0.0, 0.0, 1.0, 0.0, 0.0);
    assert!(third == 0 || third == 1, "a full world must recycle a slot");
    run(&mut w, 1.0);
    all_finite_above_floor(&w);
}
