#version 330
#extension GL_ARB_separate_shader_objects : require

// CS2-style smoke snapped to Minecraft pixels. The ray walks a grid of 1/16-block cubes
// (coarser with distance) and evaluates the smoke at each cube centre: simulation density,
// warped and eroded by pre-baked puff noise. A cube is either smoke or empty; smoke cubes
// take the cloud's precomputed lighting, so the shading reads as one cloud.
//
// Transparency is Beer-Lambert over the ray's path through each cube, so touching cubes act
// as one medium; crossing from empty space into smoke adds surface opacity, which keeps the
// outer silhouette crisp.
//
// Everything expensive is baked: per cube this is ~5 texture fetches and a little ALU.

// Simulation atlas, Y slices side by side: R = previous density, G = density,
// B = lighting, A = density dilated by half a block (for skipping empty space).
uniform sampler2D DensitySampler;
// Max dilated density per 4x4x4-voxel brick, same slice layout, for skipping 2 blocks at once.
uniform sampler2D BrickSampler;
// 128^3 tiling puff noise at one texel per pixel cube: R = large puffs, G = small puffs.
uniform sampler2D PuffSampler;
// 16^3 tiling warp offsets at one texel per half block, RGB in [0, 1].
uniform sampler2D WarpSampler;
uniform sampler2D DepthSampler;
// Block and sky light level of each voxel (level / 15), same slice layout as the density.
uniform sampler2D LightLevelSampler;
// The game's 16x16 lightmap (x: block light, y: sky light), as used for terrain.
uniform sampler2D LightmapSampler;

layout(std140) uniform SmokeInfo {
    mat4 InvViewProj;   // clip -> camera-relative world
    vec4 Origin;        // xyz: grid min corner relative to camera, w: voxel size
    vec4 Grid;          // xyz: voxel counts, w: time in seconds
    vec4 VoxelOrigin;   // xyz: world voxel index of the min corner (wrapped)
    vec4 Params;        // x: partial tick
    vec4 OccMin;        // xyz: min voxel of everything that can render
    vec4 OccMax;        // xyz: max voxel (exclusive)
};

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 fragColor;

const int PIXELS_PER_VOXEL = 8;
const int BRICK = 4;
const float PIXEL = 1.0 / 16.0;
// Fine steps per ray before handing off to the coarse march.
const int MAX_CELLS = 256;
const float THRESHOLD = 0.35;
// A cube is visible only if shape * 3.4 > THRESHOLD, and shape <= base * 1.3, so smoke
// thinner than this can never produce a visible cube: skip it (it is mostly the faint
// diffusion fringe around a cloud, which rays from outside would otherwise fine-step).
const float VISIBLE_DENSITY = 0.075;
const float EDGE_VISIBLE_DENSITY = 0.055;
const float CUBE_EXTINCTION = 2.4;
const float SURFACE_ALPHA = 0.32;
const float EDGE_SURFACE_ALPHA = 0.24;
const float EDGE_DETECTION_RANGE = 2.5;
// Once a ray is this occluded the pixel-cube detail behind is invisible, so it finishes
// with a cheap half-block march instead.
const float HANDOFF_TRANSMITTANCE = 0.45;
const float MIN_TRANSMITTANCE = 0.04;
const int MAX_COARSE_STEPS = 48;
const float WARP_BLOCKS = 0.45;
const float LIGHT_BANDS = 7.0;
const vec3 LIT_COLOR = vec3(0.96, 0.91, 0.79);
const vec3 SHADOW_COLOR = vec3(0.40, 0.37, 0.33);

// Bilinear (XZ) + linear (Y) sample of the simulation atlas, in voxel units.
vec4 coarseSample(vec3 g) {
    g -= 0.5;
    if (any(lessThan(g, vec3(-0.5))) || any(greaterThan(g, Grid.xyz - 0.5))) {
        return vec4(0.0);
    }
    float y = clamp(g.y, 0.0, Grid.y - 1.0);
    float y0 = floor(y);
    float y1 = min(y0 + 1.0, Grid.y - 1.0);
    // Clamp within a slice so bilinear filtering never bleeds into the neighbouring slice.
    float u = clamp(g.x, 0.0, Grid.x - 1.0) + 0.5;
    float v = (clamp(g.z, 0.0, Grid.z - 1.0) + 0.5) / Grid.z;
    float width = Grid.x * Grid.y;
    vec4 a = texture(DensitySampler, vec2((u + y0 * Grid.x) / width, v));
    vec4 b = texture(DensitySampler, vec2((u + y1 * Grid.x) / width, v));
    return mix(a, b, y - y0);
}

float brickSmoke(ivec3 brick) {
    int bricksX = (int(Grid.x) + BRICK - 1) >> 2;
    return texelFetch(BrickSampler, ivec2(brick.x + brick.y * bricksX, brick.z), 0).r;
}

float nearSmoke(ivec3 voxel) {
    if (any(lessThan(voxel, ivec3(0))) || any(greaterThanEqual(voxel, ivec3(Grid.xyz)))) {
        return 0.0;
    }
    return texelFetch(DensitySampler, ivec2(voxel.x + voxel.y * int(Grid.x), voxel.z), 0).a;
}

// World lighting at a voxel, from the same lightmap the surrounding blocks use, so the
// smoke darkens, tints and reacts to torches exactly like terrain does.
vec3 worldLight(ivec3 voxel) {
    voxel = clamp(voxel, ivec3(0), ivec3(Grid.xyz) - 1);
    vec2 levels = texelFetch(LightLevelSampler, ivec2(voxel.x + voxel.y * int(Grid.x), voxel.z), 0).rg * 15.0;
    return texture(LightmapSampler, (levels + 0.5) / 16.0).rgb;
}

vec2 puffsAt(ivec3 pixel) {
    ivec3 q = pixel & 127;
    return texelFetch(PuffSampler, ivec2((q.y & 15) * 128 + q.x, (q.y >> 4) * 128 + q.z), 0).rg;
}

vec3 warpAt(ivec3 halfBlock) {
    ivec3 q = halfBlock & 15;
    return texelFetch(WarpSampler, ivec2(q.y * 16 + q.x, q.z), 0).rgb * 2.0 - 1.0;
}

vec2 boxHit(vec3 dir, vec3 boxMin, vec3 boxMax) {
    vec3 inv = 1.0 / dir;
    vec3 t0 = boxMin * inv;
    vec3 t1 = boxMax * inv;
    vec3 tmin = min(t0, t1);
    vec3 tmax = max(t0, t1);
    return vec2(max(max(tmin.x, tmin.y), tmin.z), min(min(tmax.x, tmax.y), tmax.z));
}

// Detect if voxel is at a boundary (near empty space), reducing visibility threshold.
float detectEdgeFactor(vec3 g) {
    vec4 center = coarseSample(g);
    if (center.a < EDGE_VISIBLE_DENSITY) {
        return 0.0;
    }
    int edgeCount = 0;
    int sampleCount = 0;
    for (float dx = -1.0; dx <= 1.0; dx += 1.0) {
        for (float dy = -1.0; dy <= 1.0; dy += 1.0) {
            for (float dz = -1.0; dz <= 1.0; dz += 1.0) {
                if (dx == 0.0 && dy == 0.0 && dz == 0.0) continue;
                vec4 neighbor = coarseSample(g + vec3(dx, dy, dz));
                if (neighbor.a < EDGE_VISIBLE_DENSITY) {
                    edgeCount++;
                }
                sampleCount++;
            }
        }
    }
    return float(edgeCount) / float(sampleCount);
}

void main() {
    vec2 ndc = texCoord * 2.0 - 1.0;
    // Reversed-Z: the near plane is at NDC z = 1 on every backend.
    vec4 nearPoint = InvViewProj * vec4(ndc, 1.0, 1.0);
    vec3 dir = normalize(nearPoint.xyz / nearPoint.w);

    float voxel = Origin.w;
    vec3 boxMin = Origin.xyz;
    // Only march where the simulation has smoke, not the whole region.
    vec2 hit = boxHit(dir, boxMin + OccMin.xyz * voxel, boxMin + OccMax.xyz * voxel);
    if (hit.y <= max(hit.x, 0.0)) {
        discard;
    }

    float depth = texture(DepthSampler, texCoord).r;
#ifdef RENDERPEARL_DEPTH_IS_ZERO_TO_ONE
    float sceneZ = depth;
#else
    float sceneZ = depth * 2.0 - 1.0;
#endif
    vec4 scenePoint = InvViewProj * vec4(ndc, sceneZ, 1.0);
    float sceneDist = length(scenePoint.xyz / scenePoint.w);

    float t = max(hit.x, 0.0);
    float tEnd = min(hit.y, sceneDist);
    if (tEnd <= t) {
        discard;
    }

    // Level of detail: this pass runs at half resolution, where a 1/16 cube is about one
    // pixel at 12 blocks, so step coarser beyond that.
    int lod = t > 48.0 ? 3 : t > 24.0 ? 2 : t > 12.0 ? 1 : 0;
    int scale = 1 << lod;
    float cube = PIXEL * float(scale);
    // Shifts instead of integer divides, which are slow on GPUs.
    int voxelShift = 3 - lod;
    int cubesPerVoxel = 1 << voxelShift;
    ivec3 cubeCount = ivec3(Grid.xyz) * cubesPerVoxel;

    vec3 stepDir = vec3(dir.x >= 0.0 ? 1.0 : -1.0, dir.y >= 0.0 ? 1.0 : -1.0, dir.z >= 0.0 ? 1.0 : -1.0);
    ivec3 stepInt = ivec3(stepDir);
    vec3 safeDir = vec3(abs(dir.x) < 1.0e-6 ? 1.0e-6 * stepDir.x : dir.x,
                        abs(dir.y) < 1.0e-6 ? 1.0e-6 * stepDir.y : dir.y,
                        abs(dir.z) < 1.0e-6 ? 1.0e-6 * stepDir.z : dir.z);
    vec3 tDelta = abs(cube / safeDir);
    vec3 boundaryOffset = max(stepDir, 0.0);

    ivec3 cell = clamp(ivec3(floor((dir * (t + 1.0e-4) - boxMin) / cube)), ivec3(0), cubeCount - 1);
    vec3 tMax = (boxMin + (vec3(cell) + boundaryOffset) * cube) / safeDir;

    float time = Grid.w;
    float partialTick = Params.x;
    ivec3 pixelOrigin = ivec3(VoxelOrigin.xyz) * PIXELS_PER_VOXEL;
    // Noise scrolls in whole pixels, so the motion stays on the pixel grid.
    ivec3 drift = ivec3(floor(vec3(time * 0.04, -time * 0.12, time * 0.03) * 16.0));
    ivec3 warpDrift = drift / 2;

    float transmittance = 1.0;
    vec3 color = vec3(0.0);
    bool inSmoke = false;
    // The warp only changes every half block (8 pixel cubes); fetch it once per cell.
    ivec3 warpCell = ivec3(-2147483647);
    vec3 warp = vec3(0.0);
    // Empty-space checks only need redoing when the ray enters a new simulation voxel.
    ivec3 checkedVoxel = ivec3(-2147483647);
    bool handoff = false;

    for (int i = 0; i < MAX_CELLS; i++) {
        if (t >= tEnd || any(lessThan(cell, ivec3(0))) || any(greaterThanEqual(cell, cubeCount))) {
            break;
        }
        if (i == MAX_CELLS - 1) {
            handoff = true;
            break;
        }
        // Empty space: jump to where the ray leaves the empty brick or voxel. Always move
        // forward, or rays running exactly along a cell boundary would stall in place.
        ivec3 voxelCell = cell >> voxelShift;
        float skipSize = 0.0;
        vec3 skipCell = vec3(0.0);
        if (voxelCell != checkedVoxel) {
            ivec3 brick = voxelCell >> 2;
            if (brickSmoke(brick) < VISIBLE_DENSITY) {
                skipSize = voxel * float(BRICK);
                skipCell = vec3(brick);
            } else if (nearSmoke(voxelCell) < VISIBLE_DENSITY) {
                skipSize = voxel;
                skipCell = vec3(voxelCell);
            } else {
                checkedVoxel = voxelCell;
            }
        }
        if (skipSize > 0.0) {
            vec3 tExit = (boxMin + (skipCell + boundaryOffset) * skipSize) / safeDir;
            t = max(min(min(tExit.x, tExit.y), tExit.z), t) + 1.0e-3;
            cell = ivec3(floor((dir * t - boxMin) / cube));
            tMax = (boxMin + (vec3(cell) + boundaryOffset) * cube) / safeDir;
            inSmoke = false;
            continue;
        }

        ivec3 pixel = pixelOrigin + (cell << lod);
        ivec3 wc = (pixel + warpDrift) >> 3;
        if (wc != warpCell) {
            warpCell = wc;
            warp = warpAt(wc) * (WARP_BLOCKS / voxel);
        }
        vec3 g = (vec3(cell) + 0.5) / float(cubesPerVoxel) + warp;
        vec4 sim = coarseSample(g);
        float base = mix(sim.r, sim.g, partialTick);

        float dens = 0.0;
        vec2 puffs = vec2(0.0);
        float edgeFactor = detectEdgeFactor(g);
        float visibilityThreshold = mix(VISIBLE_DENSITY, EDGE_VISIBLE_DENSITY, edgeFactor);
        if (base > visibilityThreshold) {
            puffs = puffsAt(pixel + drift);
            float shape = base * 1.3 - (1.0 - puffs.x) * 0.7 - (1.0 - puffs.y) * 0.22;
            dens = clamp(shape * 3.4, 0.0, 1.0);
        }

        if (dens > THRESHOLD) {
            // Puff peaks catch more light than the creases between them.
            float light = clamp(sim.b * mix(0.75, 1.08, puffs.x), 0.0, 1.0);
            light = floor(light * LIGHT_BANDS + 0.5) / LIGHT_BANDS;
            vec3 c = mix(SHADOW_COLOR, LIT_COLOR, light) * worldLight(voxelCell);

            float exitT = min(min(min(tMax.x, tMax.y), tMax.z), tEnd);
            float through = exp(-CUBE_EXTINCTION * dens * max(exitT - t, 0.0));
            float surfaceAlpha = mix(SURFACE_ALPHA, EDGE_SURFACE_ALPHA, edgeFactor);
            float alpha = 1.0 - through * (inSmoke ? 1.0 : 1.0 - surfaceAlpha);
            color += transmittance * alpha * c;
            transmittance *= 1.0 - alpha;
            inSmoke = true;
            if (transmittance < HANDOFF_TRANSMITTANCE) {
                t = exitT;
                handoff = true;
                break;
            }
        } else {
            inSmoke = false;
        }

        // Step to the neighbouring cube across the nearest boundary.
        if (tMax.x < tMax.y && tMax.x < tMax.z) {
            t = tMax.x;
            tMax.x += tDelta.x;
            cell.x += stepInt.x;
        } else if (tMax.y < tMax.z) {
            t = tMax.y;
            tMax.y += tDelta.y;
            cell.y += stepInt.y;
        } else {
            t = tMax.z;
            tMax.z += tDelta.z;
            cell.z += stepInt.z;
        }
    }

    // Behind the visible surface: march the smooth simulation density in half-block steps.
    if (handoff) {
        for (int j = 0; j < MAX_COARSE_STEPS && t < tEnd && transmittance > MIN_TRANSMITTANCE; j++) {
            float seg = min(voxel, tEnd - t);
            vec4 sim = coarseSample((dir * (t + seg * 0.5) - boxMin) / voxel);
            float dens = clamp(mix(sim.r, sim.g, partialTick) * 1.6 - 0.3, 0.0, 1.0);
            float light = floor(clamp(sim.b, 0.0, 1.0) * LIGHT_BANDS + 0.5) / LIGHT_BANDS;
            float alpha = 1.0 - exp(-CUBE_EXTINCTION * dens * seg);
            vec3 lit = worldLight(ivec3(floor((dir * (t + seg * 0.5) - boxMin) / voxel)));
            color += transmittance * alpha * mix(SHADOW_COLOR, LIT_COLOR, light) * lit;
            transmittance *= 1.0 - alpha;
            t += seg;
        }
    }

    if (transmittance > 0.999) {
        discard;
    }
    fragColor = vec4(color, 1.0 - transmittance);
}
