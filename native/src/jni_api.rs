//! JNI surface for `physicsbruhh.sim.NativeSim`. Buffers are direct, native-order float buffers.
//! Every entry point catches panics: a bug in here must never take the game down with it — the
//! Java side sees a negative return code and switches to its own implementation.

use crate::mesh;
use crate::sim::World;
use jni::objects::{JByteBuffer, JClass};
use jni::sys::{jboolean, jfloat, jint, jlong};
use jni::JNIEnv;
use std::panic::{catch_unwind, AssertUnwindSafe};

pub const ABI_VERSION: jint = 7;
const ERR: jint = -9999;

struct Handle { world: World, mesh: Vec<f32>, parts: [Vec<f32>; 2] }

unsafe fn handle<'a>(h: jlong) -> Option<&'a mut Handle> { (h as *mut Handle).as_mut() }

unsafe fn floats<'a>(env: &JNIEnv, buf: &JByteBuffer) -> Option<&'a mut [f32]> {
    let ptr = env.get_direct_buffer_address(buf).ok()?;
    let cap = env.get_direct_buffer_capacity(buf).ok()?;
    if ptr.is_null() || (ptr as usize) % std::mem::align_of::<f32>() != 0 { return None; }
    Some(std::slice::from_raw_parts_mut(ptr as *mut f32, cap / 4))
}

fn guard<F: FnOnce() -> jint>(f: F) -> jint { catch_unwind(AssertUnwindSafe(f)).unwrap_or(ERR) }

#[no_mangle]
pub extern "system" fn Java_physicsbruhh_sim_NativeSim_nVersion(_env: JNIEnv, _c: JClass) -> jint { ABI_VERSION }

#[no_mangle]
pub extern "system" fn Java_physicsbruhh_sim_NativeSim_nCreate(_env: JNIEnv, _c: JClass, n: jint, max_bodies: jint) -> jlong {
    // room for a few bodies to begin with; more is made as they are added, up to `max_bodies`
    catch_unwind(|| Box::into_raw(Box::new(Handle { world: World::with_room(n.max(1) as usize, (max_bodies.max(1) as usize).min(8), max_bodies.max(1) as usize), mesh: Vec::new(), parts: [Vec::new(), Vec::new()] })) as jlong).unwrap_or(0)
}

#[no_mangle]
pub extern "system" fn Java_physicsbruhh_sim_NativeSim_nDestroy(_env: JNIEnv, _c: JClass, h: jlong) {
    if h != 0 { let _ = catch_unwind(|| unsafe { drop(Box::from_raw(h as *mut Handle)) }); }
}

#[no_mangle]
pub extern "system" fn Java_physicsbruhh_sim_NativeSim_nAddBody(_env: JNIEnv, _c: JClass, h: jlong, mat: jint, x: jfloat, y: jfloat, z: jfloat, dirx: jfloat, dirz: jfloat, age: jfloat, thick: jfloat) -> jint {
    guard(|| match unsafe { handle(h) } { Some(hd) => hd.world.add_body_thick(mat as u8, x, y, z, dirx, dirz, age, thick), None => ERR })
}

#[no_mangle]
pub extern "system" fn Java_physicsbruhh_sim_NativeSim_nAddEmpty(_env: JNIEnv, _c: JClass, h: jlong, mat: jint, x: jfloat, y: jfloat, z: jfloat, thick: jfloat) -> jint {
    guard(|| match unsafe { handle(h) } { Some(hd) => hd.world.add_empty_thick(mat as u8, x, y, z, thick), None => ERR })
}

#[no_mangle]
pub extern "system" fn Java_physicsbruhh_sim_NativeSim_nAddResting(_env: JNIEnv, _c: JClass, h: jlong, mat: jint, x: jfloat, y: jfloat, z: jfloat, count: jint, thick: jfloat) -> jint {
    guard(|| match unsafe { handle(h) } { Some(hd) => hd.world.add_resting_thick(mat as u8, x, y, z, count.max(0) as u32, thick), None => ERR })
}

/// Stream (`lump` false) or lump of `count` particles into `body`, from a point with a velocity. Returns how many it had room for.
#[no_mangle]
pub extern "system" fn Java_physicsbruhh_sim_NativeSim_nEmit(_env: JNIEnv, _c: JClass, h: jlong, body: jint, px: jfloat, py: jfloat, pz: jfloat, vx: jfloat, vy: jfloat, vz: jfloat, count: jint, lump: jboolean) -> jint {
    guard(|| match unsafe { handle(h) } { Some(hd) => if body >= 0 { hd.world.emit(body as usize, [px, py, pz], [vx, vy, vz], count.max(0) as u32, lump != 0) as jint } else { 0 }, None => ERR })
}

/// Removes the `count` particles of `body` nearest to a point. Returns how many were removed.
#[no_mangle]
pub extern "system" fn Java_physicsbruhh_sim_NativeSim_nTake(_env: JNIEnv, _c: JClass, h: jlong, body: jint, x: jfloat, y: jfloat, z: jfloat, count: jint) -> jint {
    guard(|| match unsafe { handle(h) } { Some(hd) => if body >= 0 { hd.world.take(body as usize, [x, y, z], count.max(0) as u32) as jint } else { 0 }, None => ERR })
}

#[no_mangle]
pub extern "system" fn Java_physicsbruhh_sim_NativeSim_nRemoveBody(_env: JNIEnv, _c: JClass, h: jlong, body: jint, fade: jboolean) -> jint {
    guard(|| match unsafe { handle(h) } { Some(hd) if body >= 0 => { hd.world.remove_body(body as usize, fade != 0); 0 } _ => ERR })
}

#[no_mangle]
pub extern "system" fn Java_physicsbruhh_sim_NativeSim_nWake(_env: JNIEnv, _c: JClass, h: jlong, body: jint) -> jint {
    guard(|| match unsafe { handle(h) } { Some(hd) if body >= 0 => { hd.world.wake(body as usize); 0 } _ => ERR })
}

/// Particles inside a box: honey count in the low 16 bits, slime count in the next 16 (each capped at 32767).
#[no_mangle]
pub extern "system" fn Java_physicsbruhh_sim_NativeSim_nProbe(_env: JNIEnv, _c: JClass, h: jlong, x0: jfloat, y0: jfloat, z0: jfloat, x1: jfloat, y1: jfloat, z1: jfloat) -> jint {
    guard(|| match unsafe { handle(h) } {
        Some(hd) => { let (honey, slime) = hd.world.probe([x0, y0, z0], [x1, y1, z1]); (honey.min(32767) | slime.min(32767) << 16) as jint }
        None => ERR,
    })
}

/// Diagnostics: `what` 0 = particles stuck to the moving box with id `arg`, 1 = particles of body `arg` stuck to blocks,
/// 2 = fixed steps taken so far (wraps), 3 = threads the solver uses, 4 = particles in body `arg` (with those of a stream still to come).
#[no_mangle]
pub extern "system" fn Java_physicsbruhh_sim_NativeSim_nCount(_env: JNIEnv, _c: JClass, h: jlong, what: jint, arg: jint) -> jint {
    guard(|| match unsafe { handle(h) } {
        Some(hd) => match what {
            0 => hd.world.anchors_on(arg) as jint,
            1 => hd.world.anchors_of(arg.max(0) as usize) as jint,
            2 => (hd.world.ticks & 0x3fff_ffff) as jint,
            3 => crate::sim::threads() as jint,
            4 => hd.world.amount(arg.max(0) as usize) as jint,
            _ => 0,
        },
        None => ERR,
    })
}

#[no_mangle]
pub extern "system" fn Java_physicsbruhh_sim_NativeSim_nSetKillY(_env: JNIEnv, _c: JClass, h: jlong, y: jfloat) -> jint {
    guard(|| match unsafe { handle(h) } { Some(hd) => { hd.world.kill_y = y; 0 } None => ERR })
}

#[no_mangle]
pub extern "system" fn Java_physicsbruhh_sim_NativeSim_nSetStatic(env: JNIEnv, _c: JClass, h: jlong, buf: JByteBuffer, count: jint) -> jint {
    guard(|| unsafe {
        let (hd, data) = match (handle(h), floats(&env, &buf)) { (Some(a), Some(b)) => (a, b), _ => return ERR };
        hd.world.set_static(data, count.max(0) as usize); 0
    })
}

#[no_mangle]
pub extern "system" fn Java_physicsbruhh_sim_NativeSim_nSetKinematic(env: JNIEnv, _c: JClass, h: jlong, buf: JByteBuffer, count: jint) -> jint {
    guard(|| unsafe {
        let (hd, data) = match (handle(h), floats(&env, &buf)) { (Some(a), Some(b)) => (a, b), _ => return ERR };
        hd.world.set_kinematic(data, count.max(0) as usize); 0
    })
}

/// Returns the number of awake bodies.
#[no_mangle]
pub extern "system" fn Java_physicsbruhh_sim_NativeSim_nStep(_env: JNIEnv, _c: JClass, h: jlong, dt: jfloat) -> jint {
    guard(|| match unsafe { handle(h) } { Some(hd) => hd.world.step(dt) as jint, None => ERR })
}

/// Writes quads (24 floats each) for one material. Returns the quad count, or minus the number
/// of floats needed when the buffer is too small.
#[no_mangle]
pub extern "system" fn Java_physicsbruhh_sim_NativeSim_nMesh(env: JNIEnv, _c: JClass, h: jlong, mat: jint, buf: JByteBuffer) -> jint {
    guard(|| unsafe {
        let (hd, out) = match (handle(h), floats(&env, &buf)) { (Some(a), Some(b)) => (a, b), _ => return ERR };
        hd.mesh.clear();
        let quads = mesh::build_parts(&hd.world, mat as u8, &mut hd.mesh, &mut hd.parts[mat as usize & 1]);
        if hd.mesh.len() > out.len() { return -(hd.mesh.len() as jint); }
        out[..hd.mesh.len()].copy_from_slice(&hd.mesh);
        quads as jint
    })
}

/// Where the puddles of the last surface built for a material are: for each, its first quad, its number
/// of quads and the box it lies in (8 floats). Returns the number of puddles, or minus the number
/// of floats needed when the buffer is too small.
#[no_mangle]
pub extern "system" fn Java_physicsbruhh_sim_NativeSim_nMeshParts(env: JNIEnv, _c: JClass, h: jlong, mat: jint, buf: JByteBuffer) -> jint {
    guard(|| unsafe {
        let (hd, out) = match (handle(h), floats(&env, &buf)) { (Some(a), Some(b)) => (a, b), _ => return ERR };
        let parts = &hd.parts[mat as usize & 1];
        if parts.len() > out.len() { return -(parts.len() as jint); }
        out[..parts.len()].copy_from_slice(parts);
        (parts.len() / mesh::PART_FLOATS) as jint
    })
}

/// Writes every particle as position + (size 0..1, plus 2 for slime). Returns the particle count, or
/// minus the number of floats needed when the buffer is too small.
#[no_mangle]
pub extern "system" fn Java_physicsbruhh_sim_NativeSim_nParticles(env: JNIEnv, _c: JClass, h: jlong, buf: JByteBuffer) -> jint {
    guard(|| unsafe {
        let (hd, out) = match (handle(h), floats(&env, &buf)) { (Some(a), Some(b)) => (a, b), _ => return ERR };
        hd.world.particles(out)
    })
}

/// Fills 8 floats per body slot: state, awake, then the bounding box, for as many slots as the buffer
/// has room for. Returns the number of slots there are (more than fit: ask again with a larger buffer).
#[no_mangle]
pub extern "system" fn Java_physicsbruhh_sim_NativeSim_nBodies(env: JNIEnv, _c: JClass, h: jlong, buf: JByteBuffer) -> jint {
    guard(|| unsafe {
        let (hd, out) = match (handle(h), floats(&env, &buf)) { (Some(a), Some(b)) => (a, b), _ => return ERR };
        let n = hd.world.slots;
        for b in 0..n.min(out.len() / 8) {
            let bd = &hd.world.bodies[b];
            out[b * 8] = bd.state as f32; out[b * 8 + 1] = if bd.awake { 1.0 } else { 0.0 };
            out[b * 8 + 2..b * 8 + 8].copy_from_slice(&bd.bb);
        }
        n as jint
    })
}

// ---------------------------------------------------------------- gas (`physicsbruhh.sim.NativeGas`)

use crate::gas::{Blob, Gas, Params, BLOB_FLOATS, PARAM_COUNT, WORLD_BYTES};

unsafe fn gas<'a>(h: jlong) -> Option<&'a mut Gas> { (h as *mut Gas).as_mut() }

unsafe fn bytes<'a>(env: &JNIEnv, buf: &JByteBuffer) -> Option<&'a mut [u8]> {
    let ptr = env.get_direct_buffer_address(buf).ok()?;
    let cap = env.get_direct_buffer_capacity(buf).ok()?;
    if ptr.is_null() { return None; }
    Some(std::slice::from_raw_parts_mut(ptr, cap))
}

#[no_mangle]
pub extern "system" fn Java_physicsbruhh_sim_NativeGas_nCreate(_env: JNIEnv, _c: JClass, bx: jint, by: jint, bz: jint, cpb: jint, ratio: jint) -> jlong {
    catch_unwind(|| {
        if bx < 2 || by < 2 || bz < 2 || cpb < 1 || ratio < 1 || (bx as i64 * by as i64 * bz as i64) * (cpb as i64 * ratio as i64).pow(3) > 64_000_000 { return 0; }
        Box::into_raw(Box::new(Gas::new(bx as usize, by as usize, bz as usize, cpb as usize, ratio as usize))) as jlong
    }).unwrap_or(0)
}

#[no_mangle]
pub extern "system" fn Java_physicsbruhh_sim_NativeGas_nDestroy(_env: JNIEnv, _c: JClass, h: jlong) {
    if h != 0 { let _ = catch_unwind(|| unsafe { drop(Box::from_raw(h as *mut Gas)) }); }
}

/// The solver's own numbers, in the order of `GasParams`. Returns how many there are.
#[no_mangle]
pub extern "system" fn Java_physicsbruhh_sim_NativeGas_nDefaults(env: JNIEnv, _c: JClass, out: JByteBuffer) -> jint {
    guard(|| match unsafe { floats(&env, &out) } {
        Some(o) if o.len() >= PARAM_COUNT => { o[..PARAM_COUNT].copy_from_slice(&Params::default().to_array()); PARAM_COUNT as jint }
        _ => ERR,
    })
}

#[no_mangle]
pub extern "system" fn Java_physicsbruhh_sim_NativeGas_nParams(env: JNIEnv, _c: JClass, h: jlong, values: JByteBuffer) -> jint {
    guard(|| match (unsafe { gas(h) }, unsafe { floats(&env, &values) }) {
        (Some(g), Some(v)) => match Params::from_slice(v) { Some(p) => { g.params = p; 0 } None => ERR },
        _ => ERR,
    })
}

/// One step: the map of the world in the box (four bytes a block), the blobs (twelve numbers each), and how many blocks the box has moved since the last step.
#[no_mangle]
pub extern "system" fn Java_physicsbruhh_sim_NativeGas_nStep(env: JNIEnv, _c: JClass, h: jlong, dt: jfloat, world: JByteBuffer, blobs: JByteBuffer, blob_count: jint, dx: jint, dy: jint, dz: jint) -> jint {
    guard(|| match (unsafe { gas(h) }, unsafe { bytes(&env, &world) }, unsafe { floats(&env, &blobs) }) {
        (Some(g), Some(w), Some(b)) => {
            if w.len() < g.bx * g.by * g.bz * WORLD_BYTES { return ERR; }
            let n = (blob_count.max(0) as usize).min(b.len() / BLOB_FLOATS);
            let list: Vec<Blob> = (0..n).map(|i| Blob::from_slice(&b[i * BLOB_FLOATS..(i + 1) * BLOB_FLOATS])).collect();
            g.shift(dx, dy, dz);
            g.set_world(w);
            g.step(dt, &list);
            0
        }
        _ => ERR,
    })
}

/// The fine grid as the picture the renderer reads, `tiles` layers to a row. Returns the bytes written.
#[no_mangle]
pub extern "system" fn Java_physicsbruhh_sim_NativeGas_nAtlas(env: JNIEnv, _c: JClass, h: jlong, out: JByteBuffer, tiles: jint) -> jint {
    guard(|| match (unsafe { gas(h) }, unsafe { bytes(&env, &out) }) {
        (Some(g), Some(o)) => {
            let tiles = tiles.max(1) as usize;
            let need = tiles * g.fx * ((g.fy + tiles - 1) / tiles) * g.fz * 4;
            if o.len() < need { return ERR; }
            g.write_atlas(o, tiles);
            need as jint
        }
        _ => ERR,
    })
}

/// Smoke in all, heat in all, the hottest cell, the fastest cell, then the smoke in each layer of blocks from the bottom up. Returns how many numbers.
#[no_mangle]
pub extern "system" fn Java_physicsbruhh_sim_NativeGas_nTotals(env: JNIEnv, _c: JClass, h: jlong, out: JByteBuffer) -> jint {
    guard(|| match (unsafe { gas(h) }, unsafe { floats(&env, &out) }) {
        (Some(g), Some(o)) => {
            if o.len() < 4 + g.by { return ERR; }
            o[..4].copy_from_slice(&g.totals());
            let fpb = g.cpb * g.ratio;
            for (y, chunk) in g.smoke_by_height().chunks(fpb).enumerate() { o[4 + y] = chunk.iter().sum(); }
            (4 + g.by) as jint
        }
        _ => ERR,
    })
}

#[no_mangle]
pub extern "system" fn Java_physicsbruhh_sim_NativeGas_nClear(_env: JNIEnv, _c: JClass, h: jlong) -> jint {
    guard(|| match unsafe { gas(h) } { Some(g) => { g.clear(); 0 } None => ERR })
}
