//! Ways of starting the pressure solve, compared: `cargo run --release --example gas_pressure -- <scene> [seconds] [jitter]`.
//!
//! Each step the solver has to find the pressure that undoes the piling up of the air. It does a fixed number of
//! rounds, starting from a guess; the better the guess, the fewer rounds it needs. This runs the same scene once for
//! every guess and every number of rounds and prints how far the pressure it ends with is from the exact one (found
//! here by conjugate gradients, only to measure against):
//!
//! - `zero`: no guess;
//! - `last`: last step's pressure as it is (what the solver did before);
//! - `scaled`: the same, scaled by how long this step is against the last (the pressure of a step is as large as the step is long);
//! - `ahead`: scaled, and carried on in time the way it changed over the last step;
//! - `carried`: scaled, and moved along with the flow;
//! - `split2`, `split4`: last step's pressure split into a smooth part (averaged over 2 or 4 cells and spread back)
//!   and the detail left over; the smooth part carried on in time where it is, the detail moved along with the flow.
//!
//! "off" is the error of what the solver takes off the velocity, as a share of what it should take off.
//! Scenes: camp, fire, room, blast. `jitter`: steps of uneven length, as frames are.

use physicsbruhh_native::gas::{kind, Blob, Gas};

#[derive(Clone, Copy, PartialEq, Debug)]
enum Guess { Zero, Last, Scaled, Ahead(f32), Carried, Split(usize, f32), SplitStill(usize, f32) }

impl Guess {
    fn name(&self) -> String {
        match self {
            Guess::Zero => "zero".into(), Guess::Last => "last".into(), Guess::Scaled => "scaled".into(),
            Guess::Ahead(b) => format!("ahead{}", if *b == 1.0 { String::new() } else { format!("*{}", b) }),
            Guess::Carried => "carried".into(),
            Guess::Split(f, b) => format!("split{}{}", f, if *b == 1.0 { String::new() } else { format!("*{}", b) }),
            Guess::SplitStill(f, b) => format!("smooth{}{}", f, if *b == 1.0 { String::new() } else { format!("*{}", b) }),
        }
    }
}

/// The six neighbours of a cell as the solver reads them: a block has the pressure of the cell itself, outside the box it is zero.
#[inline]
fn around(g: &Gas, p: &[f32], x: usize, y: usize, z: usize) -> [f32; 6] {
    let (nx, ny, nz) = (g.nx, g.ny, g.nz);
    let layer = nx * nz;
    let i = x + nx * z + layer * y;
    let v = |j: usize, inside: bool| if !inside { 0.0 } else if g.solid[j] != 0 { p[i] } else { p[j] };
    [v(i + 1, x + 1 < nx), v(i.wrapping_sub(1), x > 0), v(i + layer, y + 1 < ny), v(i.wrapping_sub(layer), y > 0), v(i + nx, z + 1 < nz), v(i.wrapping_sub(nx), z > 0)]
}

fn cells(g: &Gas) -> impl Iterator<Item = (usize, usize, usize, usize)> + '_ {
    let (nx, ny, nz) = (g.nx, g.ny, g.nz);
    (0..ny).flat_map(move |y| (0..nz).flat_map(move |z| (0..nx).map(move |x| (x, y, z, x + nx * (z + nz * y))))).filter(move |c| g.solid[c.3] == 0)
}

/// The solver's rounds, as in `Gas::pressure`.
fn rounds(g: &Gas, p: &mut Vec<f32>, q: &mut Vec<f32>, n: usize) {
    let h2 = 1.0 / (g.cpb * g.cpb) as f32;
    for _ in 0..n {
        q.iter_mut().for_each(|v| *v = 0.0);
        for (x, y, z, i) in cells(g) {
            let a = around(g, p, x, y, z);
            q[i] = (a.iter().sum::<f32>() - h2 * g.rhs[i]) / 6.0;
        }
        std::mem::swap(p, q);
    }
}

/// A coarser copy of the grid for the solve in steps: cells twice the size, a cell a block only if all it covers is block.
/// `edge`: how strongly a cell at the edge of the box is tied to the zero outside it. On the solver's own grid the
/// zero is one cell beyond the last (1); a coarser grid has to put it at the same place, which is nearer to its last
/// cell than its own cells are to each other (so more than 1), or it answers a different question.
struct Level { nx: usize, ny: usize, nz: usize, h2: f32, edge: f32, solid: Vec<u8>, p: Vec<f32>, f: Vec<f32>, q: Vec<f32> }

impl Level {
    fn of(g: &Gas) -> Level {
        let n = g.p.len();
        Level { nx: g.nx, ny: g.ny, nz: g.nz, h2: 1.0 / (g.cpb * g.cpb) as f32, edge: 1.0, solid: g.solid.clone(), p: vec![0.0; n], f: g.rhs.clone(), q: vec![0.0; n] }
    }

    fn coarser(&self) -> Level {
        let (nx, ny, nz) = (self.nx.div_ceil(2), self.ny.div_ceil(2), self.nz.div_ceil(2));
        // SOLID=all|half|any: a coarse cell is a block if all, at least half, or any of the cells it covers are
        let rule = std::env::var("SOLID").unwrap_or_else(|_| "all".into());
        let mut count = vec![[0u8; 2]; nx * ny * nz];
        for y in 0..self.ny { for z in 0..self.nz { for x in 0..self.nx {
            count[x / 2 + nx * (z / 2 + nz * (y / 2))][(self.solid[x + self.nx * (z + self.nz * y)] != 0) as usize] += 1;
        } } }
        let solid: Vec<u8> = count.iter().map(|c| match rule.as_str() { "any" => c[1] > 0, "half" => c[1] >= c[0], _ => c[0] == 0 } as u8).collect();
        // cells `size` times the solver's: from the middle of the last one to the zero outside is (size + 1) / 2 of the solver's cells
        let size = if std::env::var("PLAINEDGE").is_ok() { 1.0 } else { 2.0 * (2.0 / self.edge - 1.0) };
        Level { nx, ny, nz, h2: self.h2 * 4.0, edge: 2.0 * size / (size + 1.0), solid, p: vec![0.0; nx * ny * nz], f: vec![0.0; nx * ny * nz], q: vec![0.0; nx * ny * nz] }
    }

    /// The sum of the neighbours' pressures and how many of them count (a block does not; the outside counts `edge` and is zero).
    #[inline]
    fn around(&self, p: &[f32], x: usize, y: usize, z: usize) -> (f32, f32) {
        let (nx, ny, nz) = (self.nx, self.ny, self.nz);
        let layer = nx * nz;
        let i = x + nx * z + layer * y;
        let (mut sum, mut ties) = (0.0, 0.0);
        for (j, inside) in [(i + 1, x + 1 < nx), (i.wrapping_sub(1), x > 0), (i + layer, y + 1 < ny), (i.wrapping_sub(layer), y > 0), (i + nx, z + 1 < nz), (i.wrapping_sub(nx), z > 0)] {
            if !inside { ties += self.edge; } else if self.solid[j] == 0 { sum += p[j]; ties += 1.0; }
        }
        (sum, ties)
    }

    /// Rounds that even out what is uneven from cell to cell (each takes `weight` of the full step: 1 is the solver's own round).
    fn even_out(&mut self, n: usize, weight: f32) {
        for _ in 0..n {
            for y in 0..self.ny { for z in 0..self.nz { for x in 0..self.nx {
                let i = x + self.nx * (z + self.nz * y);
                self.q[i] = if self.solid[i] != 0 { 0.0 } else {
                    let (sum, ties) = self.around(&self.p, x, y, z);
                    if ties > 0.0 { self.p[i] + weight * ((sum - self.h2 * self.f[i]) / ties - self.p[i]) } else { self.p[i] }
                };
            } } }
            std::mem::swap(&mut self.p, &mut self.q);
        }
    }

    /// What is still left to undo, handed to the coarser grid: the mean over the eight cells each of its cells covers.
    fn hand_down(&self, to: &mut Level) {
        to.f.iter_mut().for_each(|v| *v = 0.0);
        to.p.iter_mut().for_each(|v| *v = 0.0);
        for y in 0..self.ny { for z in 0..self.nz { for x in 0..self.nx {
            let i = x + self.nx * (z + self.nz * y);
            if self.solid[i] != 0 { continue; }
            let (sum, ties) = self.around(&self.p, x, y, z);
            let left = self.f[i] - (sum - ties * self.p[i]) / self.h2;
            to.f[x / 2 + to.nx * (z / 2 + to.nz * (y / 2))] += left * 0.125;
        } } }
    }

    /// The coarser grid's answer added to this one, spread smoothly between its cells.
    fn take_up(&mut self, from: &Level) {
        let (cx, cy, cz) = (from.nx, from.ny, from.nz);
        for y in 0..self.ny { for z in 0..self.nz { for x in 0..self.nx {
            let i = x + self.nx * (z + self.nz * y);
            if self.solid[i] != 0 { continue; }
            // between the middles of the coarse cells; beyond the last of them towards the zero outside the box, the
            // answer falls off to that zero (a cell that is not there stands for it: the last cell's value times 1 - edge)
            let (gx, gy, gz) = ((x as f32 + 0.5) * 0.5 - 0.5, (y as f32 + 0.5) * 0.5 - 0.5, (z as f32 + 0.5) * 0.5 - 0.5);
            let (x0, y0, z0) = (gx.floor() as i32, gy.floor() as i32, gz.floor() as i32);
            let (tx, ty, tz) = (gx - x0 as f32, gy - y0 as f32, gz - z0 as f32);
            let out = 1.0 - from.edge;
            let pick = |v: i32, n: usize| if v < 0 { (0usize, out) } else if v >= n as i32 { (n - 1, out) } else { (v as usize, 1.0) };
            let (mut s, mut w) = (0.0, 0.0);
            for (yy, wy) in [(y0, 1.0 - ty), (y0 + 1, ty)] { for (zz, wz) in [(z0, 1.0 - tz), (z0 + 1, tz)] { for (xx, wx) in [(x0, 1.0 - tx), (x0 + 1, tx)] {
                let ((ix, kx), (iy, ky), (iz, kz)) = (pick(xx, cx), pick(yy, cy), pick(zz, cz));
                let c = ix + cx * (iz + cz * iy);
                if from.solid[c] == 0 { s += from.p[c] * kx * ky * kz * wx * wy * wz; w += wx * wy * wz; }
            } } }
            if std::env::var("FLAT").is_ok() {
                // (to compare: each cell simply takes the coarse cell it lies in)
                let c = (x / 2) + cx * ((z / 2) + cz * (y / 2));
                if from.solid[c] == 0 { self.p[i] += from.p[c]; }
                continue;
            }
            if w > 1.0e-4 { self.p[i] += s / w; }
        } } }
    }
}

/// How the pressure is found from the guess.
#[derive(Clone, Copy, PartialEq, Debug)]
enum Solve {
    /// Plain rounds, as the solver did before.
    Rounds(usize),
    /// What the solver does now (`Gas::pressure`), with this many rounds a step on its own grid.
    Own(usize),
    /// In steps: a few rounds, what is left handed to a grid twice as coarse and solved there the same way, the answer
    /// taken back up, a few rounds more. `cycles` times; `before`/`after` rounds on each grid; `levels` grids under the first.
    Steps { cycles: usize, before: usize, after: usize, levels: usize },
}

impl Solve {
    fn name(&self) -> String {
        match self { Solve::Rounds(n) => format!("{} rounds", n), Solve::Own(n) => format!("the solver's, {}", n), Solve::Steps { cycles, before, after, levels } => format!("{}x steps({},{})/{}", cycles, before, after, levels) }
    }

    /// What it costs, in rounds on the full grid (a round reads seven cells for each it writes; handing down reads seven, taking up eight).
    fn cost(&self) -> f32 {
        match self {
            Solve::Rounds(n) => *n as f32,
            Solve::Own(n) => { let cycles = (*n / 4).max(1); Solve::Steps { cycles, before: (*n / (2 * cycles)).max(1), after: (*n / (2 * cycles)).max(1), levels: 3 }.cost() }
            Solve::Steps { cycles, before, after, levels } => {
                let mut cost = 0.0;
                for l in 0..=*levels {
                    let share = 0.125f32.powi(l as i32);
                    let rounds = if l == *levels { (before + after) as f32 + 4.0 } else { (before + after) as f32 + 1.0 + 8.0 / 7.0 };
                    cost += share * rounds;
                }
                cost * *cycles as f32
            }
        }
    }
}

fn down_and_up(levels: &mut [Level], before: usize, after: usize) {
    let (here, rest) = levels.split_first_mut().unwrap();
    if rest.is_empty() { here.even_out(before + after + 4, 6.0 / 7.0); return; }
    here.even_out(before, 6.0 / 7.0);
    here.hand_down(&mut rest[0]);
    down_and_up(rest, before, after);
    here.take_up(&rest[0]);
    here.even_out(after, 6.0 / 7.0);
}

/// What is left of the piling up with this pressure (root of the sum of squares, in the solver's own units).
fn left(g: &Gas, p: &[f32]) -> f64 {
    let h2 = 1.0 / (g.cpb * g.cpb) as f32;
    cells(g).map(|(x, y, z, i)| { let a = around(g, p, x, y, z); let r = (a.iter().sum::<f32>() - 6.0 * p[i] - h2 * g.rhs[i]) as f64; r * r }).sum::<f64>().sqrt()
}

/// The exact pressure, by conjugate gradients from `start` (to measure against; far too slow for the game).
fn exact(g: &Gas, start: &[f32]) -> Vec<f32> {
    let n = start.len();
    let h2 = 1.0 / (g.cpb * g.cpb) as f64;
    let open: Vec<(usize, usize, usize, usize)> = cells(g).collect();
    let mut x: Vec<f32> = start.to_vec();
    for i in 0..n { if g.solid[i] != 0 { x[i] = 0.0; } }
    let apply = |p: &[f32], out: &mut [f64]| { for &(cx, cy, cz, i) in &open { let a = around(g, p, cx, cy, cz); out[i] = 6.0 * p[i] as f64 - a.iter().map(|v| *v as f64).sum::<f64>(); } };
    let mut ax = vec![0.0f64; n];
    apply(&x, &mut ax);
    let mut r = vec![0.0f64; n];
    let mut b2 = 0.0;
    for &(_, _, _, i) in &open { let b = -h2 * g.rhs[i] as f64; r[i] = b - ax[i]; b2 += b * b; }
    let mut d = r.clone();
    let mut rr: f64 = open.iter().map(|c| r[c.3] * r[c.3]).sum();
    let mut df = vec![0.0f32; n];
    let mut ad = vec![0.0f64; n];
    let mut xd: Vec<f64> = x.iter().map(|v| *v as f64).collect();
    for _ in 0..2000 {
        if rr <= 1.0e-12 * b2.max(1.0e-30) { break; }
        for &(_, _, _, i) in &open { df[i] = d[i] as f32; }
        apply(&df, &mut ad);
        let dad: f64 = open.iter().map(|c| d[c.3] * ad[c.3]).sum();
        if dad <= 0.0 { break; }
        let alpha = rr / dad;
        for &(_, _, _, i) in &open { xd[i] += alpha * d[i]; r[i] -= alpha * ad[i]; }
        let new: f64 = open.iter().map(|c| r[c.3] * r[c.3]).sum();
        let beta = new / rr;
        rr = new;
        for &(_, _, _, i) in &open { d[i] = r[i] + beta * d[i]; }
    }
    for i in 0..n { x[i] = xd[i] as f32; }
    x
}

/// The size of what a pressure takes off the velocity (root of the sum of squares of its slope, as `Gas::project` reads it).
fn slope(g: &Gas, p: &[f32]) -> f64 {
    cells(g).map(|(x, y, z, _)| { let a = around(g, p, x, y, z); let (sx, sy, sz) = ((a[0] - a[1]) as f64, (a[2] - a[3]) as f64, (a[4] - a[5]) as f64); sx * sx + sy * sy + sz * sz }).sum::<f64>().sqrt()
}

/// A field between cells, counting only cells that are not inside blocks (`x`, `y`, `z` in cells).
fn sample(g: &Gas, f: &[f32], x: f32, y: f32, z: f32) -> Option<f32> {
    let (nx, ny, nz) = (g.nx, g.ny, g.nz);
    let (gx, gy, gz) = ((x - 0.5).clamp(0.0, nx as f32 - 1.0), (y - 0.5).clamp(0.0, ny as f32 - 1.0), (z - 0.5).clamp(0.0, nz as f32 - 1.0));
    let (x0, y0, z0) = (gx as usize, gy as usize, gz as usize);
    let (x1, y1, z1) = ((x0 + 1).min(nx - 1), (y0 + 1).min(ny - 1), (z0 + 1).min(nz - 1));
    let (tx, ty, tz) = (gx - x0 as f32, gy - y0 as f32, gz - z0 as f32);
    let (mut sum, mut weight) = (0.0, 0.0);
    for (yy, wy) in [(y0, 1.0 - ty), (y1, ty)] { for (zz, wz) in [(z0, 1.0 - tz), (z1, tz)] { for (xx, wx) in [(x0, 1.0 - tx), (x1, tx)] {
        let i = xx + nx * (zz + nz * yy);
        if g.solid[i] == 0 { sum += f[i] * wx * wy * wz; weight += wx * wy * wz; }
    } } }
    if weight > 1.0e-4 { Some(sum / weight) } else { None }
}

/// The smooth part of a field: averaged over groups of `f` cells a side, and spread back between them.
fn smooth(g: &Gas, p: &[f32], f: usize) -> Vec<f32> {
    let (nx, ny, nz) = (g.nx, g.ny, g.nz);
    let (cx, cy, cz) = (nx.div_ceil(f), ny.div_ceil(f), nz.div_ceil(f));
    let mut sum = vec![0.0f32; cx * cy * cz];
    let mut count = vec![0.0f32; cx * cy * cz];
    for (x, y, z, i) in cells(g) { let c = x / f + cx * (z / f + cz * (y / f)); sum[c] += p[i]; count[c] += 1.0; }
    let mut out = vec![0.0f32; p.len()];
    let k = 1.0 / f as f32;
    for (x, y, z, i) in cells(g) {
        let (gx, gy, gz) = (((x as f32 + 0.5) * k - 0.5).clamp(0.0, cx as f32 - 1.0), ((y as f32 + 0.5) * k - 0.5).clamp(0.0, cy as f32 - 1.0), ((z as f32 + 0.5) * k - 0.5).clamp(0.0, cz as f32 - 1.0));
        let (x0, y0, z0) = (gx as usize, gy as usize, gz as usize);
        let (x1, y1, z1) = ((x0 + 1).min(cx - 1), (y0 + 1).min(cy - 1), (z0 + 1).min(cz - 1));
        let (tx, ty, tz) = (gx - x0 as f32, gy - y0 as f32, gz - z0 as f32);
        let (mut s, mut w) = (0.0, 0.0);
        for (yy, wy) in [(y0, 1.0 - ty), (y1, ty)] { for (zz, wz) in [(z0, 1.0 - tz), (z1, tz)] { for (xx, wx) in [(x0, 1.0 - tx), (x1, tx)] {
            let c = xx + cx * (zz + cz * yy);
            if count[c] > 0.0 { s += sum[c] / count[c] * wx * wy * wz; w += wx * wy * wz; }
        } } }
        out[i] = if w > 1.0e-4 { s / w } else { p[i] };
    }
    out
}

/// A field moved along with the flow over a step.
fn carried(g: &Gas, f: &[f32], dt: f32) -> Vec<f32> {
    let c = g.cpb as f32;
    let mut out = vec![0.0f32; f.len()];
    for (x, y, z, i) in cells(g) {
        let u = g.vel[i];
        out[i] = sample(g, f, x as f32 + 0.5 - dt * u[0] * c, y as f32 + 0.5 - dt * u[1] * c, z as f32 + 0.5 - dt * u[2] * c).unwrap_or(f[i]);
    }
    out
}

struct Tried { guess: Guess, how: Solve, guard: bool, last: Vec<f32>, before: Vec<f32>, last_dt: f32, q: Vec<f32>, off_guess: Vec<f64>, off: Vec<f64>, left: Vec<f64>, fell_back: usize, steps: usize }

impl Tried {
    /// The guess for this step: pressures are kept per second of step (`last`, `before`), so that steps of uneven length compare.
    fn start(&mut self, g: &Gas, dt: f32) -> Vec<f32> {
        let n = g.p.len();
        let scale = |f: &[f32]| -> Vec<f32> { f.iter().map(|v| v * dt).collect() };
        if self.steps == 0 { return vec![0.0; n]; }
        let on = if self.steps >= 2 { dt / self.last_dt } else { 0.0 };
        match self.guess {
            Guess::Zero => vec![0.0; n],
            Guess::Last => g.p.clone(),
            Guess::Scaled => scale(&self.last),
            Guess::Ahead(b) => scale(&self.last.iter().zip(&self.before).map(|(a, o)| a + b * on * (a - o)).collect::<Vec<f32>>()),
            Guess::Carried => scale(&carried(g, &self.last, dt)),
            Guess::Split(f, b) | Guess::SplitStill(f, b) => {
                let (s1, s2) = (smooth(g, &self.last, f), smooth(g, &self.before, f));
                let detail: Vec<f32> = self.last.iter().zip(&s1).map(|(a, s)| a - s).collect();
                let detail = if matches!(self.guess, Guess::Split(..)) { carried(g, &detail, dt) } else { detail };
                scale(&(0..n).map(|i| s1[i] + b * on * (s1[i] - s2[i]) + detail[i]).collect::<Vec<f32>>())
            }
        }
    }

    fn solve(&mut self, g: &mut Gas, dt: f32, measure: bool) {
        let mut p = self.start(g, dt);
        for i in 0..p.len() { if g.solid[i] != 0 { p[i] = 0.0; } }
        if self.guard && self.steps > 0 {
            // the fuse: a guess that leaves more undone than last step's pressure as it is, is not used
            let plain: Vec<f32> = self.last.iter().map(|v| v * dt).collect();
            if left(g, &p) > left(g, &plain) { p = plain; self.fell_back += 1; }
        }
        let truth = if measure { Some(exact(g, &p)) } else { None };
        if let Some(t) = &truth {
            let size = slope(g, t).max(1.0e-12);
            let d: Vec<f32> = p.iter().zip(t).map(|(a, b)| a - b).collect();
            self.off_guess.push(slope(g, &d) / size);
        }
        match self.how {
            Solve::Rounds(n) => rounds(g, &mut p, &mut self.q, n),
            Solve::Own(n) => {
                g.params.rounds = n as f32;
                g.p = p;
                g.pressure();
                p = g.p.clone();
            }
            Solve::Steps { cycles, before, after, levels } => {
                let mut grids = vec![Level::of(g)];
                for _ in 0..levels { let next = grids.last().unwrap().coarser(); grids.push(next); }
                grids[0].p = p;
                for _ in 0..cycles { down_and_up(&mut grids, before, after); }
                p = std::mem::take(&mut grids[0].p);
            }
        }
        if let Some(t) = &truth {
            let size = slope(g, t).max(1.0e-12);
            let d: Vec<f32> = p.iter().zip(t).map(|(a, b)| a - b).collect();
            self.off.push(slope(g, &d) / size);
            let zero = vec![0.0f32; p.len()];
            self.left.push(left(g, &p) / left(g, &zero).max(1.0e-12));
        }
        self.before = std::mem::replace(&mut self.last, p.iter().map(|v| v / dt).collect());
        self.last_dt = dt;
        self.steps += 1;
        g.p = p;
    }
}

fn scene(name: &str, cpb: usize) -> Gas {
    let (bx, by, bz) = (12, 16, 12);
    let mut g = Gas::new(bx, by, bz, cpb, 1);
    for z in 0..bz { for x in 0..bx { for y in 0..2 { g.set_block(x, y, z, true, 0, 0.0); } } }
    match name {
        "fire" => g.set_block(6, 2, 6, false, kind::FIRE, 1.0),
        "room" => {
            g.set_block(6, 2, 6, false, kind::CAMPFIRE, 1.0);
            for z in 3..10 { for x in 3..10 { g.set_block(x, 7, z, true, 0, 0.0); } }
            for y in 2..7 { for x in 3..10 { g.set_block(x, y, 9, true, 0, 0.0); } }
        }
        _ => g.set_block(6, 2, 6, false, kind::CAMPFIRE, 1.0),
    }
    g
}

fn mean(v: &[f64]) -> f64 { if v.is_empty() { 0.0 } else { v.iter().sum::<f64>() / v.len() as f64 } }
fn worst(v: &[f64]) -> f64 { let mut s = v.to_vec(); s.sort_by(|a, b| a.partial_cmp(b).unwrap()); if s.is_empty() { 0.0 } else { s[(s.len() * 19 / 20).min(s.len() - 1)] } }

/// One step looked at closely: how far off the pressure is after each cycle of the solve in steps, from the same start.
fn closely(name: &str, cpb: usize, rate: f32) {
    let mut g = scene(name, cpb);
    let dt = 1.0 / rate;
    for _ in 0..(1.5 * rate) as usize { g.step(dt, &[]); }
    let mut seen = false;
    g.step_with(dt, &[], &mut |g: &mut Gas, _dt: f32| {
        if seen { return; }
        seen = true;
        let start = g.p.clone();
        let truth = exact(g, &start);
        let size = slope(g, &truth).max(1.0e-12);
        let off = |p: &[f32]| { let d: Vec<f32> = p.iter().zip(&truth).map(|(a, b)| a - b).collect(); slope(g, &d) / size };
        println!("start: off {:.4}", off(&start));
        let mut p = start.clone();
        let mut q = vec![0.0; p.len()];
        for n in [4, 8, 16, 32, 64] { let mut p2 = start.clone(); rounds(g, &mut p2, &mut q, n); println!("{:>3} rounds: off {:.4}", n, off(&p2)); }
        rounds(g, &mut p, &mut q, 0);
        for (before, after, levels) in [(1, 1, 1), (1, 1, 2), (2, 2, 2), (2, 2, 3), (1, 1, 3), (0, 2, 2), (2, 0, 2)] {
            let mut grids = vec![Level::of(g)];
            for _ in 0..levels { let next = grids.last().unwrap().coarser(); grids.push(next); }
            grids[0].p = start.clone();
            let mut line = format!("steps({},{})/{}:", before, after, levels);
            for _ in 0..5 { down_and_up(&mut grids, before, after); line += &format!(" {:.4}", off(&grids[0].p)); }
            println!("{}", line);
        }
    });
}

fn main() {
    let args: Vec<String> = std::env::args().collect();
    let name = args.get(1).map(String::as_str).unwrap_or("camp");
    if std::env::var("CLOSELY").is_ok() {
        closely(name, std::env::var("CPB").ok().and_then(|s| s.parse().ok()).unwrap_or(3), std::env::var("RATE").ok().and_then(|s| s.parse().ok()).unwrap_or(60.0));
        return;
    }
    let seconds: f32 = args.get(2).and_then(|s| s.parse().ok()).unwrap_or(4.0);
    let jitter = args.get(3).map(|s| s == "jitter").unwrap_or(false);
    let cpb: usize = std::env::var("CPB").ok().and_then(|s| s.parse().ok()).unwrap_or(3);
    let rate: f32 = std::env::var("RATE").ok().and_then(|s| s.parse().ok()).unwrap_or(60.0);
    let guard = std::env::var("GUARD").is_ok();
    let only: Option<Vec<String>> = std::env::var("ONLY").ok().map(|s| s.split(',').map(str::to_string).collect());
    let guesses = [Guess::Zero, Guess::Last, Guess::Scaled, Guess::Ahead(1.0), Guess::Ahead(0.5), Guess::Carried, Guess::Split(2, 1.0), Guess::Split(4, 1.0), Guess::Split(4, 0.5), Guess::SplitStill(4, 1.0)];
    // ROUNDS=4,8,16 for plain rounds; STEPS=cycles:before:after:levels,... for the solve in steps
    let mut ways: Vec<Solve> = std::env::var("ROUNDS").ok().map(|s| s.split(',').filter_map(|v| v.parse().ok()).map(Solve::Rounds).collect()).unwrap_or_else(|| if std::env::var("STEPS").is_ok() || std::env::var("OWN").is_ok() { vec![] } else { vec![4, 8, 16, 32].into_iter().map(Solve::Rounds).collect() });
    if let Ok(s) = std::env::var("OWN") { ways.extend(s.split(',').filter_map(|v| v.parse().ok()).map(Solve::Own)); }
    if let Ok(s) = std::env::var("STEPS") {
        for one in s.split(',') {
            let v: Vec<usize> = one.split(':').filter_map(|v| v.parse().ok()).collect();
            if v.len() == 4 { ways.push(Solve::Steps { cycles: v[0], before: v[1], after: v[2], levels: v[3] }); }
        }
    }
    println!("scene {}, {} s at {} steps a second{}, {} cells to the block{}", name, seconds, rate, if jitter { ", uneven" } else { "" }, cpb, if guard { ", with the fuse" } else { "" });
    println!("{:<10} {:>18} {:>6} {:>10} {:>10} {:>10} {:>10} {:>9}", "guess", "solve", "cost", "guess off", "off", "worst off", "left", "fell back");
    for guess in guesses {
        if let Some(only) = &only { if !only.contains(&guess.name()) { continue; } }
        for &how in &ways {
            let mut g = scene(name, cpb);
            let cells = g.p.len();
            let mut tried = Tried { guess, how, guard, last: vec![0.0; cells], before: vec![0.0; cells], last_dt: 1.0 / rate, q: vec![0.0; cells], off_guess: vec![], off: vec![], left: vec![], fell_back: 0, steps: 0 };
            let (mut t, mut step, mut seed) = (0.0f32, 0usize, 12345u32);
            let mut blobs: Vec<Blob> = Vec::new();
            while t < seconds {
                seed = seed.wrapping_mul(1664525).wrapping_add(1013904223);
                let dt = if jitter { (1.0 / rate) * (0.7 + 0.6 * (seed >> 8) as f32 / 16777216.0) } else { 1.0 / rate };
                blobs.clear();
                if name == "blast" {
                    let c = [6.0, 3.6, 6.0];
                    if t >= 1.5 && t < 1.62 { blobs.push(Blob { pos: c, radius: 2.4, fuel: 14.0, smoke: 6.0, heat: 1.4, soul: 0.0, vel: [0.0; 3], expand: 60.0 }); }
                    else if t >= 1.62 && t < 2.0 { blobs.push(Blob { pos: c, radius: 2.0, fuel: 0.0, smoke: 3.0, heat: 0.0, soul: 0.0, vel: [0.0; 3], expand: 0.0 }); }
                }
                let measure = t > 0.5 && step % 4 == 0;
                g.step_with(dt, &blobs, &mut |g: &mut Gas, dt: f32| tried.solve(g, dt, measure));
                t += dt;
                step += 1;
            }
            println!("{:<10} {:>18} {:>6.1} {:>10.4} {:>10.4} {:>10.4} {:>10.4} {:>9}", guess.name(), how.name(), how.cost(), mean(&tried.off_guess), mean(&tried.off), worst(&tried.off), mean(&tried.left), tried.fell_back);
        }
    }
}
