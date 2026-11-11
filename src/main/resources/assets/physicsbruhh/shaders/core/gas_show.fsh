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

void main() {
    vec4 gas = texture(InSampler, texCoord);
    vec2 step = 1.0 / vec2(textureSize(HazeSampler, 0));
    vec2 slope = vec2(texture(HazeSampler, texCoord + vec2(step.x, 0.0)).r - texture(HazeSampler, texCoord - vec2(step.x, 0.0)).r,
                      texture(HazeSampler, texCoord + vec2(0.0, step.y)).r - texture(HazeSampler, texCoord - vec2(0.0, step.y)).r);
    vec2 shift = clamp(slope * SHIMMER, vec2(-FURTHEST), vec2(FURTHEST));
    vec2 size = vec2(textureSize(SceneSampler, 0));
    shift.x *= size.y / size.x;
    if (gas.a > 0.999 && gas.r + gas.g + gas.b < 1.0e-4 && dot(shift, shift) * size.y * size.y < 0.04) discard;
    vec4 behind = texture(SceneSampler, texCoord + shift);
    vec3 through = pow(behind.rgb, vec3(2.2)) * gas.a;
    vec3 colour = pow(1.0 - exp(-gas.rgb) * (1.0 - through), vec3(1.0 / 2.2));
    // a grain finer than the screen's steps of brightness: smooth smoke does not band
    float grain = fract(sin(dot(gl_FragCoord.xy, vec2(12.9898, 78.233))) * 43758.5453) - 0.5;
    fragColor = vec4(colour + grain / 255.0, behind.a);
}
