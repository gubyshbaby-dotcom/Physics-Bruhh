//! Lava let out of a pocket in a wall: writes its particles at a few moments (x, y, z, size and
//! what `lava_bits` says; little-endian floats) for looking at how it moves and how it is drawn
//! outside the game: `cargo run --release --example lava -- <out prefix> [scene] [seconds,seconds,...]`
//! Scenes: `pocket` (a block of lava behind a wall, two blocks above the floor, open to one side),
//! `floor` (a block of lava set down in the open), `lake` (a row of blocks let out one after another).
use physicsbruhh_native::sim::*;
use std::io::Write;

fn main() {
    let args: Vec<String> = std::env::args().collect();
    let out = args.get(1).cloned().unwrap_or_else(|| "lava".into());
    let scene = args.get(2).cloned().unwrap_or_else(|| "pocket".into());
    let times: Vec<f32> = args.get(3).map(|s| s.split(',').filter_map(|t| t.parse().ok()).collect()).unwrap_or_else(|| vec![1.0, 2.0, 4.0, 6.0, 9.0, 12.0, 16.0, 20.0]);
    let mut w = World::new(12, 16);
    // LAVA="damp_melted=9,plast1=20": tries other numbers for the material without rebuilding
    if let Ok(set) = std::env::var("LAVA") {
        let m = &mut w.mats[LAVA as usize];
        for pair in set.split(',') {
            let mut kv = pair.split('=');
            let (k, v) = (kv.next().unwrap_or(""), kv.next().and_then(|v| v.parse::<f32>().ok()).unwrap_or(0.0));
            match k {
                "melt_time" => m.melt_time = v, "damp_fresh" => m.damp_fresh = v, "damp_melted" => m.damp_melted = v,
                "spring_k0" => m.spring_k0 = v, "spring_k1" => m.spring_k1 = v, "plast0" => m.plast0 = v, "plast1" => m.plast1 = v,
                "yield" => m.yield_ = v, "coh_k" => m.coh_k = v, "coh_lim" => m.coh_lim = v, "xsph" => m.xsph = v,
                "adh_creep" => m.adh_creep = v, "adh_break" => m.adh_break = v, "adh_k" => m.adh_k = v, "set_after" => m.set_after = v, "set_rate" => m.set_rate = v,
                "sleep_v" => m.sleep_v = v, "break_strain" => m.break_strain = v, "spring_max_l" => m.spring_max_l = v,
                _ => println!("unknown: {}", k),
            }
        }
    }
    let mut st: Vec<f32> = Vec::new();
    let mut solid = |x: i32, y: i32, z: i32| st.extend_from_slice(&[x as f32, y as f32, z as f32, x as f32 + 1.0, y as f32 + 1.0, z as f32 + 1.0, 1.0]);
    for x in -4..9 { for z in -4..5 { solid(x, -1, z); } }
    let mut later: Vec<(usize, [f32; 3])> = Vec::new();
    let mut st_remove: Option<[f32; 3]> = None;
    match scene.as_str() {
        "floor" => { w.add_body(LAVA, 2.0, 0.0, 0.0, 1.0, 0.0, 0.0); }
        "lake" => {
            // a wall with a tunnel through it at floor height, three blocks of lava behind one another in the tunnel
            for y in 0..4 { for z in -4..5 { for x in -4..0 { if !(y == 0 && z == 0) { solid(x, y, z); } } } }
            solid(-4, 0, 0);
            w.add_body(LAVA, -1.0, 0.0, 0.0, 1.0, 0.0, 0.0);
            later.push((90, [-2.0, 0.0, 0.0]));
            later.push((180, [-3.0, 0.0, 0.0]));
        }
        "tunnel" => {
            // as the game's test has it: the lava one block inside the wall, the block in front of it taken away; a pit where it comes down
            for y in 0..5 { for z in -4..5 { for x in -3..0 { if !(y == 2 && z == 0 && x >= -2) { solid(x, y, z); } } } }
            st_remove = Some([0.0, -1.0, 0.0]);
            for x in -4..9 { for z in -4..5 { solid(x, -2, z); } }
            w.add_body(LAVA, -2.0, 2.0, 0.0, 1.0, 0.0, 0.0);
        }
        _ => {
            // a wall three blocks thick and five high; the pocket is one block in it, two above the floor, open towards +x
            for y in 0..5 { for z in -4..5 { for x in -3..0 { if !(x == -1 && y == 2 && z == 0) { solid(x, y, z); } } } }
            w.add_body(LAVA, -1.0, 2.0, 0.0, 1.0, 0.0, 0.0);
        }
    }
    drop(solid);
    if let Some(hole) = st_remove {
        let mut kept = Vec::new();
        for b in st.chunks(7) { if !(b[0] == hole[0] && b[1] == hole[1] && b[2] == hole[2]) { kept.extend_from_slice(b); } }
        st = kept;
    }
    w.set_static(&st, st.len() / 7);
    let last = times.iter().cloned().fold(0.0f32, f32::max);
    let mut next = 0usize;
    let started = std::time::Instant::now();
    for step in 0..=(last * 60.0) as usize + 1 {
        for &(at, cell) in &later { if at == step { w.add_body(LAVA, cell[0], cell[1], cell[2], 1.0, 0.0, 0.0); } }
        if step % 3 == 0 { w.set_static(&st, st.len() / 7); }
        w.step(DT + 1e-6);
        if next < times.len() && (step + 1) as f32 / 60.0 >= times[next] {
            let mut buf = vec![0.0f32; (w.maxp + PARTICLE_FILLERS) * 4];
            let n = w.particles(&mut buf).max(0) as usize;
            let name = format!("{}_{:05.1}.bin", out, times[next]);
            let mut f = std::fs::File::create(&name).unwrap();
            for v in &buf[..n * 4] { f.write_all(&v.to_le_bytes()).unwrap(); }
            let (mut lo, mut hi) = ([f32::MAX; 3], [f32::MIN; 3]);
            for i in 0..n { for a in 0..3 { lo[a] = lo[a].min(buf[i * 4 + a]); hi[a] = hi[a].max(buf[i * 4 + a]); } }
            // how much has come out of the wall, how high it lies on the floor (nineteen in twenty particles are below this), over how much floor
            let outside = (0..n).filter(|&i| buf[i * 4] > 0.02).count();
            let mut low: Vec<f32> = (0..n).filter(|&i| buf[i * 4 + 1] < 1.0 && buf[i * 4] > 0.02).map(|i| buf[i * 4 + 1]).collect();
            low.sort_by(|a, b| a.partial_cmp(b).unwrap());
            let high = if low.is_empty() { 0.0 } else { low[(low.len() * 19 / 20).min(low.len() - 1)] };
            let mut tiles = std::collections::HashSet::new();
            for i in 0..n { if buf[i * 4 + 1] < 1.0 && buf[i * 4] > 0.02 { tiles.insert(((buf[i * 4] * 10.0).floor() as i32, (buf[i * 4 + 2] * 10.0).floor() as i32)); } }
            let pit = (0..n).filter(|&i| buf[i * 4 + 1] < 0.0 && buf[i * 4] >= 0.0 && buf[i * 4] < 1.0 && buf[i * 4 + 2] >= 0.0 && buf[i * 4 + 2] < 1.0).count();
            if pit > 0 { println!("        {} particles in the pit", pit); }
            println!("{:5.1} s: out {:3.0} %, on the floor {:.2} high over {:.2} blocks, x ..{:.2} y {:.2}..{:.2} z {:.2}..{:.2}, awake {} ({})", times[next], 100.0 * outside as f32 / n.max(1) as f32, high, tiles.len() as f32 / 100.0, hi[0], lo[1], hi[1], lo[2], hi[2], w.awake_count(), name.rsplit('/').next().unwrap_or(""));
            next += 1;
        }
    }
    println!("{:.1} s of lava in {:.1} s", last, started.elapsed().as_secs_f32());
}
