#version 330
#extension GL_ARB_separate_shader_objects : require

// Liquid look, before the goo is counted: how far away the world is around each pixel, the farthest
// of the pixel itself and a ring of pixels about it. The pass that adds up the goo along each line
// of sight leaves out what lies behind that: goo behind a wall does not make the goo in front of it
// look thicker. Taking the farthest of a neighbourhood keeps thin things in front (the streaks and
// the frame of a pane of glass, the edge of a block) from cutting pale holes and fringes into the
// goo behind them; whether a pixel shows goo at all is decided pixel by pixel in the last pass.

#include <minecraft:projection.glsl>
#include <physicsbruhh:goo_frame.glsl>

uniform sampler2D SceneDepthSampler;

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 fragColor;

const float REACH = 5.0;   // pixels

float sceneAlong(mat4 unproject, vec2 uv) {
    float z = texture(SceneDepthSampler, uv).r;
    if (GooSun.w < 0.5) {
        z = z * 2.0 - 1.0;
    }
    vec4 p = unproject * vec4(uv * 2.0 - 1.0, z, 1.0);
    return -p.z / p.w;
}

void main() {
    mat4 unproject = inverse(ProjMat);
    vec2 pixel = 1.0 / vec2(textureSize(SceneDepthSampler, 0));
    float far = sceneAlong(unproject, texCoord);
    for (int k = 0; k < 8; k++) {
        float turn = float(k) * 0.7853982;
        vec2 way = vec2(cos(turn), sin(turn)) * pixel;
        far = max(far, sceneAlong(unproject, clamp(texCoord + way * REACH, vec2(0.0), vec2(1.0))));
        if ((k & 1) == 0) {
            far = max(far, sceneAlong(unproject, clamp(texCoord + way * REACH * 0.5, vec2(0.0), vec2(1.0))));
        }
    }
    fragColor = vec4(far, 0.0, 0.0, 1.0);
}
