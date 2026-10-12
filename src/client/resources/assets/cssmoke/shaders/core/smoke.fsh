#version 330
#extension GL_ARB_separate_shader_objects : require

// CS2-style smoke snapped to Minecraft pixels. The billowy volumetric field (simulation
// density, warped and eroded by puff noise) is evaluated at the centre of every 1/16-block
// cube along the ray; a cube is either smoke or empty, and smoke cubes take the cloud's
// volumetric lighting at their centre, so the shading reads as one cloud rather than as
// lit blocks. Transparency is Beer-Lambert over the ray's path through each cube, so only
// the silhouette is pixel-stepped and thin parts of the cloud are see-through.
//
// DensitySampler is a 2D atlas of Y slices: R = density, G = density dilated by one block,
// used to skip empty simulation cells in one jump.

uniform sampler2D DensitySampler;
uniform sampler2D DepthSampler;

layout(std140) uniform SmokeInfo {
    mat4 InvViewProj;   // clip -> camera-relative world
    vec4 Origin;        // xyz: grid min corner relative to camera, w: voxel size
    vec4 Grid;          // xyz: voxel counts, w: time in seconds
    vec4 VoxelOrigin;   // xyz: world voxel index of the min corner (wrapped), w: brightness
};

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 fragColor;

const float PIXEL = 1.0 / 16.0;
const int MAX_CELLS = 900;
const float THRESHOLD = 0.35;
// Absorption per block of smoke at full density. Each pixel cube absorbs by the length the
// ray travels inside it, so touching cubes act as one medium with no internal faces.
const float CUBE_EXTINCTION = 2.4;
// Extra opacity where the ray crosses from empty space into smoke. Only the outer skin of the
// cloud has such a boundary, so the silhouette stays crisp while joins between neighbouring
// smoke cubes add nothing.
const float SURFACE_ALPHA = 0.32;
const int LIGHT_STEPS = 4;
const float EXTINCTION = 7.0;
const float LIGHT_BANDS = 7.0;
const vec3 LIGHT_DIR = vec3(0.32, 0.9, 0.28);
const vec3 LIT_COLOR = vec3(0.96, 0.91, 0.79);
const vec3 SHADOW_COLOR = vec3(0.40, 0.37, 0.33);

float hash13(vec3 p) {
    p = fract(p * 0.1031);
    p += dot(p, p.zyx + 31.32);
    return fract((p.x + p.y) * p.z);
}

vec3 hash33(vec3 p) {
    p = fract(p * vec3(0.1031, 0.1030, 0.0973));
    p += dot(p, p.yxz + 33.33);
    return fract((p.xxy + p.yxx) * p.zyx);
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

// Wrapped world position of a camera-relative point, for noise.
vec3 noisePos(vec3 rel) {
    return rel - Origin.xyz + VoxelOrigin.xyz * Origin.w;
}

// Low-frequency warp that makes the silhouette lumpy (at most ~0.85 blocks, which the
// one-block dilation in the G channel covers).
vec3 warpOffset(vec3 np, float time) {
    vec3 q = np * 0.32 + drift(time) * 0.5;
    return (vec3(valueNoise(q), valueNoise(q + 17.3), valueNoise(q + 41.1)) - 0.5) * 1.7;
}

// Trilinear sample of the simulation density at a camera-relative position.
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

// Dilated density of the simulation cell containing a camera-relative position.
float nearSmoke(vec3 rel) {
    ivec3 c = ivec3(floor((rel - Origin.xyz) / Origin.w));
    if (any(lessThan(c, ivec3(0))) || any(greaterThanEqual(c, ivec3(Grid.xyz)))) {
        return 0.0;
    }
    return texelFetch(DensitySampler, ivec2(c.x + c.y * int(Grid.x), c.z), 0).g;
}

float smokeDensity(vec3 rel, vec3 warp, float time) {
    float base = gridDensity(rel + warp);
    if (base < 0.01) {
        return 0.0;
    }
    vec3 np = noisePos(rel);
    vec3 d = drift(time);
    float big = puffs(np * 0.75 + d);
    float small = puffs(np * 1.8 - d * 1.3);
    float shape = base * 1.3 - (1.0 - big) * 0.7 - (1.0 - small) * 0.22;
    return clamp(shape * 3.4, 0.0, 1.0);
}

// Volumetric lighting of the cloud at a point: self-shadowing toward the light, ambient
// occlusion from smoke above, and a lighter top. Banded for a pixel-art look.
vec3 smokeLight(vec3 p, vec3 warp, float dens, float time) {
    vec3 lightDir = normalize(LIGHT_DIR);
    float occlusion = 0.0;
    for (int j = 1; j <= LIGHT_STEPS; j++) {
        occlusion += gridDensity(p + warp + lightDir * (0.6 * float(j)));
    }
    vec3 lp = p + lightDir * 0.3;
    float nearOcc = smokeDensity(lp, warp, time);
    float shadow = exp(-occlusion * 0.6 * EXTINCTION * 0.22 - nearOcc * 1.6);
    float ambient = exp(-gridDensity(p + warp + vec3(0.0, 1.2, 0.0)) * 1.2);
    float powder = 1.0 - exp(-dens * 3.0);
    float height = clamp((p.y - Origin.y) / (Grid.y * Origin.w) * 1.6, 0.0, 1.0);
    float light = clamp(shadow * mix(0.65, 1.0, powder) * 0.8 + ambient * 0.25 + height * 0.1, 0.0, 1.0);
    light = floor(light * LIGHT_BANDS + 0.5) / LIGHT_BANDS;
    return mix(SHADOW_COLOR, LIT_COLOR, light);
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

    float voxel = Origin.w;
    vec3 boxMin = Origin.xyz;
    vec3 boxMax = Origin.xyz + Grid.xyz * voxel;
    vec2 hit = boxHit(dir, boxMin, boxMax);
    float t = max(hit.x, 0.0);
    float tEnd = min(hit.y, sceneDist);
    if (tEnd <= t) {
        discard;
    }

    vec3 stepDir = sign(dir);
    vec3 safeDir = vec3(abs(dir.x) < 1.0e-6 ? 1.0e-6 : dir.x, abs(dir.y) < 1.0e-6 ? 1.0e-6 : dir.y, abs(dir.z) < 1.0e-6 ? 1.0e-6 : dir.z);
    vec3 tDelta = abs(PIXEL / safeDir);
    ivec3 pixelCount = ivec3(Grid.xyz * (voxel / PIXEL) + 0.5);

    // Pixel-grid DDA; re-initialised after every empty-space jump.
    ivec3 cell = clamp(ivec3(floor((dir * (t + 1.0e-4) - boxMin) / PIXEL)), ivec3(0), pixelCount - 1);
    vec3 tMax = (boxMin + (vec3(cell) + max(stepDir, 0.0)) * PIXEL) / safeDir;

    float time = Grid.w;
    float brightness = VoxelOrigin.w;
    float transmittance = 1.0;
    vec3 color = vec3(0.0);
    bool inSmoke = false;

    for (int i = 0; i < MAX_CELLS; i++) {
        if (t >= tEnd || any(lessThan(cell, ivec3(0))) || any(greaterThanEqual(cell, pixelCount))) {
            break;
        }
        vec3 p = boxMin + (vec3(cell) + 0.5) * PIXEL;

        if (nearSmoke(p) < 0.01) {
            // Nothing within a block: jump to where the ray leaves this simulation cell.
            vec3 coarse = floor((p - boxMin) / voxel);
            vec3 tExit = (boxMin + (coarse + max(stepDir, 0.0)) * voxel) / safeDir;
            t = min(min(tExit.x, tExit.y), tExit.z);
            cell = ivec3(floor((dir * (t + 1.0e-4) - boxMin) / PIXEL));
            tMax = (boxMin + (vec3(cell) + max(stepDir, 0.0)) * PIXEL) / safeDir;
            inSmoke = false;
            continue;
        }

        vec3 warp = warpOffset(noisePos(p), time);
        float dens = smokeDensity(p, warp, time);
        if (dens > THRESHOLD) {
            vec3 c = smokeLight(p, warp, dens, time) * brightness;
            float exitT = min(min(min(tMax.x, tMax.y), tMax.z), tEnd);
            float through = exp(-CUBE_EXTINCTION * dens * max(exitT - t, 0.0));
            float alpha = 1.0 - through * (inSmoke ? 1.0 : 1.0 - SURFACE_ALPHA);
            color += transmittance * alpha * c;
            transmittance *= 1.0 - alpha;
            inSmoke = true;
            if (transmittance < 0.02) {
                break;
            }
        } else {
            inSmoke = false;
        }

        // Step to the neighbouring pixel cube across the nearest boundary.
        if (tMax.x < tMax.y && tMax.x < tMax.z) {
            t = tMax.x;
            tMax.x += tDelta.x;
            cell.x += int(stepDir.x);
        } else if (tMax.y < tMax.z) {
            t = tMax.y;
            tMax.y += tDelta.y;
            cell.y += int(stepDir.y);
        } else {
            t = tMax.z;
            tMax.z += tDelta.z;
            cell.z += int(stepDir.z);
        }
    }

    if (transmittance > 0.999) {
        discard;
    }
    fragColor = vec4(color, 1.0 - transmittance);
}
