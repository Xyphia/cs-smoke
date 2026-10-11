#version 330
#extension GL_ARB_separate_shader_objects : require

// CS2-style volumetric smoke. The coarse shape comes from the CPU voxel simulation
// (a 2D atlas of Y slices); world-space noise erodes it into billows.

uniform sampler2D DensitySampler;
uniform sampler2D DepthSampler;

layout(std140) uniform SmokeInfo {
    mat4 InvViewProj;   // clip -> camera-relative world
    vec4 Origin;        // xyz: grid min corner relative to camera, w: voxel size
    vec4 Grid;          // xyz: voxel counts, w: time in seconds
    vec4 NoiseOrigin;   // xyz: camera position wrapped for noise, w: brightness
};

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 fragColor;

const int MAX_STEPS = 160;
const int LIGHT_STEPS = 4;
const float EXTINCTION = 7.0;
const vec3 LIGHT_DIR = vec3(0.32, 0.9, 0.28);
const vec3 LIT_COLOR = vec3(0.96, 0.91, 0.79);
const vec3 SHADOW_COLOR = vec3(0.36, 0.34, 0.31);

float hash13(vec3 p) {
    p = fract(p * 0.1031);
    p += dot(p, p.zyx + 31.32);
    return fract((p.x + p.y) * p.z);
}

float valueNoise(vec3 p) {
    vec3 i = floor(p);
    vec3 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(
        mix(mix(hash13(i), hash13(i + vec3(1, 0, 0)), f.x),
            mix(hash13(i + vec3(0, 1, 0)), hash13(i + vec3(1, 1, 0)), f.x), f.y),
        mix(mix(hash13(i + vec3(0, 0, 1)), hash13(i + vec3(1, 0, 1)), f.x),
            mix(hash13(i + vec3(0, 1, 1)), hash13(i + vec3(1, 1, 1)), f.x), f.y),
        f.z);
}

float fbm(vec3 p, int octaves) {
    float sum = 0.0;
    float amp = 0.5;
    float norm = 0.0;
    for (int i = 0; i < octaves; i++) {
        sum += amp * valueNoise(p);
        norm += amp;
        p = p * 2.03 + vec3(1.7, 9.2, 3.1);
        amp *= 0.5;
    }
    return sum / norm;
}

// Trilinear sample of the voxel grid at a camera-relative position.
float gridDensity(vec3 rel) {
    vec3 g = (rel - Origin.xyz) / Origin.w - 0.5;
    if (any(lessThan(g, vec3(-0.5))) || any(greaterThan(g, Grid.xyz - 0.5))) {
        return 0.0;
    }
    float y = clamp(g.y, 0.0, Grid.y - 1.0);
    float y0 = floor(y);
    float y1 = min(y0 + 1.0, Grid.y - 1.0);
    // Clamp within a slice so bilinear filtering never bleeds into the neighbouring slice.
    float u = clamp(g.x, 0.0, Grid.x - 1.0) + 0.5;
    float v = (clamp(g.z, 0.0, Grid.z - 1.0) + 0.5) / Grid.z;
    float width = Grid.x * Grid.y;
    float a = texture(DensitySampler, vec2((u + y0 * Grid.x) / width, v)).r;
    float b = texture(DensitySampler, vec2((u + y1 * Grid.x) / width, v)).r;
    return mix(a, b, y - y0);
}

vec3 hash33(vec3 p) {
    p = fract(p * vec3(0.1031, 0.1030, 0.0973));
    p += dot(p, p.yxz + 33.33);
    return fract((p.xxy + p.yxx) * p.zyx);
}

// Approximate Worley F1 (2x2x2 cell search); 1 - F1 gives round puffs.
float puffs(vec3 p) {
    vec3 cell = floor(p);
    vec3 f = fract(p);
    vec3 base = cell + step(0.5, f) - 1.0;
    float best = 1.0;
    for (int z = 0; z < 2; z++) {
        for (int y = 0; y < 2; y++) {
            for (int x = 0; x < 2; x++) {
                vec3 c = base + vec3(x, y, z);
                vec3 d = c + hash33(c) - p;
                best = min(best, dot(d, d));
            }
        }
    }
    return 1.0 - clamp(sqrt(best), 0.0, 1.0);
}

vec3 drift(float time) {
    return vec3(time * 0.04, -time * 0.12, time * 0.03);
}

// Low-frequency warp that makes the voxel silhouette lumpy.
vec3 warpOffset(vec3 np, float time) {
    vec3 q = np * 0.32 + drift(time) * 0.5;
    return (vec3(valueNoise(q), valueNoise(q + 17.3), valueNoise(q + 41.1)) - 0.5) * 1.7;
}

float smokeDensity(float base, vec3 rel, float time) {
    vec3 np = rel + NoiseOrigin.xyz;
    vec3 d = drift(time);
    float big = puffs(np * 0.75 + d);
    float small = puffs(np * 1.8 - d * 1.3);
    float shape = base * 1.3 - (1.0 - big) * 0.7 - (1.0 - small) * 0.22;
    return clamp(shape * 3.4, 0.0, 1.0);
}

vec2 boxHit(vec3 dir, vec3 boxMin, vec3 boxMax) {
    vec3 inv = 1.0 / dir;
    vec3 t0 = boxMin * inv;
    vec3 t1 = boxMax * inv;
    vec3 tmin = min(t0, t1);
    vec3 tmax = max(t0, t1);
    return vec2(max(max(tmin.x, tmin.y), tmin.z), min(min(tmax.x, tmax.y), tmax.z));
}

void main() {
    vec2 ndc = texCoord * 2.0 - 1.0;
    float depth = texture(DepthSampler, texCoord).r;
#ifdef RENDERPEARL_DEPTH_IS_ZERO_TO_ONE
    float sceneZ = depth;
#else
    float sceneZ = depth * 2.0 - 1.0;
#endif
    // Reversed-Z: the near plane is at NDC z = 1 on every backend.
    vec4 nearPoint = InvViewProj * vec4(ndc, 1.0, 1.0);
    vec4 scenePoint = InvViewProj * vec4(ndc, sceneZ, 1.0);
    vec3 dir = normalize(nearPoint.xyz / nearPoint.w);
    float sceneDist = length(scenePoint.xyz / scenePoint.w);

    vec3 boxMin = Origin.xyz;
    vec3 boxMax = Origin.xyz + Grid.xyz * Origin.w;
    vec2 hit = boxHit(dir, boxMin, boxMax);
    float tStart = max(hit.x, 0.0);
    float tEnd = min(hit.y, sceneDist);
    if (tEnd <= tStart) {
        discard;
    }

    float time = Grid.w;
    float brightness = NoiseOrigin.w;
    vec3 lightDir = normalize(LIGHT_DIR);
    float baseStep = max((tEnd - tStart) / float(MAX_STEPS), 0.06);
    float t = tStart + baseStep * hash13(vec3(gl_FragCoord.xy, fract(time * 7.0) * 100.0));

    float transmittance = 1.0;
    vec3 color = vec3(0.0);
    for (int i = 0; i < MAX_STEPS; i++) {
        if (t >= tEnd || transmittance < 0.01) {
            break;
        }
        vec3 p = dir * t;
        vec3 warp = warpOffset(p + NoiseOrigin.xyz, time);
        float base = gridDensity(p + warp);
        if (base < 0.01) {
            // Empty space: stride further until we reach smoke.
            t += baseStep * 3.0;
            continue;
        }
        float dens = smokeDensity(base, p, time);
        float dt = min(baseStep, tEnd - t);
        if (dens > 0.001) {
            // Coarse self-shadowing from the grid, plus one detailed sample for puff shading.
            float occlusion = 0.0;
            for (int j = 1; j <= LIGHT_STEPS; j++) {
                occlusion += gridDensity(p + warp + lightDir * (0.6 * float(j)));
            }
            vec3 lp = p + lightDir * 0.3;
            float nearOcc = smokeDensity(gridDensity(lp + warp), lp, time);
            float shadow = exp(-occlusion * 0.6 * EXTINCTION * 0.22 - nearOcc * 1.6);
            float ambient = exp(-gridDensity(p + warp + vec3(0.0, 1.2, 0.0)) * 1.2);
            float powder = 1.0 - exp(-dens * 3.0);
            float height = clamp((p.y - Origin.y) / (Grid.y * Origin.w) * 1.6, 0.0, 1.0);
            float light = clamp(shadow * mix(0.65, 1.0, powder) * 0.8 + ambient * 0.25 + height * 0.1, 0.0, 1.0);
            vec3 c = mix(SHADOW_COLOR, LIT_COLOR, light) * brightness;

            float stepT = exp(-dens * EXTINCTION * dt);
            color += transmittance * (1.0 - stepT) * c;
            transmittance *= stepT;
        }
        t += dt;
    }

    if (transmittance > 0.999) {
        discard;
    }
    fragColor = vec4(color, 1.0 - transmittance);
}
