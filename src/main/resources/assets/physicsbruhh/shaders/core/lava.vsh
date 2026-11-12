#version 330
#extension GL_ARB_separate_shader_objects : require

// Lava: the surface the mod draws in place of the game's own lava (LavaMesher makes it, a section of
// the world at a time). There are no vertex buffers: four corners a face, two texels a corner, looked
// up by number; the first texel of the buffer says which section it is.
//
// With LAVA_CUT the faces are the tops of calm blocks, a face a block, and each is cut here into
// six by six along the same lines the bent blocks around it are cut along: that is what gives the
// waves something to move in the middle of a lake.
//
// The waves (lava_waves.fsh) lift and tilt every corner that belongs to the top.

#include <minecraft:fog.glsl>
#include <minecraft:globals.glsl>
#include <minecraft:projection.glsl>
#include <physicsbruhh:lava_frame.glsl>
#include <physicsbruhh:lava_waves.glsl>

uniform samplerBuffer LavaMesh;

layout(location = 0) out vec3 place;        // where in the world, blocks (started over every 1024), for the patterns
layout(location = 1) out vec3 facing;       // which way the surface faces
layout(location = 2) out vec4 look;         // heat 0..1, how far from the edge 0..1 (more than 1: a thin skin, by how much), light of the sky 0..1, how lately it moved 0..1
layout(location = 3) out vec3 flow;         // which way it runs (x, z) and what kind of face it is
layout(location = 4) out vec3 fromCamera;   // where it is, seen from the camera, along the world's axes
layout(location = 5) out vec2 fogDistance;

vec4 texel(int at) {
    return texelFetch(LavaMesh, at);
}

// A corner as the mesh has it: where (in blocks from the section's least corner), which way it faces, how it
// looks, which way it runs and what kind of face it is of, and how much of the waves it takes.
void corner(int at, out vec3 spot, out vec3 normal, out vec4 seen, out vec3 runs, out float sway) {
    vec4 a = texel(at), b = texel(at + 1);
    // (whole numbers up to 2^24, kept exactly: nothing is added before they are taken apart)
    int bits = int(a.w);
    int kind = (bits >> 8) & 3;
    int packedFlow = int(b.z), packedFacing = int(b.w);
    // a height on the move: from where it was to where the blocks now say, eased
    float moved = clamp((LavaClock.x - b.y) / LavaClock.z, 0.0, 1.0);
    spot = vec3(a.x, mix(b.x, a.y, moved * moved * (3.0 - 2.0 * moved)), a.z);
    vec2 n = vec2(float(packedFacing & 4095), float((packedFacing >> 12) & 4095)) / 4095.0 * 2.0 - 1.0;
    normal = kind == LAVA_UNDER ? vec3(0.0, -1.0, 0.0) : vec3(n.x, sqrt(max(0.0, 1.0 - dot(n, n))), n.y);
    seen = vec4(float((bits >> 10) & 63) / 63.0, float((bits >> 16) & 15) / 15.0, float(bits & 15) / 15.0,
            1.0 - clamp((LavaClock.x - b.y - LavaClock.z) / 8.0, 0.0, 1.0));
    runs = vec3(vec2(float(packedFlow & 255), float((packedFlow >> 8) & 255)) / 255.0 * 2.0 - 1.0, float(kind));
    sway = float((bits >> 23) & 1) * float((bits >> 20) & 7) / 7.0;
}

void main() {
    vec4 head = texel(0);
    vec3 local, normal, runs;
    vec4 seen;
    float sway;
#ifdef LAVA_CUT
    const float LINES[7] = float[7](0.0, 0.07, 0.25, 0.5, 0.75, 0.93, 1.0);
    int face = gl_VertexIndex / 4, k = gl_VertexIndex % 4;
    int cut = face % 36, first = 1 + (face / 36) * 8;
    float u = LINES[cut % 6 + ((k == 2 || k == 3) ? 1 : 0)], v = LINES[cut / 6 + ((k == 1 || k == 2) ? 1 : 0)];
    // the block's four corners: least x and z, then greater z, then both greater, then greater x
    vec3 p0, p1, p2, p3, n0, n1, n2, n3, r0, r1, r2, r3;
    vec4 s0, s1, s2, s3;
    float w0, w1, w2, w3;
    corner(first, p0, n0, s0, r0, w0);
    corner(first + 2, p1, n1, s1, r1, w1);
    corner(first + 4, p2, n2, s2, r2, w2);
    corner(first + 6, p3, n3, s3, r3, w3);
    float m0 = (1.0 - u) * (1.0 - v), m1 = (1.0 - u) * v, m2 = u * v, m3 = u * (1.0 - v);
    local = p0 * m0 + p1 * m1 + p2 * m2 + p3 * m3;
    normal = normalize(n0 * m0 + n1 * m1 + n2 * m2 + n3 * m3);
    seen = s0 * m0 + s1 * m1 + s2 * m2 + s3 * m3;
    runs = r0 * m0 + r1 * m1 + r2 * m2 + r3 * m3;
    sway = w0 * m0 + w1 * m1 + w2 * m2 + w3 * m3;
#else
    corner(1 + gl_VertexIndex * 2, local, normal, seen, runs, sway);
#endif

    ivec3 corner0 = ivec3(floor(head.xyz + 0.5)) * 16;
    vec3 rel = local + vec3(corner0 - CameraBlockPos);
    vec4 wave = lavaWave(rel, sway);
    local.y += wave.x;
    rel.y += wave.x;
    normal = normalize(normal + vec3(-wave.y, 0.0, -wave.z));
    // (lava that has just been stirred is hot where it was: the crust there is torn)
    seen.w = max(seen.w, wave.w);

    vec3 pos = rel + CameraOffset;
    gl_Position = ProjMat * LavaView * vec4(pos, 1.0);

    facing = normal;
    place = vec3(corner0 & 1023) + local;
    look = seen;
    flow = runs;
    fromCamera = pos;
    fogDistance = vec2(fog_spherical_distance(pos), fog_cylindrical_distance(pos));
}
