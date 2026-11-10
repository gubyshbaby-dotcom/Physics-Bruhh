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
/// How strongly the mod makes goo cling to a sticky piston's head (`GooClient.STICKY_HEAD`).
const STICKY: f32 = 8.0;

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
    for k in 0..6 { let face = -1.0 + (k as f32) / 6.0; w.set_kinematic(&plate(1, face, 1.0, 10.0, STICKY), 1); w.step(DT + 1e-6); }
    for _ in 0..90 { w.set_kinematic(&plate(1, 0.0, 1.0, 0.0, STICKY), 1); w.step(DT + 1e-6); }
    let x1 = centre_x(&w);
    assert!(x1 - x0 > 0.6 && x1 - x0 < 1.4, "pushed by {}", x1 - x0);
    let bb = w.bodies[0].bb;
    assert!(bb[3] < 2.2, "slime was launched: max x = {}", bb[3]);
    // sticky retraction drags it back
    for k in 0..6 { let face = 0.0 - (k as f32) / 6.0; w.set_kinematic(&plate(1, face, 1.0, -10.0, STICKY), 1); w.step(DT + 1e-6); }
    for _ in 0..120 { w.set_kinematic(&plate(1, -1.0, 1.0, 0.0, STICKY), 1); w.step(DT + 1e-6); }
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
    let both = |a: f32, b: f32, v: f32| { let mut d = plate(1, a, 1.0, -v, STICKY).to_vec(); d.extend_from_slice(&plate(2, b, -1.0, v, STICKY)); d };
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
    let data = [1.0, l - 0.25, 0.0, 0.0, l, 1.0, 1.0, -vel, 0.0, 0.0, STICKY, 1.0,
                2.0, r, 0.0, 0.0, r + 0.25, 1.0, 1.0, vel, 0.0, 0.0, STICKY, 2.0];
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
            let stick = if self.sticky { STICKY } else { 0.0 };
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

/// One piece is removed while its neighbour is still moving: the removed one's bounds stay sane
/// (the game reads them every tick to decide where to look for blocks and creatures) and it is gone
/// on time; the neighbour is not disturbed.
#[test]
fn goo_removed_next_to_moving_goo_keeps_sane_bounds() {
    let mut w = World::new(12, 4);
    let f = floor(); w.set_static(&f, f.len() / 7);
    w.add_body(SLIME, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0);
    w.add_body(HONEY, 2.0, 0.0, 0.0, 1.0, 0.0, 0.0);
    for _ in 0..90 { w.step(DT + 1e-6); }
    assert!(w.bodies[1].awake, "the honey is still melting");
    let before = w.bodies[0].bb;
    w.remove_body(0, true);
    let mut widths = Vec::new();
    for _ in 0..40 {
        w.step(DT + 1e-6);
        for b in 0..2 {
            if w.bodies[b].state == 0 { continue; }
            let bb = w.bodies[b].bb;
            assert!(bb.iter().all(|v| v.is_finite() && v.abs() < 100.0), "body {} has bounds {:?}", b, bb);
            assert!((0..3).all(|a| bb[a] <= bb[a + 3]), "body {} has inverted bounds {:?}", b, bb);
        }
        if w.bodies[0].state != 0 {
            let bb = w.bodies[0].bb;
            assert!((0..3).all(|a| bb[a] >= before[a] - 1e-3 && bb[a + 3] <= before[a + 3] + 1e-3), "it shrinks inside where it was: {:?} in {:?}", bb, before);
            widths.push(bb[3] - bb[0]);
        }
    }
    assert!(widths.len() > 10 && widths.windows(2).all(|p| p[1] < p[0]), "it only ever gets smaller: {:?}", widths);
    assert_eq!(w.bodies[0].state, 0, "gone after {:.2} s", 40.0 * DT);
    assert_eq!(w.bodies[1].state, 1);
    assert_eq!(w.bodies[1].count, 1728);
    all_finite_above_floor(&w);
}

/// How violently things move right after a block of honey appears where honey already lies
/// (`dx` = 1: next to the first block, its bottom layers inside the edge of the puddle; 0: right in the middle of it).
fn spawn_into_puddle(dx: f32) -> (f32, f32) {
    let mut w = World::new(12, 4);
    let f = floor(); w.set_static(&f, f.len() / 7);
    w.add_body(HONEY, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0);
    for _ in 0..420 { w.step(DT + 1e-6); } // seven seconds: a puddle about two blocks wide
    w.add_body(HONEY, dx, 0.0, 0.0, 1.0, 0.0, 0.0);
    let (mut top, mut fastest) = (0.0f32, 0.0f32);
    let mut prev: Vec<f32> = w.p.clone();
    for _ in 0..90 {
        w.step(DT + 1e-6);
        for i in 0..w.maxp { if w.alive[i] == 0 { continue; }
            let d = (0..3).map(|a| (w.p[i * 3 + a] - prev[i * 3 + a]).powi(2)).sum::<f32>().sqrt();
            fastest = fastest.max(d / DT); top = top.max(w.p[i * 3 + 1]);
        }
        prev.copy_from_slice(&w.p);
    }
    (top, fastest)
}

#[test]
fn honey_placed_into_honey_does_not_burst() {
    for dx in [1.0f32, 0.0] {
        let (top, fastest) = spawn_into_puddle(dx);
        if std::env::var("DIAG").is_ok() { println!("offset {}: highest particle {:.2}, fastest {:.2} blocks/s", dx, top, fastest); }
        assert!(top < 1.25, "offset {}: nothing is thrown upwards: highest particle at {:.2}", dx, top);
        assert!(fastest < 4.0, "offset {}: nothing shoots off: fastest particle {:.2} blocks/s", dx, fastest);
    }
}

/// A creature as the mod reports it: its box at the start of the tick and the velocity that carries it through the tick.
fn creature(feet: [f32; 3], vel_y: f32) -> [f32; 12] {
    [-7.0, feet[0] - 0.3, feet[1], feet[2] - 0.3, feet[0] + 0.3, feet[1] + 1.8, feet[2] + 0.3, 0.0, vel_y, 0.0, 1.0, 0.0]
}
/// Goo pressed against a player standing at `feet`, counted the way the mod does it (a little around the box and under it).
fn touching(w: &World, feet: [f32; 3]) -> (u32, u32) {
    w.probe([feet[0] - 0.52, feet[1] - 0.12, feet[2] - 0.52], [feet[0] + 0.52, feet[1] + 1.8, feet[2] + 0.52])
}

/// A player drops into a resting slime block at speed, goes straight through to the floor (goo is
/// not solid), and is thrown back up. The mod has to notice the slime on the way (or the player
/// would not bounce), nothing may be flung away, and the slime has to pull itself together again.
#[test]
fn player_falling_through_slime_is_noticed_and_the_slime_recovers() {
    let mut w = World::new(12, 4);
    let f = floor(); w.set_static(&f, f.len() / 7);
    w.add_body(SLIME, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0);
    run(&mut w, 3.0);
    assert!(!w.bodies[0].awake);
    let rest = w.bodies[0].bb;
    // heights of the feet tick by tick: down at 0.9 blocks a tick, two ticks on the floor, up again
    let mut path: Vec<f32> = Vec::new();
    let mut y = 3.3f32;
    while y > 0.0 { path.push(y); y -= 0.9; }
    path.extend_from_slice(&[0.0, 0.0, 0.0]);
    let mut y = 0.0f32;
    while y < 4.0 { y += 0.9; path.push(y); }
    let (mut noticed_on_the_way, mut noticed_on_the_floor, mut widest) = (0u32, 0u32, 0.0f32);
    for t in 0..path.len() - 1 {
        let (from, to) = (path[t], path[t + 1]);
        let kin = creature([0.5, from, 0.5], (to - from) * 20.0);
        w.set_static(&f, f.len() / 7);
        w.set_kinematic(&kin, 1);
        for _ in 0..3 { w.step(DT + 1e-6); }
        let (_, slime) = touching(&w, [0.5, to, 0.5]);
        if to > 0.0 && to < 1.0 && from > to { noticed_on_the_way = noticed_on_the_way.max(slime); }
        if to == 0.0 { noticed_on_the_floor = noticed_on_the_floor.max(slime); }
        let bb = w.bodies[0].bb;
        widest = widest.max(bb[3] - bb[0]).max(bb[5] - bb[2]).max(bb[4]);
        all_finite_above_floor(&w);
    }
    assert!(noticed_on_the_way >= 12 || noticed_on_the_floor >= 12, "slime around the player: {} on the way down, {} on the floor", noticed_on_the_way, noticed_on_the_floor);
    assert!(noticed_on_the_floor >= 12, "slime around the player standing in it: {}", noticed_on_the_floor);
    assert!(widest < 2.6, "nothing is flung away: {:.2}", widest);
    w.set_kinematic(&[], 0);
    run(&mut w, 6.0);
    let bb = w.bodies[0].bb;
    assert_eq!(w.bodies[0].count, 1728);
    assert!(bb[4] > 0.6 && bb[3] - bb[0] < 1.5 && bb[5] - bb[2] < 1.5, "the slime is a block again: {:?} (was {:?})", bb, rest);
    all_finite_above_floor(&w);
}

/// The player walks through a honey puddle: honey is shoved out of the way but stays pressed
/// against the legs, which is how the mod knows to slow the player down.
#[test]
fn player_wading_through_honey_is_noticed() {
    let mut w = World::new(12, 4);
    let f = floor(); w.set_static(&f, f.len() / 7);
    w.add_body(HONEY, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0);
    run(&mut w, 14.0);
    let bb = w.bodies[0].bb;
    let (cx, cz) = ((bb[0] + bb[3]) * 0.5, (bb[2] + bb[5]) * 0.5);
    // appears in the middle of the puddle (teleported: no velocity), stands for a second, then walks out along x at 0.1 blocks a tick
    let mut fewest_standing = u32::MAX;
    for _ in 0..20 {
        w.set_static(&f, f.len() / 7);
        w.set_kinematic(&creature([cx, 0.0, cz], 0.0), 1);
        for _ in 0..3 { w.step(DT + 1e-6); }
        fewest_standing = fewest_standing.min(touching(&w, [cx, 0.0, cz]).0);
    }
    assert!(fewest_standing >= 12, "honey around the player standing in the puddle: {}", fewest_standing);
    assert_eq!(inside(&w, [cx - 0.3, 0.0, cz - 0.3], [cx + 0.3, 1.8, cz + 0.3]), 0, "no honey is left inside the player");
    let mut x = cx; let mut seen = Vec::new();
    for _ in 0..8 {
        let mut k = creature([x, 0.0, cz], 0.0); k[7] = 2.0;
        w.set_static(&f, f.len() / 7);
        w.set_kinematic(&k, 1);
        for _ in 0..3 { w.step(DT + 1e-6); }
        x += 0.1;
        seen.push(touching(&w, [x, 0.0, cz]).0);
    }
    assert!(seen.iter().all(|&n| n >= 12), "honey around the player walking through it: {:?}", seen);
    all_finite_above_floor(&w);
    assert_eq!(w.bodies[0].count, 1728);
}

/// The game hands the core coordinates relative to the first piece of goo it saw, so the same
/// contraption can sit anywhere in the core's space. Whether slime stays stretched between two
/// sticky pistons must not depend on that (it did: at half of these places the band used to peel
/// off one piston a moment after the heads had come to rest).
#[test]
fn slime_band_holds_wherever_it_is_in_the_world() {
    for o in [[0.0f32, 0.0, 0.0], [4.0, 263.0, 3.0], [20.0, 263.0, 3.0], [13.0, 140.0, -7.0], [-3.0, 3.0, 3.0]] {
        let mut w = World::new(12, 4);
        let mut fl = Vec::new();
        for x in -5..6 { for z in -3..4 { fl.extend_from_slice(&[o[0] + x as f32, o[1] - 1.0, o[2] + z as f32, o[0] + x as f32 + 1.0, o[1], o[2] + z as f32 + 1.0, 1.0]); } }
        w.add_body(SLIME, o[0], o[1], o[2], 0.0, 1.0, 0.0);
        // heads followed the way the mod does it: half a block per tick, the base three quarters of a block until the head is in
        let mut shown = 0.0f32;
        for t in 0..200 {
            let next = if t >= 80 { (shown + 0.5).min(1.0) } else { 0.0 };
            let (mut kin, mut st) = (Vec::new(), fl.clone());
            for (id, base, dir) in [(1.0f32, o[0] - 2.0, 1.0f32), (3.0, o[0] + 2.0, -1.0)] {
                if shown < 1.0 || next < 1.0 {
                    let (v, off, head) = (-(next - shown) * 20.0 * dir, -dir * shown, base + dir);
                    let (plate, arm) = if dir > 0.0 { ((head + 0.75, head + 1.0), (head - 0.25, head + 0.75)) } else { ((head, head + 0.25), (head + 0.25, head + 1.25)) };
                    kin.extend_from_slice(&[id, plate.0 + off, o[1], o[2], plate.1 + off, o[1] + 1.0, o[2] + 1.0, v, 0.0, 0.0, STICKY, if dir > 0.0 { 1.0 } else { 2.0 }]);
                    kin.extend_from_slice(&[id + 1.0, arm.0 + off, o[1] + 0.375, o[2] + 0.375, arm.1 + off, o[1] + 0.625, o[2] + 0.625, v, 0.0, 0.0, STICKY, 0.0]);
                    if dir > 0.0 { st.extend_from_slice(&[base, o[1], o[2], base + 0.75, o[1] + 1.0, o[2] + 1.0, 2.0]); } else { st.extend_from_slice(&[base + 0.25, o[1], o[2], base + 1.0, o[1] + 1.0, o[2] + 1.0, 2.0]); }
                } else {
                    st.extend_from_slice(&[base, o[1], o[2], base + 1.0, o[1] + 1.0, o[2] + 1.0, 2.0]);
                }
            }
            shown = next;
            w.set_static(&st, st.len() / 7);
            w.set_kinematic(&kin, kin.len() / KIN_FLOATS);
            for _ in 0..3 { w.step(DT + 1e-6); }
        }
        let bb = w.bodies[0].bb;
        assert!(bb[0] - o[0] < -0.8 && bb[3] - o[0] > 1.8, "at {:?} the band spans {:.2}..{:.2}", o, bb[0] - o[0], bb[3] - o[0]);
    }
}

/// Particles of the world in the air (above `y`), and the furthest of them along x.
fn airborne(w: &World, y: f32) -> (usize, f32) {
    let (mut n, mut far) = (0, f32::MIN);
    for i in 0..w.maxp { if w.alive[i] != 0 && w.p[i * 3 + 1] > y { n += 1; far = far.max(w.p[i * 3]); } }
    (n, far)
}

/// A dispenser one block up squirts a bottle of honey sideways: it comes out as a stream over a
/// second or so, flies freely, and gathers into one puddle on the floor ahead. A second bottle
/// into the same piece flies just as fast, although the piece now lies on the ground.
#[test]
fn honey_stream_flies_and_gathers_into_a_puddle() {
    const BOTTLE: u32 = 432;
    let mut w = World::new(12, 4);
    let f = floor(); w.set_static(&f, f.len() / 7);
    let b = w.add_empty(HONEY, 0.0, 1.0, 0.0);
    assert_eq!(b, 0);
    assert_eq!(w.emit(0, [0.0, 1.5, 0.5], [3.5, 0.0, 0.0], BOTTLE, false), BOTTLE);
    assert_eq!(w.amount(0), BOTTLE, "all of it counts from the start, also what has not come out yet");
    run(&mut w, 0.25);
    let out = w.bodies[0].count;
    assert!(out > 40 && out < BOTTLE, "it comes out bit by bit: {} particles after a quarter of a second", out);
    let (in_air, front) = airborne(&w, 0.6);
    assert!(in_air > 30 && front > 0.6, "the stream is under way: {} in the air, front at {:.2}", in_air, front);
    run(&mut w, 6.0);
    assert_eq!(w.bodies[0].count, BOTTLE, "every particle came out");
    assert_eq!(w.bodies[0].pending, 0);
    let bb = w.bodies[0].bb;
    assert!(bb[4] < 0.45, "it lies flat: top at {:.2}", bb[4]);
    assert!(bb[0] > 0.2 && bb[3] < 4.5, "it landed ahead of the nozzle and stayed together: {:.2}..{:.2}", bb[0], bb[3]);
    assert_eq!(w.flying(0), 0, "nothing still counts as in flight");
    all_finite_above_floor(&w);
    run(&mut w, 10.0);
    assert!(!w.bodies[0].awake, "the puddle comes to rest");
    // the second bottle
    assert_eq!(w.emit(0, [0.0, 1.5, 0.5], [3.5, 0.0, 0.0], BOTTLE, false), BOTTLE);
    run(&mut w, 0.3);
    let (in_air, front) = airborne(&w, 0.6);
    assert!(in_air > 30 && front > 0.75, "the second stream is not slowed by the puddle it belongs to: {} in the air, front at {:.2}", in_air, front);
    run(&mut w, 6.0);
    assert_eq!(w.bodies[0].count, 2 * BOTTLE);
    all_finite_above_floor(&w);
    // a piece holds one block of goo, no more
    assert_eq!(w.emit(0, [0.0, 1.5, 0.5], [3.5, 0.0, 0.0], 5 * BOTTLE, false), 2 * BOTTLE);
    assert_eq!(w.emit(0, [0.0, 1.5, 0.5], [3.5, 0.0, 0.0], BOTTLE, false), 0);
}

/// A dispenser throws slime balls: each is a lump that flies, lands, and sticks to the lumps of
/// the same piece that are already there, so that in the end there is one wobbly heap.
#[test]
fn slime_lumps_fly_and_stick_together() {
    const BALL: u32 = 192;
    let mut w = World::new(12, 4);
    let f = floor(); w.set_static(&f, f.len() / 7);
    w.add_empty(SLIME, 0.0, 1.0, 0.0);
    for k in 0..3 {
        assert_eq!(w.emit(0, [0.3, 1.5, 0.5], [4.0, 0.5, 0.0], BALL, true), BALL);
        if k == 0 {
            run(&mut w, 0.1);
            let (in_air, front) = airborne(&w, 0.9);
            assert!(in_air > 150 && front > 0.6, "the lump flies: {} in the air, front at {:.2}", in_air, front);
            let (_, _, springs) = w.link_counts();
            assert!(springs > BALL * 3, "the lump is held together from the start: {} springs", springs);
        }
        run(&mut w, 0.35);
    }
    run(&mut w, 6.0);
    assert_eq!(w.bodies[0].count, 3 * BALL);
    let bb = w.bodies[0].bb;
    assert!(bb[3] - bb[0] < 1.6 && bb[5] - bb[2] < 1.6 && bb[4] < 0.9, "one heap, not a smear: {:?}", bb);
    assert!(bb[0] > 0.3, "it landed ahead of the nozzle: {:.2}", bb[0]);
    // one heap: pull at one end and the other end follows
    let (_, _, springs) = w.link_counts();
    assert!(springs > 3 * BALL * 4, "the lumps have grown together: {} springs", springs);
    assert!(!w.bodies[0].awake, "the heap comes to rest");
    all_finite_above_floor(&w);
}

/// Dipping a bottle into honey takes a bottle's worth from where the bottle was, and a piece that
/// has been emptied frees its slot.
#[test]
fn taking_goo_removes_that_much_from_that_place() {
    let mut w = World::new(12, 4);
    let f = floor(); w.set_static(&f, f.len() / 7);
    w.add_body(HONEY, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0);
    run(&mut w, 9.0);
    let bb = w.bodies[0].bb;
    let left_end = [bb[0], 0.1, (bb[2] + bb[5]) * 0.5];
    assert_eq!(w.take(0, left_end, 432), 432);
    assert_eq!(w.amount(0), 1728 - 432);
    w.step(DT + 1e-6);
    assert!(w.bodies[0].awake, "what is left flows into the gap");
    assert!(w.bodies[0].bb[0] > bb[0] + 0.15, "it was taken from the end the bottle was at: {:.2} -> {:.2}", bb[0], w.bodies[0].bb[0]);
    run(&mut w, 2.0);
    assert_eq!(w.bodies[0].count, 1728 - 432);
    all_finite_above_floor(&w);
    assert_eq!(w.take(0, left_end, 5000), 1728 - 432, "no more than there is");
    run(&mut w, 1.0);
    assert_eq!(w.bodies[0].state, 0, "an emptied piece is gone");
    let mut out = Vec::new();
    assert_eq!(mesh::build(&w, HONEY, &mut out), 0);
}

/// Goo that comes back into view is put down as it would lie by then: honey from two bottles as a
/// small puddle, three slime balls as a lump that holds together.
#[test]
fn goo_seen_again_is_put_down_at_rest() {
    let mut w = World::new(12, 4);
    let f = floor(); w.set_static(&f, f.len() / 7);
    assert_eq!(w.add_resting(HONEY, 0.0, 0.0, 0.0, 864), 0);
    assert_eq!(w.add_resting(SLIME, 3.0, 0.0, 0.0, 576), 1);
    assert_eq!((w.amount(0), w.amount(1)), (864, 576));
    run(&mut w, 4.0);
    let (honey, slime) = (w.bodies[0].bb, w.bodies[1].bb);
    assert!(honey[4] < 0.4 && honey[3] - honey[0] < 2.4 && honey[3] - honey[0] > 1.0, "a small puddle: {:?}", honey);
    assert!(slime[4] > 0.3 && slime[4] < 0.9 && slime[3] - slime[0] < 1.3, "a lump: {:?}", slime);
    assert_eq!((w.bodies[0].count, w.bodies[1].count), (864, 576));
    run(&mut w, 8.0);
    assert!(!w.bodies[0].awake && !w.bodies[1].awake);
    all_finite_above_floor(&w);
}
