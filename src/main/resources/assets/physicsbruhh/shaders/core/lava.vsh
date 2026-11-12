#version 330
#extension GL_ARB_separate_shader_objects : require

// Lava: the surface the mod draws in place of the game's own lava (LavaMesher makes it, a section of
// the world at a time). There are no vertex buffers: four corners a face, two texels a corner, looked
// up by number; the first texel of the buffer says which section it is.

#include <minecraft:fog.glsl>
#include <minecraft:globals.glsl>
#include <minecraft:projection.glsl>
#include <physicsbruhh:lava_frame.glsl>

uniform samplerBuffer LavaMesh;

layout(location = 0) out vec3 place;        // where in the world, blocks (started over every 1024), for the patterns
layout(location = 1) out vec3 facing;       // which way the surface faces
layout(location = 2) out vec4 look;         // heat 0..1, how far from the edge 0..1 (more than 1: a thin skin, by how much), light of the sky 0..1, how lately it moved 0..1
layout(location = 3) out vec3 flow;         // which way it runs (x, z) and what kind of face it is
layout(location = 4) out vec3 fromCamera;   // where it is, seen from the camera, along the world's axes
layout(location = 5) out vec2 fogDistance;

vec4 corner(int at) {
    return texelFetch(LavaMesh, at);
}

void main() {
    vec4 head = corner(0);
    int at = 1 + gl_VertexIndex * 2;
    vec4 a = corner(at), b = corner(at + 1);
    int bits = int(a.w + 0.5);
    int kind = (bits >> 8) & 3;
    int packedFlow = int(b.z + 0.5), packedFacing = int(b.w + 0.5);

    // a height on the move: from where it was to where the blocks now say, eased
    float moved = clamp((LavaClock.x - b.y) / LavaClock.z, 0.0, 1.0);
    float y = mix(b.x, a.y, moved * moved * (3.0 - 2.0 * moved));

    ivec3 corner0 = ivec3(floor(head.xyz + 0.5)) * 16;
    vec3 local = vec3(a.x, y, a.z);
    vec3 pos = local + vec3(corner0 - CameraBlockPos) + CameraOffset;
    gl_Position = ProjMat * LavaView * vec4(pos, 1.0);

    vec2 n = vec2(float(packedFacing & 4095), float((packedFacing >> 12) & 4095)) / 4095.0 * 2.0 - 1.0;
    facing = kind == LAVA_UNDER ? vec3(0.0, -1.0, 0.0) : vec3(n.x, sqrt(max(0.0, 1.0 - dot(n, n))), n.y);
    place = vec3(corner0 & 1023) + local;
    look = vec4(float((bits >> 10) & 63) / 63.0, float((bits >> 16) & 15) / 15.0, float(bits & 15) / 15.0,
            1.0 - clamp((LavaClock.x - b.y - LavaClock.z) / 8.0, 0.0, 1.0));
    flow = vec3(vec2(float(packedFlow & 255), float((packedFlow >> 8) & 255)) / 255.0 * 2.0 - 1.0, float(kind));
    fromCamera = pos;
    fogDistance = vec2(fog_spherical_distance(pos), fog_cylindrical_distance(pos));
}
