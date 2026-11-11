#version 330
#extension GL_ARB_separate_shader_objects : require

// Gas, step 6, second half: the carrying made sharp again, fuel that is hot enough burnt
// into heat and smoke, heat lost and smoke thinned, and what burning blocks, burning creatures and
// explosions put in. Red smoke, green heat, blue fuel, alpha how much of the fuel and heat is soul fire's.

#include <physicsbruhh:gas.glsl>
#include <physicsbruhh:gas_carry.glsl>

uniform sampler2D CarriedSampler;   // first half
uniform sampler2D GasSampler;       // smoke, heat, fuel after the last step
uniform sampler2D VelSampler;       // step 5
uniform sampler2D WorldSampler;

layout(location = 0) out vec4 fragColor;

float share(float soul, float fuel, float heat) {
    return fuel + heat > 1.0e-6 ? clamp(soul / (fuel + heat), 0.0, 1.0) : 0.0;
}

void main() {
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    ivec3 c = gasCell(GasFine, pixel);
    float fpbf = GasStep.z * GasStep.w;
    int fpb = int(fpbf);
    ivec3 inBlock = c / fpb;
    vec4 block = c.y < 0 ? vec4(1.0) : gasBlock(WorldSampler, inBlock);
    if (block.r > 0.5) { fragColor = vec4(0.0); return; }
    float dt = GasStep.x;
    float time = GasStep.y;
    vec3 p = vec3(c) + 0.5;
    vec4 v = texelFetch(CarriedSampler, pixel, 0);

    {
        // carrying is a blur; carried back again it shows how much of one, and half of that is put back,
        // within what the cells the gas came from held
        ivec3 movedCells = ivec3(GasMoved.xyz) * fpb;
        vec3 q = gasCameFrom(VelSampler, WorldSampler, c);
        vec4 back = gasAt(CarriedSampler, GasFine, 2.0 * p - q);
        vec4 was = gasFetch(GasSampler, GasFine, c + movedCells);
        vec3 at = clamp(q + vec3(movedCells), vec3(0.5), GasFine.xyz - 0.5) - 0.5;
        ivec3 a = ivec3(floor(at));
        ivec3 b = min(a + 1, ivec3(GasFine.xyz) - 1);
        vec4 lo = gasFetch(GasSampler, GasFine, a), hi = lo, w;
        w = gasFetch(GasSampler, GasFine, ivec3(b.x, a.y, a.z)); lo = min(lo, w); hi = max(hi, w);
        w = gasFetch(GasSampler, GasFine, ivec3(a.x, a.y, b.z)); lo = min(lo, w); hi = max(hi, w);
        w = gasFetch(GasSampler, GasFine, ivec3(b.x, a.y, b.z)); lo = min(lo, w); hi = max(hi, w);
        w = gasFetch(GasSampler, GasFine, ivec3(a.x, b.y, a.z)); lo = min(lo, w); hi = max(hi, w);
        w = gasFetch(GasSampler, GasFine, ivec3(b.x, b.y, a.z)); lo = min(lo, w); hi = max(hi, w);
        w = gasFetch(GasSampler, GasFine, ivec3(a.x, b.y, b.z)); lo = min(lo, w); hi = max(hi, w);
        w = gasFetch(GasSampler, GasFine, ivec3(b.x, b.y, b.z)); lo = min(lo, w); hi = max(hi, w);
        v = clamp(v + 0.5 * (was - back), lo, hi);
    }

    float smoke = v.r, heat = v.g, fuel = v.b, soul = v.a;

    // fuel that is hot enough burns
    if (heat > GasB.x && fuel > 0.0) {
        float burnt = fuel * (1.0 - exp(-GasB.y * dt));
        soul += (GasB.z - 1.0) * burnt * share(soul, fuel, heat);
        fuel -= burnt;
        heat += GasB.z * burnt;
        smoke += GasB.w * burnt;
    }

    // heat is lost, smoke thins
    float cooled = heat / (1.0 + dt * (GasA.z + GasA.w * heat * heat * heat));
    soul -= (heat - cooled) * share(soul, fuel, heat);
    heat = cooled;
    smoke *= exp(-GasC.x * dt);

    // blocks that burn
    vec3 place = p / fpbf;
    int kind = int(block.g);
    if (kind != 0) {
        float much = block.b;
        float blue = (kind & 16) != 0 ? 1.0 : 0.0;
        float live = 0.55 + 0.9 * gasFlicker(uint(c.x) / 2u, uint(c.y) / 2u, uint(c.z) / 2u, time, 5.0);
        vec3 src = gasSource(kind & 15, place - vec3(inBlock), 0.5 / fpbf, place, time, live);
        fuel += dt * much * src.x;
        soul += dt * much * src.x * blue;
        smoke += dt * much * src.y;
        float to = src.z * min(much, 1.0);
        if (to > heat) { soul += (to - heat) * blue; heat = to; }
    }

    // creatures that burn, explosions
    int blobs = int(GasMoved.w);
    for (int i = 0; i < blobs; i++) {
        vec4 ball = GasBlob[i * 3];
        vec3 d = place - ball.xyz;
        float d2 = dot(d, d);
        if (d2 < ball.w * ball.w) {
            float near = 1.0 - d2 / (ball.w * ball.w);
            vec4 puts = GasBlob[i * 3 + 1];
            fuel += dt * near * puts.x;
            soul += dt * near * puts.x * puts.w;
            smoke += dt * near * puts.y;
            float to = puts.z * near;
            if (to > heat) { soul += (to - heat) * puts.w; heat = to; }
        }
    }

    // nothing at the edge of the box: gas that leaves it is gone, and is not seen to be cut off
    ivec3 rim = min(c, ivec3(GasFine.xyz) - 1 - c);
    float edge = min(float(min(rim.x, min(rim.y, rim.z))) / GasD.z, 1.0);
    heat = clamp(heat * edge, 0.0, GasE.x);
    fuel = clamp(fuel * edge, 0.0, GasE.y);
    fragColor = vec4(clamp(smoke * edge, 0.0, GasD.w), heat, fuel, clamp(soul * edge, 0.0, heat + fuel));
}
