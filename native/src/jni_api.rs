//! JNI surface for `physicsbruhh.sim.NativeSim`. Buffers are direct, native-order float buffers.
//! Every entry point catches panics: a bug in here must never take the game down with it — the
//! Java side sees a negative return code and switches to its own implementation.

use crate::mesh;
use crate::sim::World;
use jni::objects::{JByteBuffer, JClass};
use jni::sys::{jboolean, jfloat, jint, jlong};
use jni::JNIEnv;
use std::panic::{catch_unwind, AssertUnwindSafe};

pub const ABI_VERSION: jint = 4;
const ERR: jint = -9999;

struct Handle { world: World, mesh: Vec<f32> }

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
    catch_unwind(|| Box::into_raw(Box::new(Handle { world: World::new(n.max(1) as usize, max_bodies.max(1) as usize), mesh: Vec::new() })) as jlong).unwrap_or(0)
}

#[no_mangle]
pub extern "system" fn Java_physicsbruhh_sim_NativeSim_nDestroy(_env: JNIEnv, _c: JClass, h: jlong) {
    if h != 0 { let _ = catch_unwind(|| unsafe { drop(Box::from_raw(h as *mut Handle)) }); }
}

#[no_mangle]
pub extern "system" fn Java_physicsbruhh_sim_NativeSim_nAddBody(_env: JNIEnv, _c: JClass, h: jlong, mat: jint, x: jfloat, y: jfloat, z: jfloat, dirx: jfloat, dirz: jfloat, age: jfloat) -> jint {
    guard(|| match unsafe { handle(h) } { Some(hd) => hd.world.add_body(mat as u8, x, y, z, dirx, dirz, age), None => ERR })
}

#[no_mangle]
pub extern "system" fn Java_physicsbruhh_sim_NativeSim_nAddEmpty(_env: JNIEnv, _c: JClass, h: jlong, mat: jint, x: jfloat, y: jfloat, z: jfloat) -> jint {
    guard(|| match unsafe { handle(h) } { Some(hd) => hd.world.add_empty(mat as u8, x, y, z), None => ERR })
}

#[no_mangle]
pub extern "system" fn Java_physicsbruhh_sim_NativeSim_nAddResting(_env: JNIEnv, _c: JClass, h: jlong, mat: jint, x: jfloat, y: jfloat, z: jfloat, count: jint) -> jint {
    guard(|| match unsafe { handle(h) } { Some(hd) => hd.world.add_resting(mat as u8, x, y, z, count.max(0) as u32), None => ERR })
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
        let quads = mesh::build(&hd.world, mat as u8, &mut hd.mesh);
        if hd.mesh.len() > out.len() { return -(hd.mesh.len() as jint); }
        out[..hd.mesh.len()].copy_from_slice(&hd.mesh);
        quads as jint
    })
}

/// Fills 8 floats per body slot: state, awake, then the bounding box. Returns the slot count.
#[no_mangle]
pub extern "system" fn Java_physicsbruhh_sim_NativeSim_nBodies(env: JNIEnv, _c: JClass, h: jlong, buf: JByteBuffer) -> jint {
    guard(|| unsafe {
        let (hd, out) = match (handle(h), floats(&env, &buf)) { (Some(a), Some(b)) => (a, b), _ => return ERR };
        let n = hd.world.slots.min(out.len() / 8);
        for b in 0..n {
            let bd = &hd.world.bodies[b];
            out[b * 8] = bd.state as f32; out[b * 8 + 1] = if bd.awake { 1.0 } else { 0.0 };
            out[b * 8 + 2..b * 8 + 8].copy_from_slice(&bd.bb);
        }
        n as jint
    })
}
