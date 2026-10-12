#version 330
#extension GL_ARB_separate_shader_objects : require

// Composites the half-resolution smoke (premultiplied colour + alpha) over the frame.

uniform sampler2D SmokeSampler;

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 fragColor;

void main() {
    vec4 smoke = texelFetch(SmokeSampler, ivec2(gl_FragCoord.xy) / 2, 0);
    if (smoke.a < 0.002) {
        discard;
    }
    fragColor = smoke;
}
