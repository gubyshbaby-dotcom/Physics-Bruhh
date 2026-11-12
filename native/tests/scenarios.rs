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
    assert!(out > 12 && out < BOTTLE / 2, "it comes out bit by bit, as a thread at first: {} particles after a quarter of a second", out);
    let (in_air, front) = airborne(&w, 0.6);
    assert!(in_air > 10 && front > 0.6, "the stream is under way: {} in the air, front at {:.2}", in_air, front);
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

/// A stream of honey is not an even rod: it starts as a thread, runs fuller and thinner in turns,
/// sways a little, and ends in a thread again. Counted here by how many particles leave the spout
/// in each tenth of a second, and by how far the stream strays from its straight line.
#[test]
fn a_stream_of_honey_is_uneven() {
    const BOTTLE: u32 = 432;
    let mut w = World::new(12, 4);
    let f = floor(); w.set_static(&f, f.len() / 7);
    w.add_empty(HONEY, 0.0, 3.0, 0.0);
    assert_eq!(w.emit(0, [0.5, 3.5, 0.5], [0.0, -2.5, 0.0], BOTTLE, false), BOTTLE);
    let mut per_tenth: Vec<u32> = Vec::new();
    let (mut last, mut astray) = (0u32, 0.0f32);
    while w.bodies[0].pending > 0 && per_tenth.len() < 100 {
        run(&mut w, 0.1);
        let out = BOTTLE - w.bodies[0].pending;
        per_tenth.push(out - last);
        last = out;
        // sideways drift of what is still falling, well above the floor
        for i in 0..w.nb {
            if w.alive[i] == 0 || w.p[i * 3 + 1] < 1.0 { continue; }
            astray = astray.max((w.p[i * 3] - 0.5).abs().max((w.p[i * 3 + 2] - 0.5).abs()));
        }
    }
    let steady = &per_tenth[1..per_tenth.len() - 1];
    let (least, most) = (*steady.iter().min().unwrap(), *steady.iter().max().unwrap());
    assert!(per_tenth.len() >= 8, "a bottle takes its time to run out: {} tenths of a second", per_tenth.len());
    assert!(most >= least * 2 && most >= least + 8, "the stream runs fuller and thinner in turns: between {} and {} particles a tenth of a second ({:?})", least, most, per_tenth);
    assert!(per_tenth[0] < most && *per_tenth.last().unwrap() < most, "it starts and ends thin: {:?}", per_tenth);
    assert!(astray > 0.12 && astray < 0.8, "it sways, but stays a stream: up to {:.2} off its line", astray);
    run(&mut w, 8.0);
    assert_eq!(w.bodies[0].count, BOTTLE, "and all of it arrives");
    all_finite_above_floor(&w);
}

/// How thick a piece is, is its own: thin honey runs out wide and flat, thick honey is still a
/// heap after the same time; thin slime sags, thick slime keeps its shape and hardly wobbles.
/// A thickness of 1 is exactly the usual goo.
#[test]
fn each_piece_is_as_thick_as_it_was_made() {
    let spread = |mat: u8, thick: f32, secs: f32| -> (f32, f32) {
        let mut w = World::new(12, 2);
        let f = floor(); w.set_static(&f, f.len() / 7);
        assert_eq!(w.add_body_thick(mat, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, thick), 0);
        run(&mut w, secs);
        all_finite_above_floor(&w);
        let bb = w.bodies[0].bb;
        ((bb[3] - bb[0]).max(bb[5] - bb[2]), bb[4])
    };
    let (thin_w, thin_h) = spread(HONEY, 0.25, 8.0);
    let (usual_w, usual_h) = spread(HONEY, 1.0, 8.0);
    let (thick_w, thick_h) = spread(HONEY, 4.0, 8.0);
    assert!(thin_w > usual_w + 0.25 && thin_h < usual_h, "thin honey runs further and lies flatter: {:.2} wide, {:.2} high against {:.2}, {:.2}", thin_w, thin_h, usual_w, usual_h);
    assert!(thick_w < usual_w - 0.15 && thick_h > usual_h + 0.1, "thick honey is still a heap: {:.2} wide, {:.2} high against {:.2}, {:.2}", thick_w, thick_h, usual_w, usual_h);

    let (_, soft_h) = spread(SLIME, 0.25, 4.0);
    let (_, usual_slime_h) = spread(SLIME, 1.0, 4.0);
    let (_, stiff_h) = spread(SLIME, 4.0, 4.0);
    assert!(soft_h < usual_slime_h - 0.03, "thin slime sags: {:.2} high against {:.2}", soft_h, usual_slime_h);
    assert!(stiff_h > usual_slime_h + 0.01 && stiff_h < 1.1, "thick slime stands up: {:.2} high against {:.2}", stiff_h, usual_slime_h);

    // 1 is the usual goo, to the last bit
    let mut a = World::new(12, 2); let mut b = World::new(12, 2);
    let f = floor(); a.set_static(&f, f.len() / 7); b.set_static(&f, f.len() / 7);
    a.add_body(HONEY, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0);
    b.add_body_thick(HONEY, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 1.0);
    run(&mut a, 2.0); run(&mut b, 2.0);
    assert!(a.p == b.p, "thickness 1 changes nothing");
    // nonsense is taken as the usual, out-of-range values are brought into range
    assert_eq!(Material::honey().thick(f32::NAN).melt_time, Material::honey().melt_time);
    assert_eq!(Material::honey().thick(100.0).melt_time, Material::honey().thick(THICK_MAX).melt_time);
}

/// Fastest particle over the last step (blocks a second), from where they all were before it.
fn fastest(w: &World, before: &mut Vec<f32>) -> f32 {
    let mut m = 0.0f32;
    if before.len() == w.p.len() {
        for i in 0..w.maxp { if w.alive[i] != 0 { let o = i * 3; m = m.max(((w.p[o] - before[o]).powi(2) + (w.p[o + 1] - before[o + 1]).powi(2) + (w.p[o + 2] - before[o + 2]).powi(2)).sqrt() / DT); } }
    }
    *before = w.p.clone();
    m
}
fn inside_block(w: &World) -> usize { (0..w.maxp).filter(|&i| w.alive[i] != 0 && (0..3).all(|a| w.p[i * 3 + a] > 0.02 && w.p[i * 3 + a] < 0.98)).count() }

#[test]
fn a_block_put_into_goo_squeezes_it_out_and_none_of_it_goes_through_the_floor() {
    // a puddle of honey, and a cube of slime (the worst there is: all of it is where the block goes)
    for (mat, wait, top_speed) in [(HONEY, 10.0f32, 10.0f32), (SLIME, 4.0, f32::MAX)] {
        let mut w = World::new(12, 4);
        let f = floor(); w.set_static(&f, f.len() / 7);
        w.add_body(mat, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0);
        run(&mut w, wait);
        assert!(inside_block(&w) > 400, "the goo should be lying where the block is about to go: {}", inside_block(&w));
        let mut with = f.clone(); with.extend_from_slice(&[0.0, 0.0, 0.0, 1.0, 1.0, 1.0, 1.0]);
        w.set_static(&with, with.len() / 7);
        w.wake(0); // the mod wakes goo when a block next to it changes
        let (mut was, mut top) = (Vec::new(), 0.0f32);
        for step in 0..60 { if step % 3 == 0 { w.set_static(&with, with.len() / 7); } w.step(DT + 1e-6); top = top.max(fastest(&w, &mut was)); }
        assert_eq!(inside_block(&w), 0, "after a second no goo is left inside the block");
        run(&mut w, 3.0);
        assert_eq!(inside_block(&w), 0, "... and none goes back in");
        all_finite_above_floor(&w); // putting each particle on its nearest face sent the lowest ones down into the floor
        assert_eq!(w.bodies[0].count, 1728);
        assert!(top < top_speed, "squeezed out, not fired out: fastest particle {} blocks a second", top);
        let bb = w.bodies[0].bb;
        assert!(bb[3] - bb[0] < 3.0 && bb[5] - bb[2] < 3.0 && bb[4] < 1.3, "it stays around the block: {:?}", bb);
    }
}

#[test]
fn slime_born_inside_a_block_that_then_goes_away_is_not_blown_apart() {
    // A block placed by hand is still there in the player's own game for a tick or two after it has turned
    // into goo. The mod keeps that block out of the simulation; should one get in all the same, the goo has
    // to come through it in one piece rather than burst out of all six faces.
    for linger in [3usize, 6] {
        let mut w = World::new(12, 4);
        let f = floor();
        let mut with = f.clone(); with.extend_from_slice(&[0.0, 0.0, 0.0, 1.0, 1.0, 1.0, 2.0]);
        w.add_body(SLIME, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0);
        let (mut was, mut top) = (Vec::new(), 0.0f32);
        for step in 0..360 {
            if step % 3 == 0 { if step < linger { w.set_static(&with, with.len() / 7); } else { w.set_static(&f, f.len() / 7); } }
            w.step(DT + 1e-6);
            top = top.max(fastest(&w, &mut was));
        }
        let bb = w.bodies[0].bb;
        assert!(bb[3] - bb[0] < 1.45 && bb[5] - bb[2] < 1.45 && bb[4] > 0.62, "block there for {} steps: the slime ends up {} x {} wide, {} high", linger, bb[3] - bb[0], bb[5] - bb[2], bb[4]);
        assert!(top < 8.0, "block there for {} steps: fastest particle {} blocks a second", linger, top);
        all_finite_above_floor(&w);
    }
}

/// Honey that has been left hanging between two sticky pistons, six blocks above the ground, with the pistons going in and
/// out every two seconds. Returns, for every 100th step: particles on the ground, particles in the air between the pistons
/// and the ground, springs longer than half a block that run through that air.
fn dripping_from_pistons(steps: usize) -> Vec<(usize, usize, usize)> {
    let mut w = World::new(12, 4);
    let mut f = Vec::new();
    for x in -5..6 { for z in -3..4 { f.extend_from_slice(&[x as f32, -7.0, z as f32, x as f32 + 1.0, -6.0, z as f32 + 1.0, 1.0]); } }
    f.extend_from_slice(&[-2.0, 0.0, 0.0, -1.0, 1.0, 1.0, 1.0]); f.extend_from_slice(&[2.0, 0.0, 0.0, 3.0, 1.0, 1.0, 1.0]);
    // the block it was placed on is taken away once it has stuck to the piston heads
    let mut held = f.clone(); held.extend_from_slice(&[0.0, -1.0, 0.0, 1.0, 0.0, 1.0, 1.0]);
    w.set_static(&held, held.len() / 7);
    w.add_body(HONEY, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0);
    let both = |a: f32, b: f32, v: f32| { let mut d = plate(1, a, 1.0, -v, STICKY).to_vec(); d.extend_from_slice(&plate(2, b, -1.0, v, STICKY)); d };
    for _ in 0..240 { w.set_kinematic(&both(0.0, 1.0, 0.0), 2); w.step(DT + 1e-6); }
    w.set_static(&f, f.len() / 7); w.wake(0);
    let mut seen = Vec::new();
    for step in 0..steps {
        let (a, b, v) = match ((step / 60) % 4, step % 60) {
            (0, k) if k < 6 => (-(k as f32) / 6.0, 1.0 + k as f32 / 6.0, 10.0),
            (0, _) | (1, _) => (-1.0, 2.0, 0.0),
            (2, k) if k < 6 => (-1.0 + k as f32 / 6.0, 2.0 - k as f32 / 6.0, -10.0),
            _ => (0.0, 1.0, 0.0),
        };
        w.set_kinematic(&both(a, b, v), 2); w.step(DT + 1e-6);
        if step % 100 == 99 {
            let ground = (0..w.maxp).filter(|&i| w.alive[i] != 0 && w.p[i * 3 + 1] < -5.0).count();
            let air = (0..w.maxp).filter(|&i| w.alive[i] != 0 && w.p[i * 3 + 1] < -1.0 && w.p[i * 3 + 1] >= -5.0).count();
            let threads = w.long_springs(0.5).iter().filter(|(p, q)| p[1].min(q[1]) < -1.0 && p[1].max(q[1]) > -5.0).count();
            seen.push((ground, air, threads));
        }
    }
    for i in 0..w.maxp { if w.alive[i] != 0 { for c in 0..3 { assert!(w.p[i * 3 + c].is_finite()); } assert!(w.p[i * 3 + 1] > -6.01, "particle under the ground: {}", w.p[i * 3 + 1]); } }
    seen
}

#[test]
fn honey_dripping_from_between_pistons_falls_instead_of_hanging_in_the_air() {
    // Goo used to be as thick as its body all the way through whenever any of it stuck to anything, so what came off
    // the honey between the pistons sank through the air at a crawl, drawn out into a rope: after twenty seconds
    // none of it had reached the ground six blocks below.
    let seen = dripping_from_pistons(1000);
    let (ground, _, _) = seen[5];
    assert!(ground > 600, "ten seconds on, most of what will fall has fallen: {} particles on the ground ({:?})", ground, seen);
    let (_, air, threads) = seen[8];
    assert!(air < 100 && threads == 0, "fifteen seconds on nothing hangs in the air: {} particles, {} threads ({:?})", air, threads, seen);
}

#[test]
fn goo_lets_go_of_a_creature_that_no_longer_holds_it() {
    let mut w = World::new(12, 4);
    let f = floor(); w.set_static(&f, f.len() / 7);
    w.add_body(HONEY, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0);
    run(&mut w, 8.0);
    // someone standing in the puddle: the honey sticks to them
    let stands = |stick: f32| [-5.0f32, 0.2, 0.0, 0.2, 0.8, 1.8, 0.8, 0.0, 0.0, 0.0, stick, 0.0];
    for _ in 0..60 { w.set_kinematic(&stands(1.0), 1); w.wake(0); w.step(DT + 1e-6); }
    assert!(w.anchors_on(-5) > 20, "honey should stick to someone standing in it: {} particles", w.anchors_on(-5));
    // they get moving (or step into water, or shake themselves): the mod stops their box holding goo
    w.set_kinematic(&stands(0.0), 1);
    assert_eq!(w.anchors_on(-5), 0, "nothing stays stuck to a box that does not hold goo");
    for _ in 0..12 { w.set_kinematic(&stands(1.0), 1); w.step(DT + 1e-6); }
    assert!(w.anchors_on(-5) < 10, "... and what was let go does not stick again at once: {} particles", w.anchors_on(-5));
    for _ in 0..60 { w.set_kinematic(&stands(1.0), 1); w.wake(0); w.step(DT + 1e-6); }
    assert!(w.anchors_on(-5) > 20, "... but does once they stand in it again: {} particles", w.anchors_on(-5));
    all_finite_above_floor(&w);
}


/// Every bit of every position, velocity aside: two runs that agree here are the same run.
fn bits(w: &World) -> u64 {
    let mut sum = 0xcbf29ce484222325u64;
    for i in 0..w.maxp { if w.alive[i] != 0 { for c in 0..3 { sum = (sum ^ w.p[i * 3 + c].to_bits() as u64).wrapping_mul(0x100000001b3); } } }
    sum
}

/// Honey that runs together, slime, a stream poured in and a piston: a little of everything the solver does.
fn a_little_of_everything(w: &mut World) {
    let mut f = floor(); f.extend_from_slice(&[-2.0, 0.0, 0.0, -1.0, 1.0, 1.0, 1.0]);
    w.set_static(&f, f.len() / 7);
    w.add_body(SLIME, -1.0, 0.0, 0.0, 1.0, 0.0, 0.0);
    w.add_body(HONEY, 1.0, 0.0, 0.0, 1.0, 0.0, 0.0);
    w.add_body(HONEY, 2.0, 0.0, 0.0, 1.0, 0.0, 0.0);
    w.add_body(HONEY, 1.0, 0.0, 1.0, 1.0, 0.0, 0.0);
    let poured = w.add_empty(HONEY, 3.0, 1.0, -1.0);
    w.emit(poured as usize, [3.5, 2.5, -0.5], [0.0, -1.0, 0.0], 400, false);
    run(w, 0.8);
    for k in 0..6 { let face = -1.0 + (k as f32) / 6.0; w.set_kinematic(&plate(1, face, 1.0, 10.0, STICKY), 1); w.step(DT + 1e-6); }
    for _ in 0..30 { w.set_kinematic(&plate(1, 0.0, 1.0, 0.0, STICKY), 1); w.step(DT + 1e-6); }
}

#[test]
fn eight_neighbours_at_a_time_give_the_same_numbers_as_one_at_a_time() {
    if !wide_registers() { return; } // this machine only has the one way
    let (mut one, mut eight) = (World::new(12, 8), World::new(12, 8));
    one.wide = false;
    assert!(eight.wide);
    a_little_of_everything(&mut one);
    a_little_of_everything(&mut eight);
    assert_eq!(bits(&one), bits(&eight), "the two ways of working through the neighbours must agree to the last bit");
    all_finite_above_floor(&eight);
}

#[test]
fn a_world_makes_room_as_bodies_are_added() {
    // one with room for all of them from the start, one that begins with room for two
    let (mut whole, mut grown) = (World::new(12, 40), World::with_room(12, 2, 40));
    for w in [&mut whole, &mut grown] {
        let mut f = Vec::new();
        for x in -2..40 { for z in -2..3 { f.extend_from_slice(&[x as f32, -1.0, z as f32, x as f32 + 1.0, 0.0, z as f32 + 1.0, 1.0]); } }
        w.set_static(&f, f.len() / 7);
        for b in 0..30 {
            let slot = w.add_body(if b % 4 == 3 { SLIME } else { HONEY }, b as f32 * 1.25, 0.0, 0.0, 1.0, 0.0, 0.0);
            assert_eq!(slot, b, "every body gets a place of its own");
            if b % 6 == 5 { run(w, 0.1); }
        }
        run(w, 0.3);
        assert_eq!(w.bodies.iter().filter(|b| b.state == 1 && b.count == 1728).count(), 30, "all thirty are there, whole");
        all_finite_above_floor(w);
    }
    assert!(grown.slots >= 30 && grown.slots <= 40, "room was made: {} places", grown.slots);
    assert_eq!(bits(&whole), bits(&grown), "making room as it goes must not change what the goo does");
    // and once there are as many as there may be, the oldest makes room, as before
    for b in 30..41 { assert!(grown.add_body(HONEY, b as f32 * 1.25, 0.0, 0.0, 1.0, 0.0, 0.0) >= 0); }
    assert_eq!(grown.slots, 40);
}

#[test]
fn goo_in_places_far_apart_stands_on_its_own_ground() {
    // (the blocks around the goo used to be kept in a table 96 blocks wide at most: what lay further out fell through)
    let mut w = World::new(12, 4);
    let mut f = Vec::new();
    for at in [0.0f32, 700.0, -900.0] { for x in -2..3 { for z in -2..3 { f.extend_from_slice(&[at + x as f32, -1.0, at * 0.5 + z as f32, at + x as f32 + 1.0, 0.0, at * 0.5 + z as f32 + 1.0, 1.0]); } } }
    w.set_static(&f, f.len() / 7);
    for at in [0.0f32, 700.0, -900.0] { w.add_body(SLIME, at, 0.0, at * 0.5, 1.0, 0.0, 0.0); }
    run(&mut w, 1.5);
    all_finite_above_floor(&w);
    for b in 0..3 { assert_eq!(w.bodies[b].count, 1728, "piece {} is all there", b); assert!(w.bodies[b].bb[4] > 0.6, "piece {} stands: top at {}", b, w.bodies[b].bb[4]); }
}

#[test]
fn any_number_of_moving_boxes_and_only_the_near_ones_matter() {
    // a piston pushing slime, alone and among two hundred creatures that are nowhere near
    let push = |crowd: bool| {
        let mut w = World::new(12, 4);
        let mut f = floor(); f.extend_from_slice(&[-2.0, 0.0, 0.0, -1.0, 1.0, 1.0, 1.0]);
        w.set_static(&f, f.len() / 7);
        w.add_body(SLIME, -1.0, 0.0, 0.0, 1.0, 0.0, 0.0);
        run(&mut w, 0.5);
        let boxes = |face: f32, vel: f32| {
            let mut all = Vec::new();
            if crowd { for k in 0..200 { let x = 40.0 + k as f32; all.extend_from_slice(&[-(k as f32) - 10.0, x, 0.0, 0.2, x + 0.6, 1.8, 0.8, 0.3, 0.0, 0.0, 1.0, 0.0]); } }
            all.extend_from_slice(&plate(1, face, 1.0, vel, STICKY));
            all
        };
        for k in 0..6 { let all = boxes(-1.0 + (k as f32) / 6.0, 10.0); w.set_kinematic(&all, all.len() / 12); w.step(DT + 1e-6); }
        for _ in 0..40 { let all = boxes(0.0, 0.0); w.set_kinematic(&all, all.len() / 12); w.step(DT + 1e-6); }
        assert!(w.anchors_on(1) > 20, "the slime sticks to the piston's head: {} particles", w.anchors_on(1));
        (bits(&w), centre_x(&w))
    };
    let (alone, among) = (push(false), push(true));
    assert!(alone.1 > -0.2, "the piston pushed the slime: its middle is at {}", alone.1);
    assert_eq!(alone.0, among.0, "boxes that are nowhere near must make no difference");
}

#[test]
fn the_number_of_threads_can_be_changed_while_it_runs_and_changes_nothing() {
    let with = |n: usize| {
        assert_eq!(set_threads(n), n);
        let mut w = World::new(12, 8);
        a_little_of_everything(&mut w);
        bits(&w)
    };
    let (one, three, five) = (with(1), with(3), with(5));
    assert_eq!(one, three, "one thread and three must agree to the last bit");
    assert_eq!(one, five, "one thread and five must agree to the last bit");
    assert!(set_threads(0) >= 1);
}
