#version 330
#extension GL_ARB_separate_shader_objects : require

// Gas, into the picture. Light adds up as light, not as the numbers a screen is given: what is
// behind is turned back into light, dimmed by the smoke, the gas's own light is added, and the sum
// is taken the way a camera would take it (a bright flame goes to yellow and white rather than
// being cut off) and turned into screen numbers again. What is seen through hot gas is shifted a
// little, by how the amount of hot gas along the line of sight changes from pixel to pixel: the
// shimmer over a fire. Pixels with no gas in them are left alone.

uniform sampler2D InSampler;      // gas_march.fsh: the gas's light, and how much of what is behind still shows
uniform sampler2D HazeSampler;    // gas_haze.fsh: how much hot gas lies along each line of sight
uniform sampler2D SceneSampler;   // the picture so far

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 fragColor;

// How far the picture behind is shifted for a given change in the hot gas, and the furthest (both in screen heights).
const float SHIMMER = 0.012;
const float FURTHEST = 0.004;
// The glow round a flame: how far it reaches (in screen heights), how bright gas has to be to give any, the most one
// place gives, how strong it is, and how much it lights what is behind.
const float GLOW_REACH = 0.07;
const float GLOW_FROM = 1.2;
const float GLOW_MOST = 6.0;
const float GLOW = 0.16;
const float LIT_BY_GLOW = 3.0;

void main() {
    vec4 gas = texture(InSampler, texCoord);
    vec2 step = 1.0 / vec2(textureSize(HazeSampler, 0));
    vec2 slope = vec2(texture(HazeSampler, texCoord + vec2(step.x, 0.0)).r - texture(HazeSampler, texCoord - vec2(step.x, 0.0)).r,
                      texture(HazeSampler, texCoord + vec2(0.0, step.y)).r - texture(HazeSampler, texCoord - vec2(0.0, step.y)).r);
    vec2 shift = clamp(slope * SHIMMER, vec2(-FURTHEST), vec2(FURTHEST));
    vec2 size = vec2(textureSize(SceneSampler, 0));
    shift.x *= size.y / size.x;
    // the light of a flame spills over what is around it in the picture, as it does in an eye or a lens: the bright
    // parts of the gas's light, gathered from rings of places about this pixel
    vec3 spill = vec3(0.0);
    float turn = 6.2831853 * fract(52.9829189 * fract(0.06711056 * gl_FragCoord.x + 0.00583715 * gl_FragCoord.y));
    // (only where there is hot gas somewhere about: most of the screen has none, and is spared the looking)
    float about = texture(HazeSampler, texCoord).r;
    for (int k = 0; k < 8; k++) {
        float angle = float(k) * 0.7853982;
        about += texture(HazeSampler, texCoord + vec2(cos(angle) * size.y / size.x, sin(angle)) * GLOW_REACH * ((k & 1) == 0 ? 1.0 : 0.5)).r;
    }
    for (int ring = 0; ring < 3 && about > 0.0; ring++) {
        float reach = GLOW_REACH * (ring == 0 ? 0.18 : ring == 1 ? 0.5 : 1.0);
        for (int k = 0; k < 8; k++) {
            float angle = turn + float(k) * 0.7853982 + float(ring) * 0.3926991;
            vec2 off = vec2(cos(angle) * size.y / size.x, sin(angle)) * reach;
            spill += min(max(texture(InSampler, texCoord + off).rgb - GLOW_FROM, vec3(0.0)), vec3(GLOW_MOST)) * (ring == 0 ? 0.5 : ring == 1 ? 0.3 : 0.2);
        }
    }
    spill *= GLOW / 8.0;
    if (gas.a > 0.999 && gas.r + gas.g + gas.b + spill.r < 1.0e-4 && dot(shift, shift) * size.y * size.y < 0.04) discard;
    vec4 behind = texture(SceneSampler, texCoord + shift);
    // what is near a flame in the picture is lit by it, the more the brighter it already is
    vec3 through = pow(behind.rgb, vec3(2.2)) * gas.a * (1.0 + LIT_BY_GLOW * spill);
    vec3 colour = pow(1.0 - exp(-(gas.rgb + spill)) * (1.0 - min(through, vec3(1.0))), vec3(1.0 / 2.2));
    // a grain finer than the screen's steps of brightness: smooth smoke does not band
    float grain = fract(sin(dot(gl_FragCoord.xy, vec2(12.9898, 78.233))) * 43758.5453) - 0.5;
    fragColor = vec4(colour + grain / 255.0, behind.a);
}
