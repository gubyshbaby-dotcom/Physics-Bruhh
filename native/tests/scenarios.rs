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
fn plate(id: i32, face: f32, dir: f32, vel: f32, stick: f32) -> [f32; 12] {
    let (a, b) = if dir > 0.0 { (face - 0.25, face) } else { (face, face + 0.25) };
    [id as f32, a, 0.0, 0.0, b, 1.0, 1.0, vel, 0.0, 0.0, stick, if dir > 0.0 { 1.0 } else { 2.0 }]
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
    let mut deep = 0.0f32;
    for v in out.chunks(6) { for c in 0..3 { assert!(v[c].is_finite()); lo[c] = lo[c].min(v[c]); hi[c] = hi[c].max(v[c]); }
        // normal length = 1 + depth of goo behind the vertex (measured up to 16 grid cells, about 1.3 blocks)
        let nl = (v[3] * v[3] + v[4] * v[4] + v[5] * v[5]).sqrt(); assert!(nl > 0.999 && nl < 2.35, "normal length {}", nl); deep = deep.max(nl - 1.0); }
    assert!(deep > 0.6, "deepest point of a full block reads as {} blocks", deep);
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

/// The way the mod reports a piston head: once per game tick (three steps), where it was at the
/// start of the tick plus the velocity that carries it through the tick. At rest the head is still
/// reported (not moving); once it has fully retracted it is gone.
fn game_heads(w: &mut World, step: usize, retract_at: usize) {
    if step % 3 != 0 { return; }
    let tick = step as i32 / 3 - retract_at as i32 / 3;
    if tick >= 2 { w.set_kinematic(&[], 0); return; }
    let (travel, vel) = match tick { i32::MIN..=-1 => (0.0, 0.0), 0 => (0.0, 10.0), _ => (0.5, 10.0) };
    let (l, r) = (0.0 - travel, 1.0 + travel); // faces of the left and right head
    let data = [1.0, l - 0.25, 0.0, 0.0, l, 1.0, 1.0, -vel, 0.0, 0.0, 3.0, 1.0,
                2.0, r, 0.0, 0.0, r + 0.25, 1.0, 1.0, vel, 0.0, 0.0, 3.0, 2.0];
    w.set_kinematic(&data, 2);
}

#[test]
fn slime_stays_stretched_between_sticky_heads_reported_at_game_rate() {
    let mut w = World::new(12, 4);
    let mut f = floor(); f.extend_from_slice(&[-2.0, 0.0, 0.0, -1.0, 1.0, 1.0, 1.0]); f.extend_from_slice(&[2.0, 0.0, 0.0, 3.0, 1.0, 1.0, 1.0]);
    w.set_static(&f, f.len() / 7);
    w.add_body(SLIME, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0);
    let retract_at = 240;
    for step in 0..retract_at { game_heads(&mut w, step, retract_at); w.step(DT + 1e-6); }
    let (_, on_heads, _) = w.link_counts();
    assert!(on_heads > 60, "slime grips the resting heads: {} particles", on_heads);
    let mut max_awake_width = 0.0f32;
    for step in retract_at..retract_at + 240 {
        game_heads(&mut w, step, retract_at); w.step(DT + 1e-6);
        max_awake_width = max_awake_width.max(w.bodies[0].bb[3] - w.bodies[0].bb[0]);
    }
    let bb = w.bodies[0].bb;
    assert!(bb[3] - bb[0] > 2.5, "slime ends up spanning the gap: {:.2}..{:.2}", bb[0], bb[3]);
    let (held, on_gone_heads, _) = w.link_counts();
    assert_eq!(on_gone_heads, 0, "heads are gone, nothing can still be attached to them");
    assert!(held > 100, "the band is held at both ends: {} anchors", held);
    assert!(max_awake_width < 3.3, "nothing was flung away: {}", max_awake_width);
}

#[test]
fn honey_strings_between_sticky_heads_reported_at_game_rate() {
    let mut w = World::new(12, 4);
    let mut f = floor(); f.extend_from_slice(&[-2.0, 0.0, 0.0, -1.0, 1.0, 1.0, 1.0]); f.extend_from_slice(&[2.0, 0.0, 0.0, 3.0, 1.0, 1.0, 1.0]);
    w.set_static(&f, f.len() / 7);
    w.add_body(HONEY, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0);
    let retract_at = 240;
    for step in 0..retract_at { game_heads(&mut w, step, retract_at); w.step(DT + 1e-6); }
    let mut strings = 0usize;
    for step in retract_at..retract_at + 30 { game_heads(&mut w, step, retract_at); w.step(DT + 1e-6); strings = strings.max(w.long_springs(3.0 * w.s).len()); }
    assert!(strings > 40, "honey pulls into strings: {}", strings);
    let bb = w.bodies[0].bb;
    assert!(bb[0] < -0.6 && bb[3] > 1.6, "honey went with both heads: {:.2}..{:.2}", bb[0], bb[3]);
}

/// A piston the way the mod presents it to the core: the head is followed at half a block per
/// tick, the base is a low slab while the head is out or moving and a full block otherwise.
struct Piston { id: i32, base: f32, dir: f32, sticky: bool, shown: f32, target: f32 }

impl Piston {
    /// Advances one game tick; appends this tick's moving boxes and static boxes.
    fn tick(&mut self, kin: &mut Vec<f32>, st: &mut Vec<f32>) {
        let next = self.shown + (self.target - self.shown).clamp(-0.5, 0.5);
        let (b0, b1) = (self.base, self.base + 1.0);
        if self.shown < 1.0 || next < 1.0 {
            let v = -(next - self.shown) * 20.0 * self.dir;
            let off = -self.dir * self.shown;
            let head = self.base + self.dir; // cell the extended head occupies
            let (plate, arm) = if self.dir > 0.0 { ((head + 0.75, head + 1.0), (head - 0.25, head + 0.75)) } else { ((head, head + 0.25), (head + 0.25, head + 1.25)) };
            let stick = if self.sticky { 3.0 } else { 0.6 };
            kin.extend_from_slice(&[self.id as f32, plate.0 + off, 0.0, 0.0, plate.1 + off, 1.0, 1.0, v, 0.0, 0.0, stick, if self.dir > 0.0 { 1.0 } else { 2.0 }]);
            kin.extend_from_slice(&[(self.id + 1) as f32, arm.0 + off, 0.375, 0.375, arm.1 + off, 0.625, 0.625, v, 0.0, 0.0, stick, 0.0]);
            // the base without its head: three quarters of a block
            if self.dir > 0.0 { st.extend_from_slice(&[b0, 0.0, 0.0, b0 + 0.75, 1.0, 1.0, 2.0]); } else { st.extend_from_slice(&[b0 + 0.25, 0.0, 0.0, b1, 1.0, 1.0, 2.0]); }
        } else {
            st.extend_from_slice(&[b0, 0.0, 0.0, b1, 1.0, 1.0, 2.0]);
        }
        self.shown = next;
    }
}

/// `steps_per_tick` is 3 when the simulation keeps up with the game; fewer when the machine is too slow.
fn run_pistons(w: &mut World, pistons: &mut [Piston], ticks: usize, steps_per_tick: usize, mut script: impl FnMut(usize, &mut [Piston])) {
    let fl = floor();
    for tick in 0..ticks {
        script(tick, pistons);
        let (mut kin, mut st) = (Vec::new(), fl.clone());
        for p in pistons.iter_mut() { p.tick(&mut kin, &mut st); }
        w.set_static(&st, st.len() / 7);
        w.set_kinematic(&kin, kin.len() / KIN_FLOATS);
        for _ in 0..steps_per_tick { w.step(DT + 1e-6); }
    }
}

/// Particles sitting inside the given box (shrunk a little, so resting against it does not count).
fn inside(w: &World, min: [f32; 3], max: [f32; 3]) -> usize {
    (0..w.maxp).filter(|&i| w.alive[i] != 0 && (0..3).all(|a| w.p[i * 3 + a] > min[a] + 0.03 && w.p[i * 3 + a] < max[a] - 0.03)).count()
}

#[test]
fn rapid_pistons_do_not_swallow_goo() {
    // also with a simulation that cannot keep up (one or two steps per game tick instead of three):
    // the head then moves in jumps as far as the goo is concerned, and it must still stay in front of it
    for steps_per_tick in [3usize, 2, 1] {
        for (mat, sticky) in [(HONEY, true), (HONEY, false), (SLIME, true), (SLIME, false)] {
            let mut w = World::new(12, 4);
            w.add_body(mat, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0);
            let mut pistons = [Piston { id: 1, base: -2.0, dir: 1.0, sticky, shown: 1.0, target: 1.0 }];
            // jerk it: out and back every few ticks, at uneven intervals, for five seconds; end with the head out
            run_pistons(&mut w, &mut pistons, 100, steps_per_tick, |tick, p| { if tick % 7 == 0 || tick % 11 == 3 { p[0].target = 1.0 - p[0].target; } });
            run_pistons(&mut w, &mut pistons, 12, steps_per_tick, |_, p| p[0].target = 0.0);
            let in_base = inside(&w, [-2.0, 0.0, 0.0], [-1.25, 1.0, 1.0]);
            let behind_plate = inside(&w, [-1.25, 0.0, 0.0], [-0.25, 1.0, 1.0]);
            let below = inside(&w, [-9.0, -1.0, -9.0], [9.0, 0.0, 9.0]);
            if std::env::var("DIAG").is_ok() { println!("steps {} mat {} sticky {}: in base {}, behind the plate {}, under the floor {}", steps_per_tick, mat, sticky, in_base, behind_plate, below); }
            // a few particles (of 1728) may wrap round the edge of the plate; a little more honey may creep round its sides
            let allowed = if mat == SLIME { 12 } else { 60 };
            assert!(in_base == 0 && below == 0 && behind_plate <= allowed,
                "{} steps per tick, material {}, sticky {}: {} particles inside the base, {} behind the head plate, {} under the floor", steps_per_tick, mat, sticky, in_base, behind_plate, below);
        }
    }
}

#[test]
fn removed_goo_shrinks_away_completely() {
    let mut w = World::new(12, 4);
    let f = floor(); w.set_static(&f, f.len() / 7);
    w.add_body(HONEY, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0);
    w.add_body(SLIME, 3.0, 0.0, 0.0, 1.0, 0.0, 0.0);
    for _ in 0..300 { w.step(DT + 1e-6); }
    let mut out = Vec::new();
    let full = mesh::build(&w, HONEY, &mut out);
    w.remove_body(0, true); w.remove_body(1, true);
    let mut widths = Vec::new();
    for step in 0..40 {
        w.step(DT + 1e-6);
        if step == 12 { out.clear(); let half = mesh::build(&w, HONEY, &mut out); assert!(half > 0 && half < full, "shrinking: {} of {} quads", half, full); }
        if w.bodies[0].state != 0 { widths.push(w.bodies[0].bb[3] - w.bodies[0].bb[0]); }
    }
    assert!(widths.windows(2).all(|p| p[1] < p[0]), "it only ever gets smaller");
    assert!(w.bodies[0].state == 0 && w.bodies[1].state == 0, "both are gone after {:.2} s", 40.0 * DT);
    assert_eq!(w.awake_count(), 0);
    out.clear();
    assert_eq!(mesh::build(&w, HONEY, &mut out) + mesh::build(&w, SLIME, &mut out), 0);
}
