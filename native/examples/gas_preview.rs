//! Pictures of the gas solver without the game: `cargo run --release --example gas_preview -- <scene> <seconds> <out prefix> [night]`.
//! Scenes: fires (a block of fire, a campfire, a soul fire, a torch), blast (an explosion), room (a campfire under a roof).
//! Writes `<prefix>_<n>.ppm`, one per half second. The drawing is the same sum along each line of sight
//! as `shaders/core/gas_march.fsh` does, with the same numbers.

use physicsbruhh_native::gas::{kind, Blob, Gas};
use rayon::prelude::*;
use std::io::Write;

/// Smoke, heat, fuel, soul at a point given in fine cells.
fn tri(g: &Gas, x: f32, y: f32, z: f32) -> [f32; 4] {
    let (nx, ny, nz) = (g.fx, g.fy, g.fz);
    let gx = (x - 0.5).max(0.0).min(nx as f32 - 1.0);
    let gy = (y - 0.5).max(0.0).min(ny as f32 - 1.0);
    let gz = (z - 0.5).max(0.0).min(nz as f32 - 1.0);
    let (x0, y0, z0) = (gx as usize, gy as usize, gz as usize);
    let (x1, y1, z1) = ((x0 + 1).min(nx - 1), (y0 + 1).min(ny - 1), (z0 + 1).min(nz - 1));
    let (tx, ty, tz) = (gx - x0 as f32, gy - y0 as f32, gz - z0 as f32);
    let mut out = [0.0f32; 4];
    for (yy, wy) in [(y0, 1.0 - ty), (y1, ty)] { for (zz, wz) in [(z0, 1.0 - tz), (z1, tz)] { for (xx, wx) in [(x0, 1.0 - tx), (x1, tx)] {
        let v = &g.gas[xx + nx * (zz + nz * yy)];
        for c in 0..4 { out[c] += v[c] * wx * wy * wz; }
    } } }
    out
}

fn smooth(a: f32, b: f32, x: f32) -> f32 { let t = ((x - a) / (b - a)).clamp(0.0, 1.0); t * t * (3.0 - 2.0 * t) }

/// Light the gas gives off at this heat, per block of it looked through: soot glowing at the gas's temperature
/// (heat 1 is about 1800 K). The colour is that of a black body, the brightness climbs with temperature the way
/// visible light does (steeply), and soul fire burns blue.
fn flame(heat: f32, soul: f32) -> [f32; 3] {
    if heat < 0.12 { return [0.0; 3]; }
    let kelvin = 300.0 + 1500.0 * heat;
    let bright = GLOW * (GLOW_STEEP * (1.0 - 1800.0 / kelvin)).exp();
    let green = (0.04 + 0.000208 * (kelvin - 1000.0)).clamp(0.0, 0.7);
    let k = ((kelvin - 1900.0) / 1100.0).max(0.0);
    let blue = (0.147 * k * k).min(0.5);
    let t = ((heat - 0.2) / 0.9).clamp(0.0, 1.0);
    let cold = [0.05 + 0.35 * t * t, 0.35 + 0.5 * t, 1.0];
    [bright * (1.0 + (cold[0] - 1.0) * soul), bright * (green + (cold[1] - green) * soul), bright * (blue + (cold[2] - blue) * soul)]
}

/// Brightness of gas at flame heat, and how steeply it falls off as the gas cools.
const GLOW: f32 = 22.0;
const GLOW_STEEP: f32 = 11.0;
/// How much a block of smoke of density 1 dims what is behind it (e-folds), and the share of that light it scatters on rather than swallows.
const SMOKE_DIMS: f32 = 9.0;
const SMOKE_WHITE: f32 = 0.82;
/// Flames are nearly clear: this much dimming per block at flame heat.
const FLAME_DIMS: f32 = 0.6;
/// Share of light smoke throws on forwards (Henyey-Greenstein g).
const FORWARD: f32 = 0.45;

fn phase(cos: f32) -> f32 {
    let g = FORWARD;
    (1.0 - g * g) / (1.0 + g * g - 2.0 * g * cos).powf(1.5)
}

fn main() {
    let args: Vec<String> = std::env::args().collect();
    let scene = args.get(1).map(String::as_str).unwrap_or("fires");
    let seconds: f32 = args.get(2).and_then(|s| s.parse().ok()).unwrap_or(6.0);
    let prefix = args.get(3).cloned().unwrap_or_else(|| "gas".to_string());
    let night = args.get(4).map(|s| s == "night").unwrap_or(false);
    let ratio: usize = std::env::var("RATIO").ok().and_then(|s| s.parse().ok()).unwrap_or(2);
    let cpb: usize = std::env::var("CPB").ok().and_then(|s| s.parse().ok()).unwrap_or(2);

    let close = scene.starts_with("close");
    let (bx, by, bz) = if close { (10, 14, 10) } else { (24, 20, 16) };
    let mut g = Gas::new(bx, by, bz, cpb, ratio);
    g.sharp = std::env::var("BLUNT").is_err();
    {
        // G_<number of the parameter>=value changes one of the solver's numbers for this run
        let mut v = g.params.to_array();
        for (i, slot) in v.iter_mut().enumerate() { if let Some(x) = std::env::var(format!("G_{}", i)).ok().and_then(|s| s.parse::<f32>().ok()) { *slot = x; } }
        g.params = physicsbruhh_native::gas::Params::from_slice(&v).unwrap();
    }
    for z in 0..bz { for x in 0..bx { for y in 0..2 { g.set_block(x, y, z, true, 0, 0.0); } } }
    let mut blobs: Vec<Blob> = Vec::new();
    match scene {
        "fires" => {
            g.set_block(4, 2, 8, false, kind::FIRE, 1.0);
            g.set_block(10, 2, 8, false, kind::CAMPFIRE, 1.0);
            g.set_block(15, 2, 8, false, kind::FIRE | kind::SOUL, 1.0);
            g.set_block(20, 2, 8, false, kind::TORCH, 1.0);
        }
        "close" => g.set_block(5, 2, 5, false, kind::FIRE, 1.0),
        "closecamp" => g.set_block(5, 2, 5, false, kind::CAMPFIRE, 1.0),
        "closesoul" => g.set_block(5, 2, 5, false, kind::FIRE | kind::SOUL, 1.0),
        "wide" => { for x in 8..12 { for z in 7..10 { g.set_block(x, 2, z, false, kind::FIRE, 1.0); } } }
        "room" => {
            g.set_block(12, 2, 8, false, kind::CAMPFIRE, 1.0);
            for z in 4..13 { for x in 7..18 { g.set_block(x, 7, z, true, 0, 0.0); } }
            for y in 2..7 { for x in 7..18 { g.set_block(x, y, 12, true, 0, 0.0); } }
        }
        "lava" => { for x in 6..18 { for z in 5..11 { g.set_block(x, 1, z, true, 0, 0.0); g.set_block(x, 2, z, false, kind::OVER_LAVA, 1.0); } } g.set_block(12, 3, 2, false, kind::CHIMNEY, 1.0); g.set_block(12, 2, 2, true, 0, 0.0); }
        _ => {}
    }

    let (w, h) = if close { (520usize, 640usize) } else { (720usize, 600usize) };
    let near = std::env::var("NEAR").is_ok();
    let eye = if close { [5.5f32, 3.6, -0.6] } else if near { [8.0f32, 4.5, -1.5] } else { [12.0f32, 7.0, -13.0] };
    let look = if close { [5.5f32, 4.9, 5.5] } else if near { [8.0f32, 5.5, 8.0] } else { [12.0f32, 8.0, 8.0] };
    let fwd = { let d = [look[0] - eye[0], look[1] - eye[1], look[2] - eye[2]]; let l = (d[0] * d[0] + d[1] * d[1] + d[2] * d[2]).sqrt(); [d[0] / l, d[1] / l, d[2] / l] };
    let right = { let l = (fwd[0] * fwd[0] + fwd[2] * fwd[2]).sqrt(); [fwd[2] / l, 0.0, -fwd[0] / l] };
    let up = [right[1] * fwd[2] - right[2] * fwd[1], right[2] * fwd[0] - right[0] * fwd[2], right[0] * fwd[1] - right[1] * fwd[0]];
    let up = [-up[0], -up[1], -up[2]];
    let sun = { let d = [0.45f32, 0.8, -0.4]; let l = (d[0] * d[0] + d[1] * d[1] + d[2] * d[2]).sqrt(); [d[0] / l, d[1] / l, d[2] / l] };
    let (sun_light, sky_light) = if night { ([0.02f32, 0.03, 0.05], [0.006f32, 0.008, 0.014]) } else { ([0.62f32, 0.58, 0.5], [0.3f32, 0.36, 0.45]) };
    let fpb = (g.cpb * g.ratio) as f32;

    let dt = 1.0 / 30.0;
    let frames = (seconds / dt) as usize;
    let mut spent = std::time::Duration::ZERO;
    let mut shot = 0;
    for frame in 0..=frames {
        if scene == "blast" {
            blobs.clear();
            let t = frame as f32 * dt;
            let c = [12.0, 3.2, 8.0];
            if t < 0.12 { blobs.push(Blob { pos: c, radius: 2.4, fuel: 14.0, smoke: 6.0, heat: 1.4, soul: 0.0, vel: [0.0; 3], expand: 60.0 }); }
            else if t < 0.5 { blobs.push(Blob { pos: c, radius: 2.0, fuel: 0.0, smoke: 3.0, heat: 0.0, soul: 0.0, vel: [0.0; 3], expand: 0.0 }); }
        }
        if scene == "runner" {
            blobs.clear();
            let t = frame as f32 * dt;
            let x = 3.0 + 3.0 * t;
            if x < 21.0 {
                blobs.push(Blob { pos: [x, 2.5, 8.0], radius: 0.55, fuel: 5.0, smoke: 0.4, heat: 0.6, soul: 0.0, vel: [3.0, 0.0, 0.0], expand: 0.0 });
                blobs.push(Blob { pos: [x, 3.3, 8.0], radius: 0.55, fuel: 5.0, smoke: 0.4, heat: 0.6, soul: 0.0, vel: [3.0, 0.0, 0.0], expand: 0.0 });
            }
        }
        if frame % 30 == 15 {
            let mut img = vec![0u8; w * h * 3];
            let g = &g;
            img.par_chunks_mut(w * 3).enumerate().for_each(|(py, line)| {
                for px in 0..w {
                    let (sx, sy) = ((px as f32 + 0.5) / w as f32 * 2.0 - 1.0, 1.0 - (py as f32 + 0.5) / h as f32 * 2.0);
                    let (kx, ky) = (sx * 0.62 * w as f32 / h as f32, sy * 0.62);
                    let d = [fwd[0] + right[0] * kx + up[0] * ky, fwd[1] + right[1] * kx + up[1] * ky, fwd[2] + right[2] * kx + up[2] * ky];
                    let l = (d[0] * d[0] + d[1] * d[1] + d[2] * d[2]).sqrt();
                    let d = [d[0] / l, d[1] / l, d[2] / l];
                    // what is behind: sky, or the ground at height 2
                    let mut far = 80.0f32;
                    let mut back = if night { [0.02f32, 0.03, 0.06] } else { [0.5 + 0.2 * (1.0 - d[1]), 0.68 + 0.12 * (1.0 - d[1]), 0.95] };
                    if d[1] < -1.0e-4 {
                        let t = (2.0 - eye[1]) / d[1];
                        if t > 0.0 {
                            far = t;
                            let (gx, gz) = (eye[0] + d[0] * t, eye[2] + d[2] * t);
                            let check = if ((gx.floor() + gz.floor()) as i32) & 1 == 0 { 1.0 } else { 0.86 };
                            let lit = if night { 0.06 } else { 0.9 };
                            back = [0.33 * check * lit, 0.5 * check * lit, 0.24 * check * lit];
                        }
                    }
                    // blocks in the way (roof, wall): the nearest solid block along the ray, coarsely
                    let mut t = 0.0;
                    while t < far {
                        let p = [eye[0] + d[0] * t, eye[1] + d[1] * t, eye[2] + d[2] * t];
                        if p[0] >= 0.0 && p[1] >= 2.0 && p[2] >= 0.0 && (p[0] as usize) < bx && (p[1] as usize) < by && (p[2] as usize) < bz
                            && g.world[g.block(p[0] as usize, p[1] as usize, p[2] as usize)] != 0 {
                            far = t; back = if night { [0.05, 0.05, 0.055] } else { [0.45, 0.45, 0.47] }; break;
                        }
                        t += 0.05;
                    }
                    let (mut cr, mut cg, mut cb, mut tr) = (0.0f32, 0.0f32, 0.0f32, 1.0f32);
                    let ds = 0.5 / fpb;
                    let jitter = ((px * 7 + py * 13) % 16) as f32 / 16.0;
                    let towards = phase(d[0] * sun[0] + d[1] * sun[1] + d[2] * sun[2]);
                    let mut t = jitter * ds;
                    while t < far && tr > 0.01 {
                        let p = [eye[0] + d[0] * t, eye[1] + d[1] * t, eye[2] + d[2] * t];
                        t += ds;
                        if p[0] < 0.0 || p[1] < 0.0 || p[2] < 0.0 || p[0] >= bx as f32 || p[1] >= by as f32 || p[2] >= bz as f32 { continue; }
                        let (qx, qy, qz) = (p[0] * fpb, p[1] * fpb, p[2] * fpb);
                        let v = tri(g, qx, qy, qz);
                        let (s, heat, soul) = (v[0], v[1], (v[3] / (v[1] + v[2] + 1.0e-4)).min(1.0));
                        if s < 0.002 && heat < 0.12 { continue; }
                        // hot gas is thin gas: as many times fewer specks of smoke to the block as it is hotter than the air
                        let s = s / (1.0 + 5.0 * heat);
                        let dims = SMOKE_DIMS * s + FLAME_DIMS * smooth(0.3, 0.9, heat);
                        let a = 1.0 - (-dims * ds).exp();
                        let glow = flame(heat, soul);
                        let (mut lr, mut lg, mut lb) = (0.0, 0.0, 0.0);
                        if s > 0.002 {
                            // how much smoke the sun's light crosses on its way here; light scattered more than once gets further than that
                            let mut depth = 0.0;
                            let mut reach = 0.0;
                            for step in [0.15f32, 0.3, 0.55, 0.9, 1.4] {
                                let r = reach + 0.5 * step;
                                depth += tri(g, (p[0] + sun[0] * r) * fpb, (p[1] + sun[1] * r) * fpb, (p[2] + sun[2] * r) * fpb)[0] * step;
                                reach += step;
                            }
                            let depth = depth * SMOKE_DIMS;
                            let direct = (-depth).exp();
                            let soft = 0.75 * direct + 0.25 * (-0.22 * depth).exp();
                            // the flames under the smoke light it from below
                            let (mut fr, mut fg, mut fb) = (0.0, 0.0, 0.0);
                            for (down, share) in [(0.35f32, 0.5f32), (0.9, 0.3), (1.8, 0.2)] {
                                let u = tri(g, qx, qy - down * fpb, qz);
                                let f = flame(u[1], (u[3] / (u[1] + u[2] + 1.0e-4)).min(1.0));
                                fr += f[0] * share; fg += f[1] * share; fb += f[2] * share;
                            }
                            let own = 0.12;
                            lr = sun_light[0] * (towards * direct + 0.6 * soft) + sky_light[0] * (0.35 + 0.65 * soft) + own * fr;
                            lg = sun_light[1] * (towards * direct + 0.6 * soft) + sky_light[1] * (0.35 + 0.65 * soft) + own * fg;
                            lb = sun_light[2] * (towards * direct + 0.6 * soft) + sky_light[2] * (0.35 + 0.65 * soft) + own * fb;
                        }
                        let scatter = SMOKE_WHITE * (1.0 - (-SMOKE_DIMS * s * ds).exp());
                        cr += tr * (lr * scatter + glow[0] * ds);
                        cg += tr * (lg * scatter + glow[1] * ds);
                        cb += tr * (lb * scatter + glow[2] * ds);
                        tr *= 1.0 - a;
                    }
                    // the gas's light as a camera would take it: bright flame goes to yellow and white rather than being cut off
                    let (cr, cg, cb) = (1.0 - (-cr).exp(), 1.0 - (-cg).exp(), 1.0 - (-cb).exp());
                    let o = px * 3;
                    line[o] = ((cr + back[0] * tr).min(1.0).powf(1.0 / 2.2) * 255.0) as u8;
                    line[o + 1] = ((cg + back[1] * tr).min(1.0).powf(1.0 / 2.2) * 255.0) as u8;
                    line[o + 2] = ((cb + back[2] * tr).min(1.0).powf(1.0 / 2.2) * 255.0) as u8;
                }
            });
            let mut file = std::fs::File::create(format!("{}_{:02}.ppm", prefix, shot)).unwrap();
            write!(file, "P6\n{} {}\n255\n", w, h).unwrap();
            file.write_all(&img).unwrap();
            let t = g.totals();
            let (mut top, mut mass, mut mean) = (0usize, 0.0f64, 0.0f64);
            for (y, &layer) in g.smoke_by_height().iter().enumerate() { let layer = layer as f64; if layer > 0.01 { top = y; } mass += layer; mean += layer * y as f64; }
            print!("top {:.1} mean {:.1} | ", top as f32 / fpb, if mass > 0.0 { mean / mass / fpb as f64 } else { 0.0 });
            println!("t={:.2}s smoke {:.2} heat {:.2} hottest {:.2} fastest {:.2} divergence {:.3}", frame as f32 * dt, t[0], t[1], t[2], t[3], g.worst_divergence());
            shot += 1;
        }
        let started = std::time::Instant::now();
        g.step(dt, &blobs);
        if frame < 50 && frame % 5 == 4 && std::env::var("EARLY").is_ok() { let k = 1.0 / (fpb * fpb * fpb); let (f, h, s) = g.gas.iter().fold((0.0f64, 0.0f64, 0.0f64), |a, v| (a.0 + v[2] as f64, a.1 + v[1] as f64, a.2 + v[0] as f64)); println!("step {}: fuel {:.5} heat {:.5} smoke {:.5}", frame + 1, f as f32 * k, h as f32 * k, s as f32 * k); }
        spent += started.elapsed();
    }
    println!("{} steps of {}x{}x{} coarse, {}x{}x{} fine: {:.1} ms a step on {} threads ({} pictures)", frames, g.nx, g.ny, g.nz, g.fx, g.fy, g.fz,
        spent.as_secs_f32() * 1000.0 / frames as f32, physicsbruhh_native::sim::threads(), shot);
}
