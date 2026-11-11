//! What the gas solver has to get right, whatever its numbers are tuned to.

use physicsbruhh_native::gas::{kind, Blob, Gas, Params, PARAM_COUNT};

const DT: f32 = 1.0 / 30.0;

/// A box with two layers of ground under it.
fn ground(bx: usize, by: usize, bz: usize, cpb: usize, ratio: usize) -> Gas {
    let mut g = Gas::new(bx, by, bz, cpb, ratio);
    for z in 0..bz { for x in 0..bx { for y in 0..2 { g.set_block(x, y, z, true, kind::NONE, 0.0); } } }
    g
}

fn run(g: &mut Gas, seconds: f32, blobs: &[Blob]) {
    for _ in 0..(seconds / DT).round() as usize { g.step(DT, blobs); }
}

/// Smoke in the box over a height (in blocks) and under it.
fn above_and_below(g: &Gas, height: f32) -> (f32, f32) {
    let fpb = (g.cpb * g.ratio) as f32;
    let (mut above, mut below) = (0.0, 0.0);
    for (y, &s) in g.smoke_by_height().iter().enumerate() { if (y as f32 + 0.5) / fpb > height { above += s; } else { below += s; } }
    (above, below)
}

fn all_finite(g: &Gas) -> bool {
    g.gas.iter().all(|v| v.iter().all(|x| x.is_finite())) && g.vel.iter().all(|v| v.iter().all(|x| x.is_finite())) && g.p.iter().all(|x| x.is_finite())
}

#[test]
fn nothing_comes_of_nothing() {
    let mut g = ground(8, 8, 8, 2, 2);
    run(&mut g, 2.0, &[]);
    let t = g.totals();
    assert_eq!(t, [0.0; 4], "a box with no source in it stays empty and still");
}

#[test]
fn smoke_from_a_campfire_rises() {
    let mut g = ground(10, 16, 10, 2, 2);
    g.set_block(5, 2, 5, false, kind::CAMPFIRE, 1.0);
    run(&mut g, 8.0, &[]);
    assert!(all_finite(&g));
    let t = g.totals();
    assert!(t[0] > 0.5, "a campfire makes smoke: {:?}", t);
    let (above, below) = above_and_below(&g, 6.0);
    assert!(above > 0.2 * (above + below), "after eight seconds a good part of the smoke is more than three blocks over the fire: {} above, {} below", above, below);
    assert!(t[3] > 1.0 && t[3] < 25.0, "the gas moves at a believable speed: {} blocks/s", t[3]);
    // none of it in the ground
    let fpb = g.cpb * g.ratio;
    for y in 0..2 * fpb { for i in 0..g.fx * g.fz { assert_eq!(g.gas[y * g.fx * g.fz + i], [0.0; 4], "no gas inside a block"); } }
}

#[test]
fn a_fire_is_hot_while_it_burns_and_cold_soon_after() {
    let mut g = ground(8, 12, 8, 2, 2);
    g.set_block(4, 2, 4, false, kind::FIRE, 1.0);
    run(&mut g, 3.0, &[]);
    let burning = g.totals();
    assert!(burning[2] > 0.6, "a burning block of fire is at flame heat: hottest {}", burning[2]);
    g.set_block(4, 2, 4, false, kind::NONE, 0.0);
    run(&mut g, 3.0, &[]);
    let after = g.totals();
    assert!(after[2] < 0.4, "three seconds after the fire is put out nothing is hot enough to glow any more: hottest {}", after[2]);
    run(&mut g, 40.0, &[]);
    let late = g.totals();
    assert!(late[0] < 0.1 * after[0].max(0.01) + 0.01, "the smoke thins out and leaves: {} then, {} forty seconds later", after[0], late[0]);
}

#[test]
fn smoke_goes_round_a_roof_not_through_it() {
    let mut g = ground(12, 14, 12, 2, 2);
    g.set_block(6, 2, 6, false, kind::CAMPFIRE, 1.0);
    for z in 3..10 { for x in 3..10 { g.set_block(x, 6, z, true, kind::NONE, 0.0); } }
    run(&mut g, 25.0, &[]);
    assert!(all_finite(&g));
    let fpb = g.cpb * g.ratio;
    // nothing inside the roof
    for y in 6 * fpb..7 * fpb { for z in 3 * fpb..10 * fpb { for x in 3 * fpb..10 * fpb { assert_eq!(g.gas[g.fine(x, y, z)][0], 0.0, "no smoke inside the roof"); } } }
    // under the roof the smoke has spread sideways: there is smoke well away from the fire's own column
    let (mut near, mut wide) = (0.0f32, 0.0f32);
    for y in 4 * fpb..6 * fpb { for z in 3 * fpb..10 * fpb { for x in 3 * fpb..10 * fpb {
        let s = g.gas[g.fine(x, y, z)][0];
        let (dx, dz) = (x as f32 / fpb as f32 - 6.5, z as f32 / fpb as f32 - 6.5);
        if dx * dx + dz * dz < 1.0 { near += s; } else { wide += s; }
    } } }
    assert!(wide > near, "smoke under a roof spreads out along it: {} in the column, {} around it", near, wide);
    // and right over the middle of the roof there is next to none, while it does come up past the edges
    let mut over_middle = 0.0f32;
    for y in 7 * fpb..9 * fpb { for z in 5 * fpb..8 * fpb { for x in 5 * fpb..8 * fpb { over_middle += g.gas[g.fine(x, y, z)][0]; } } }
    let (over_all, _) = above_and_below(&g, 7.0);
    assert!(over_all > 0.02, "smoke gets out from under the roof and rises past it: {}", over_all);
    let over_middle = over_middle / ((fpb * fpb * fpb) as f32);
    assert!(over_middle < 0.35 * over_all, "what is over the roof came round its edges: {} over the middle of {} in all", over_middle, over_all);
}

#[test]
fn an_explosion_throws_the_gas_outwards() {
    let mut g = ground(14, 14, 14, 2, 2);
    let centre = [7.0, 5.0, 7.0];
    let blast = [Blob { pos: centre, radius: 2.0, fuel: 14.0, smoke: 6.0, heat: 1.4, soul: 0.0, vel: [0.0; 3], expand: 60.0 }];
    run(&mut g, 0.1, &blast);
    assert!(all_finite(&g));
    // the air a little outside the ball moves away from its middle, on every side
    let h = 1.0 / g.cpb as f32;
    let (mut out, mut cells) = (0.0f32, 0);
    for y in 0..g.ny { for z in 0..g.nz { for x in 0..g.nx {
        let d = [(x as f32 + 0.5) * h - centre[0], (y as f32 + 0.5) * h - centre[1], (z as f32 + 0.5) * h - centre[2]];
        let r = (d[0] * d[0] + d[1] * d[1] + d[2] * d[2]).sqrt();
        if r > 2.0 && r < 3.0 {
            let v = g.vel[g.coarse(x, y, z)];
            out += (v[0] * d[0] + v[1] * d[1] + v[2] * d[2]) / r;
            cells += 1;
        }
    } } }
    assert!(out / cells as f32 > 1.0, "an explosion pushes the air away from itself: {} blocks/s outwards on average", out / cells as f32);
    let hot = g.totals();
    assert!(hot[2] > 0.8, "a fireball: hottest {}", hot[2]);
    run(&mut g, 4.0, &[]);
    assert!(all_finite(&g));
    let later = g.totals();
    assert!(later[2] < 0.3, "the fireball has burnt out: hottest {}", later[2]);
    assert!(later[0] > 0.3, "and left a cloud of smoke: {}", later[0]);
    let (above, below) = above_and_below(&g, 6.0);
    assert!(above > below * 0.5, "which has risen: {} above the place, {} below", above, below);
}

#[test]
fn the_box_can_move_and_the_gas_stays_where_it_is() {
    let mut g = ground(12, 12, 12, 2, 2);
    g.set_block(6, 2, 6, false, kind::CAMPFIRE, 1.0);
    run(&mut g, 4.0, &[]);
    let fpb = (g.cpb * g.ratio) as f32;
    let middle = |g: &Gas| {
        let (mut sx, mut sz, mut m) = (0.0f64, 0.0f64, 0.0f64);
        for y in 0..g.fy { for z in 0..g.fz { for x in 0..g.fx { let s = g.gas[g.fine(x, y, z)][0] as f64; sx += s * x as f64; sz += s * z as f64; m += s; } } }
        ((sx / m) as f32 / fpb, (sz / m) as f32 / fpb, m as f32)
    };
    let before = middle(&g);
    g.shift(2, 0, -1);
    let after = middle(&g);
    assert!((after.0 - (before.0 - 2.0)).abs() < 0.05 && (after.1 - (before.1 + 1.0)).abs() < 0.05,
        "the box went two blocks east and one north, so the smoke is two blocks further west in it and one further south: {:?} then {:?}", before, after);
    assert!(after.2 > 0.9 * before.2, "and nearly all of it is still in the box: {} of {}", after.2, before.2);
    g.shift(100, 0, 0);
    assert_eq!(g.totals()[0], 0.0, "a box moved right away from its gas is empty");
}

#[test]
fn soul_fire_burns_blue_and_stays_blue() {
    let mut g = ground(12, 10, 8, 2, 2);
    g.set_block(3, 2, 4, false, kind::FIRE, 1.0);
    g.set_block(8, 2, 4, false, kind::FIRE | kind::SOUL, 1.0);
    run(&mut g, 3.0, &[]);
    let fpb = g.cpb * g.ratio;
    let blue_over = |bx: usize| {
        let (mut blue, mut heat) = (0.0f32, 0.0f32);
        for y in 2 * fpb..5 * fpb { for z in 4 * fpb..5 * fpb { for x in bx * fpb..(bx + 1) * fpb { let v = g.gas[g.fine(x, y, z)]; blue += v[3]; heat += v[1] + v[2]; } } }
        (blue / heat.max(1.0e-6), heat)
    };
    let (plain, plain_heat) = blue_over(3);
    let (soul, soul_heat) = blue_over(8);
    assert!(plain_heat > 1.0 && soul_heat > 1.0, "both burn: {} and {}", plain_heat, soul_heat);
    assert!(plain < 0.1, "an ordinary flame is not blue: {}", plain);
    assert!(soul > 0.8, "a soul flame is: {}", soul);
}

#[test]
fn a_big_fire_in_a_closed_room_stays_sane() {
    let mut g = ground(10, 8, 10, 2, 2);
    for y in 2..8 { for i in 0..10 { g.set_block(i, y, 0, true, 0, 0.0); g.set_block(i, y, 9, true, 0, 0.0); g.set_block(0, y, i, true, 0, 0.0); g.set_block(9, y, i, true, 0, 0.0); } }
    for z in 0..10 { for x in 0..10 { g.set_block(x, 7, z, true, 0, 0.0); } }
    for z in 3..7 { for x in 3..7 { g.set_block(x, 2, z, false, kind::FIRE, 2.0); } }
    for step in 0..(20.0 / DT) as usize {
        g.step(DT, &[]);
        if step % 60 == 0 { assert!(all_finite(&g), "step {}", step); }
    }
    let t = g.totals();
    let pr = Params::default();
    assert!(t[2] <= pr.most_heat && t[3] < 60.0, "sixteen burning blocks in a sealed room: hottest {}, fastest {} blocks/s", t[2], t[3]);
    assert!(g.gas.iter().all(|v| v[0] <= pr.most_smoke && v[2] <= pr.most_fuel && v[0] >= 0.0 && v[1] >= 0.0 && v[2] >= 0.0));
}

#[test]
fn the_picture_of_the_grid_holds_what_the_grid_holds() {
    let mut g = ground(6, 6, 6, 2, 2);
    g.set_block(3, 2, 3, false, kind::FIRE, 1.0);
    run(&mut g, 1.5, &[]);
    let tiles = 5;
    let rows = (g.fy + tiles - 1) / tiles;
    let width = tiles * g.fx;
    let mut out = vec![0u8; width * rows * g.fz * 4];
    g.write_atlas(&mut out, tiles);
    let pr = g.params;
    let mut seen = 0;
    for y in 0..g.fy { for z in 0..g.fz { for x in 0..g.fx {
        let v = g.gas[g.fine(x, y, z)];
        let o = (((y / tiles) * g.fz + z) * width + (y % tiles) * g.fx + x) * 4;
        let smoke = (out[o] as f32 / 255.0).powi(2) * pr.most_smoke;
        let heat = out[o + 1] as f32 / 255.0 * pr.most_heat;
        assert!((smoke - v[0]).abs() < 0.04 * (v[0] + 0.05) + 0.004 && (heat - v[1]).abs() < 0.004, "cell {} {} {}: {:?} written as smoke {} heat {}", x, y, z, v, smoke, heat);
        if v[1] > 0.3 { seen += 1; }
    } } }
    assert!(seen > 20, "the fire is in the picture");
}

#[test]
fn the_numbers_go_to_the_game_and_back_unchanged() {
    let p = Params::default();
    let a = p.to_array();
    assert_eq!(a.len(), PARAM_COUNT);
    assert_eq!(Params::from_slice(&a), Some(p));
    assert_eq!(Params::from_slice(&a[..PARAM_COUNT - 1]), None);
}
