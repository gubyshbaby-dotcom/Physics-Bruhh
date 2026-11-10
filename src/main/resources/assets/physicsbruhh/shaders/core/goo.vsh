#version 330
#extension GL_ARB_separate_shader_objects : require

// Goo surface, vertex stage. Same vertex layout and uniform blocks as the game's entity shader,
// so it runs in the entity pipelines (plain and order-independent transparency alike).

#include <minecraft:fog.glsl>
#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>
#include <minecraft:sample_lightmap.glsl>

layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;   // r: material (0 honey, 1 slime), g: look (0 block texture, 1 clear), a: depth of goo behind the surface / 2.6
layout(location = 2) in vec2 UV0;
layout(location = 3) in ivec2 UV1;
layout(location = 4) in ivec2 UV2;
layout(location = 5) in vec3 Normal;

#ifndef OIT_ALPHA_ONLY
uniform sampler2D Sampler2;
#endif

layout(location = 0) out float sphericalVertexDistance;
layout(location = 1) out float cylindricalVertexDistance;
layout(location = 2) out vec4 gooParams;
layout(location = 3) out vec4 lightMapColor;
layout(location = 4) out vec2 texCoord0;
layout(location = 5) out vec3 gooPosition;   // relative to the camera, along the world axes
layout(location = 6) out vec3 gooNormal;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);

    sphericalVertexDistance = fog_spherical_distance(Position);
    cylindricalVertexDistance = fog_cylindrical_distance(Position);
    gooParams = Color;
#ifndef OIT_ALPHA_ONLY
    lightMapColor = sample_lightmap(Sampler2, UV2);
#else
    lightMapColor = vec4(1.0);
#endif
    texCoord0 = UV0;
    gooPosition = Position;
    gooNormal = Normal;
}
