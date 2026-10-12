package dev.xyphia.cssmoke;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A world-aligned block of smoke voxels shared by every emitter inside it. Overlapping
 * smokes live in one region, so they merge into one cloud instead of two stacked ones.
 * Each tick the density relaxes toward what the emitters want, diffuses, gets shoved
 * aside by moving entities and is blown out by explosions.
 */
public final class SmokeRegion implements AutoCloseable {
    public static final float VOXEL = 0.5F;
    public static final int MAX_X = 128;
    public static final int MAX_Y = 32;
    public static final int MAX_Z = 128;

    private static final float FILL_RATE = 0.22F;
    private static final float REFILL_RATE = 0.018F;
    private static final float DECAY_RATE = 0.08F;
    private static final float DIFFUSION = 0.035F;
    private static final float MAX_DENSITY = 1.6F;
    private static final int RECLASSIFY_DELAY = 3;
    private static final int LIGHT_REFRESH_TICKS = 20;
    /** Faster than this (blocks per tick) and small, an entity cuts a tunnel like a bullet. */
    private static final double PROJECTILE_SPEED = 0.6;
    /** One voxel = half a block, more than the shader's noise warp. */
    private static final int DILATE = 1;
    private static final float LIGHT_ABSORB = 0.9F;
    private static final float LIGHT_FALLOFF = 0.85F;

    /** Minimum corner and size, in world voxel coordinates. */
    public final int minX;
    public final int minY;
    public final int minZ;
    public final int nx;
    public final int ny;
    public final int nz;

    private final boolean[] open;
    private final float[] goal;
    private final boolean[] fastFill;
    private final int[] suppress;
    private float[] density;
    private float[] previous;
    private float[] scratch;
    private float[] upload;
    private float[] dilateA;
    private float[] dilateB;
    private float[] light;
    private byte[] bricks;
    private int[] occupied = {0, 0, 0, 0, 0, 0};
    private int version;
    private final List<SmokeEmitter> emitters = new ArrayList<>();
    private final List<int[]> pendingReclassify = new ArrayList<>();
    private final byte[] lightLevels;
    private int lightVersion;
    private int age;

    private final SmokeRegionGpu gpu = new SmokeRegionGpu();

    private SmokeRegion(Level level, int minX, int minY, int minZ, int nx, int ny, int nz) {
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.nx = nx;
        this.ny = ny;
        this.nz = nz;
        int n = nx * ny * nz;
        open = new boolean[n];
        goal = new float[n];
        fastFill = new boolean[n];
        suppress = new int[n];
        density = new float[n];
        previous = new float[n];
        scratch = new float[n];
        lightLevels = new byte[n * 2];
        classify(level, 0, 0, 0, nx - 1, ny - 1, nz - 1);
        updateLightLevels(level);
    }

    public static SmokeRegion of(Level level, SmokeEmitter emitter) {
        SmokeRegion region = new SmokeRegion(level, emitter.minX, emitter.minY, emitter.minZ, SmokeEmitter.SX, SmokeEmitter.SY, SmokeEmitter.SZ);
        region.emitters.add(emitter);
        return region;
    }

    /** Union of the given regions plus a new emitter, or null if it would be too big. */
    public static SmokeRegion merge(Level level, List<SmokeRegion> regions, SmokeEmitter emitter) {
        int x0 = emitter.minX;
        int y0 = emitter.minY;
        int z0 = emitter.minZ;
        int x1 = emitter.minX + SmokeEmitter.SX;
        int y1 = emitter.minY + SmokeEmitter.SY;
        int z1 = emitter.minZ + SmokeEmitter.SZ;
        for (SmokeRegion r : regions) {
            x0 = Math.min(x0, r.minX);
            y0 = Math.min(y0, r.minY);
            z0 = Math.min(z0, r.minZ);
            x1 = Math.max(x1, r.minX + r.nx);
            y1 = Math.max(y1, r.minY + r.ny);
            z1 = Math.max(z1, r.minZ + r.nz);
        }
        if (x1 - x0 > MAX_X || y1 - y0 > MAX_Y || z1 - z0 > MAX_Z) {
            return null;
        }

        SmokeRegion merged = new SmokeRegion(level, x0, y0, z0, x1 - x0, y1 - y0, z1 - z0);
        for (SmokeRegion r : regions) {
            for (int y = 0; y < r.ny; y++) {
                for (int z = 0; z < r.nz; z++) {
                    for (int x = 0; x < r.nx; x++) {
                        int from = r.index(x, y, z);
                        int to = merged.index(r.minX - x0 + x, r.minY - y0 + y, r.minZ - z0 + z);
                        merged.density[to] = Math.max(merged.density[to], r.density[from]);
                        merged.previous[to] = Math.max(merged.previous[to], r.previous[from]);
                        merged.suppress[to] = Math.max(merged.suppress[to], r.suppress[from]);
                    }
                }
            }
            merged.emitters.addAll(r.emitters);
        }
        merged.emitters.add(emitter);
        return merged;
    }

    public static int toVoxel(double world) {
        return (int) Math.floor(world / VOXEL);
    }

    public int index(int x, int y, int z) {
        return (y * nz + z) * nx + x;
    }

    public boolean overlaps(SmokeEmitter e) {
        return e.minX < minX + nx && e.minX + SmokeEmitter.SX > minX
            && e.minY < minY + ny && e.minY + SmokeEmitter.SY > minY
            && e.minZ < minZ + nz && e.minZ + SmokeEmitter.SZ > minZ;
    }

    /** Bumped whenever the per-voxel light levels change. */
    public int lightVersion() {
        return lightVersion;
    }

    /**
     * Writes the sky and block light level of every voxel as RG8 texels (level / 15) in the
     * atlas layout, so the shader can light the smoke from the game's own lightmap.
     */
    public void writeLight(ByteBuffer out) {
        int width = nx * ny;
        for (int y = 0; y < ny; y++) {
            for (int z = 0; z < nz; z++) {
                for (int x = 0; x < nx; x++) {
                    int i = index(x, y, z);
                    int texel = (z * width + y * nx + x) * 2;
                    out.put(texel, lightLevels[i * 2]);
                    out.put(texel + 1, lightLevels[i * 2 + 1]);
                }
            }
        }
    }

    public SmokeRegionGpu gpu() {
        return gpu;
    }

    public int emitterCount() {
        return emitters.size();
    }

    public boolean isDone() {
        if (!emitters.isEmpty()) {
            return false;
        }
        for (float d : density) {
            if (d > 0.01F) {
                return false;
            }
        }
        return true;
    }

    /** Bumped whenever the density changes, so the renderer uploads at most once per tick. */
    public int version() {
        return version;
    }

    /**
     * Writes the atlas as RGBA8 texels: R = previous tick's density, G = this tick's (the
     * shader interpolates between them), B = precomputed lighting, A = density dilated by
     * {@link #DILATE} voxels so the shader can skip empty space without missing smoke its
     * noise warp pushes outward.
     */
    public void writeUpload(ByteBuffer out) {
        int n = density.length;
        if (upload == null) {
            upload = new float[n];
            dilateA = new float[n];
            dilateB = new float[n];
            light = new float[n];
        }
        for (int i = 0; i < n; i++) {
            upload[i] = Math.max(previous[i], density[i]);
        }
        dilate(upload, dilateA, 1, nx);
        dilate(dilateA, dilateB, nx, nz);
        dilate(dilateB, dilateA, nx * nz, ny);
        computeLight();
        computeBricksAndBounds();

        int width = nx * ny;
        for (int y = 0; y < ny; y++) {
            for (int z = 0; z < nz; z++) {
                for (int x = 0; x < nx; x++) {
                    int i = index(x, y, z);
                    // Atlas layout: Y slices side by side along U, Z along V.
                    int texel = (z * width + y * nx + x) * 4;
                    out.put(texel, toByte(previous[i]));
                    out.put(texel + 1, toByte(density[i]));
                    out.put(texel + 2, toByte(light[i]));
                    out.put(texel + 3, toByte(dilateA[i]));
                }
            }
        }
    }

    public static final int BRICK = 4;

    public int brickX() {
        return (nx + BRICK - 1) / BRICK;
    }

    public int brickY() {
        return (ny + BRICK - 1) / BRICK;
    }

    public int brickZ() {
        return (nz + BRICK - 1) / BRICK;
    }

    /** Max dilated density per 4x4x4 brick, as R8 in the same slice layout as the atlas. */
    public void writeBricks(ByteBuffer out) {
        out.put(0, bricks, 0, bricks.length);
    }

    /** Voxel-space bounds of everything that could render, inclusive min / exclusive max. */
    public int[] occupiedBounds() {
        return occupied;
    }

    private void computeBricksAndBounds() {
        int bx = brickX();
        int by = brickY();
        int bz = brickZ();
        if (bricks == null) {
            bricks = new byte[bx * by * bz];
        }
        java.util.Arrays.fill(bricks, (byte) 0);
        int x0 = nx, y0 = ny, z0 = nz, x1 = 0, y1 = 0, z1 = 0;
        int width = bx * by;
        for (int y = 0; y < ny; y++) {
            for (int z = 0; z < nz; z++) {
                for (int x = 0; x < nx; x++) {
                    float d = dilateA[index(x, y, z)];
                    // Below this the shader can't produce a visible cube (see VISIBLE_DENSITY).
                    if (d < 0.075F) {
                        continue;
                    }
                    x0 = Math.min(x0, x);
                    y0 = Math.min(y0, y);
                    z0 = Math.min(z0, z);
                    x1 = Math.max(x1, x + 1);
                    y1 = Math.max(y1, y + 1);
                    z1 = Math.max(z1, z + 1);
                    int b = (z / BRICK) * width + (y / BRICK) * bx + x / BRICK;
                    int v = toByte(d) & 0xFF;
                    if (v > (bricks[b] & 0xFF)) {
                        bricks[b] = (byte) v;
                    }
                }
            }
        }
        occupied = x1 > x0 ? new int[] {x0, y0, z0, x1, y1, z1} : new int[] {0, 0, 0, 0, 0, 0};
    }

    /**
     * Cheap volumetric lighting from above: smoke stacked over a voxel shades it, nearer
     * smoke counting more, plus a little ambient and a lighter top.
     */
    private void computeLight() {
        for (int z = 0; z < nz; z++) {
            for (int x = 0; x < nx; x++) {
                float occlusion = 0.0F;
                for (int y = ny - 1; y >= 0; y--) {
                    int i = index(x, y, z);
                    float height = y / (float) ny;
                    float shadow = (float) Math.exp(-occlusion * LIGHT_ABSORB);
                    light[i] = Math.min(1.0F, shadow * 0.8F + 0.12F + height * 0.15F);
                    occlusion = occlusion * LIGHT_FALLOFF + density[i];
                }
            }
        }
    }

    /** One-axis running max with radius {@link #DILATE}; stride/length pick the axis. */
    private void dilate(float[] src, float[] dst, int stride, int length) {
        for (int i = 0; i < src.length; i++) {
            int coord = (i / stride) % length;
            float m = src[i];
            for (int k = 1; k <= DILATE; k++) {
                if (coord - k >= 0) {
                    m = Math.max(m, src[i - k * stride]);
                }
                if (coord + k < length) {
                    m = Math.max(m, src[i + k * stride]);
                }
            }
            dst[i] = m;
        }
    }

    private static byte toByte(float d) {
        return (byte) (int) (Math.min(1.0F, Math.max(0.0F, d)) * 255.0F + 0.5F);
    }

    public static boolean isOpen(Level level, int vx, int vy, int vz) {
        double wx = (vx + 0.5) * VOXEL;
        double wy = (vy + 0.5) * VOXEL;
        double wz = (vz + 0.5) * VOXEL;
        BlockPos pos = BlockPos.containing(wx, wy, wz);
        BlockState state = level.getBlockState(pos);
        VoxelShape shape = state.getCollisionShape(level, pos);
        if (shape.isEmpty()) {
            return true;
        }
        double lx = wx - pos.getX();
        double ly = wy - pos.getY();
        double lz = wz - pos.getZ();
        for (AABB box : shape.toAabbs()) {
            if (lx >= box.minX && lx <= box.maxX && ly >= box.minY && ly <= box.maxY && lz >= box.minZ && lz <= box.maxZ) {
                return false;
            }
        }
        return true;
    }

    private void classify(Level level, int x0, int y0, int z0, int x1, int y1, int z1) {
        for (int y = Math.max(0, y0); y <= Math.min(ny - 1, y1); y++) {
            for (int z = Math.max(0, z0); z <= Math.min(nz - 1, z1); z++) {
                for (int x = Math.max(0, x0); x <= Math.min(nx - 1, x1); x++) {
                    open[index(x, y, z)] = isOpen(level, minX + x, minY + y, minZ + z);
                }
            }
        }
    }

    public void tick(Level level) {
        age++;
        version++;
        for (Iterator<SmokeEmitter> it = emitters.iterator(); it.hasNext(); ) {
            SmokeEmitter e = it.next();
            e.tick();
            if (e.isExpired()) {
                it.remove();
            }
        }

        float[] swap = previous;
        previous = density;
        density = swap;
        System.arraycopy(previous, 0, density, 0, density.length);

        java.util.Arrays.fill(goal, 0.0F);
        java.util.Arrays.fill(fastFill, false);
        for (SmokeEmitter e : emitters) {
            e.writeGoal(this, goal, fastFill);
        }

        for (int i = 0; i < density.length; i++) {
            if (!open[i]) {
                density[i] = 0.0F;
                continue;
            }
            float g = goal[i];
            if (suppress[i] > 0) {
                suppress[i]--;
                g = 0.0F;
            }
            float d = density[i];
            float rate = d < g ? (fastFill[i] ? FILL_RATE : REFILL_RATE) : DECAY_RATE;
            density[i] = d + (g - d) * rate;
        }

        pushByEntities((ClientLevel) level);
        diffuse();
        runPendingReclassify(level);

        if (age % LIGHT_REFRESH_TICKS == 0) {
            updateLightLevels(level);
        }
    }

    /**
     * Blows the smoke out of a sphere around an explosion, like an HE grenade in CS2. The
     * hole stays clear briefly, then refills from the emitters and the surrounding smoke.
     */
    public void blast(Vec3 center, float radius) {
        version++;
        double clear = radius * 1.75 + 1.0;
        int cx = toVoxel(center.x) - minX;
        int cy = toVoxel(center.y) - minY;
        int cz = toVoxel(center.z) - minZ;
        int r = (int) Math.ceil((clear + 1.5) / VOXEL);

        for (int y = cy - r; y <= cy + r; y++) {
            for (int z = cz - r; z <= cz + r; z++) {
                for (int x = cx - r; x <= cx + r; x++) {
                    if (x < 0 || y < 0 || z < 0 || x >= nx || y >= ny || z >= nz) {
                        continue;
                    }
                    int i = index(x, y, z);
                    double px = (minX + x + 0.5) * VOXEL - center.x;
                    double py = (minY + y + 0.5) * VOXEL - center.y;
                    double pz = (minZ + z + 0.5) * VOXEL - center.z;
                    double dist = Math.sqrt(px * px + py * py + pz * pz);
                    if (dist >= clear) {
                        continue;
                    }
                    float f = (float) (1.0 - dist / clear);
                    float removed = density[i] * Math.min(1.0F, 0.35F + f * 2.0F);
                    density[i] -= removed;
                    previous[i] = density[i];
                    suppress[i] = Math.max(suppress[i], (int) (20 + 30 * f));

                    // Throw some of it outward into a ring just past the blast.
                    double inv = 1.0 / Math.max(dist, 1.0E-3);
                    double push = clear - dist + 1.0;
                    int tx = toVoxel(center.x + px + px * inv * push) - minX;
                    int ty = toVoxel(center.y + py + py * inv * push * 0.5) - minY;
                    int tz = toVoxel(center.z + pz + pz * inv * push) - minZ;
                    if (tx >= 0 && ty >= 0 && tz >= 0 && tx < nx && ty < ny && tz < nz) {
                        int t = index(tx, ty, tz);
                        if (open[t] && suppress[t] == 0) {
                            density[t] = Math.min(MAX_DENSITY, density[t] + removed * 0.5F);
                        }
                    }
                }
            }
        }

        // Blocks the explosion destroys arrive as separate updates; re-check them shortly.
        int br = (int) Math.ceil((radius + 1.0) / VOXEL);
        pendingReclassify.add(new int[] {RECLASSIFY_DELAY, cx - br, cy - br, cz - br, cx + br, cy + br, cz + br});
    }

    private void runPendingReclassify(Level level) {
        for (Iterator<int[]> it = pendingReclassify.iterator(); it.hasNext(); ) {
            int[] job = it.next();
            if (--job[0] <= 0) {
                classify(level, job[1], job[2], job[3], job[4], job[5], job[6]);
                updateLightLevels(level);
                it.remove();
            }
        }
    }

    /** Entities carve a hole and shove the smoke ahead of and around them. */
    private void pushByEntities(ClientLevel level) {
        AABB bounds = new AABB(minX * VOXEL, minY * VOXEL, minZ * VOXEL,
            (minX + nx) * VOXEL, (minY + ny) * VOXEL, (minZ + nz) * VOXEL).inflate(1.0);

        for (Entity entity : level.entitiesForRendering()) {
            if (entity.isSpectator()) {
                continue;
            }
            AABB box = entity.getBoundingBox();
            if (!box.intersects(bounds)) {
                continue;
            }
            Vec3 c = box.getCenter();
            double vx = entity.getX() - entity.xo;
            double vy = entity.getY() - entity.yo;
            double vz = entity.getZ() - entity.zo;
            double speed = Math.sqrt(vx * vx + vy * vy + vz * vz);
            double size = Math.max(box.getXsize(), Math.max(box.getYsize(), box.getZsize()));
            if (speed > PROJECTILE_SPEED && size < 1.0) {
                // Fast and small (arrows, snowballs, tridents...): it moves several blocks per
                // tick, so carve along the whole path it covered, not just where it ended up.
                tunnel(c.subtract(vx, vy, vz), c, Math.max(0.55, size * 0.5 + 0.35));
                continue;
            }
            double rx = box.getXsize() * 0.5 + 0.65;
            double ry = box.getYsize() * 0.5 + 0.4;
            double rz = box.getZsize() * 0.5 + 0.65;
            float strength = (float) (0.06 + Math.min(1.0, speed * 4.0) * 0.8);
            pushEllipsoid(c, rx, ry, rz, vx, vy, vz, speed, strength);
        }
    }

    /**
     * Clears a tube along a segment, pushing the smoke sideways out of it, and holds it
     * open briefly so the trail stays visible before it refills.
     */
    private void tunnel(Vec3 from, Vec3 to, double radius) {
        double dx = to.x - from.x;
        double dy = to.y - from.y;
        double dz = to.z - from.z;
        double lenSq = dx * dx + dy * dy + dz * dz;
        int x0 = Math.max(0, toVoxel(Math.min(from.x, to.x) - radius) - minX);
        int x1 = Math.min(nx - 1, toVoxel(Math.max(from.x, to.x) + radius) - minX);
        int y0 = Math.max(0, toVoxel(Math.min(from.y, to.y) - radius) - minY);
        int y1 = Math.min(ny - 1, toVoxel(Math.max(from.y, to.y) + radius) - minY);
        int z0 = Math.max(0, toVoxel(Math.min(from.z, to.z) - radius) - minZ);
        int z1 = Math.min(nz - 1, toVoxel(Math.max(from.z, to.z) + radius) - minZ);

        for (int y = y0; y <= y1; y++) {
            for (int z = z0; z <= z1; z++) {
                for (int x = x0; x <= x1; x++) {
                    int i = index(x, y, z);
                    if (!open[i]) {
                        continue;
                    }
                    double px = (minX + x + 0.5) * VOXEL;
                    double py = (minY + y + 0.5) * VOXEL;
                    double pz = (minZ + z + 0.5) * VOXEL;
                    double t = lenSq > 1.0E-8 ? ((px - from.x) * dx + (py - from.y) * dy + (pz - from.z) * dz) / lenSq : 0.0;
                    t = Math.max(0.0, Math.min(1.0, t));
                    double cx = from.x + dx * t;
                    double cy = from.y + dy * t;
                    double cz = from.z + dz * t;
                    double ox = px - cx;
                    double oy = py - cy;
                    double oz = pz - cz;
                    double dist = Math.sqrt(ox * ox + oy * oy + oz * oz);
                    if (dist >= radius) {
                        continue;
                    }
                    float w = (float) (1.0 - dist / radius);
                    suppress[i] = Math.max(suppress[i], (int) (25 + 25 * w));
                    float removed = density[i] * Math.min(1.0F, 0.6F + w);
                    if (removed <= 0.001F) {
                        continue;
                    }
                    density[i] -= removed;

                    // Out to just past the tube wall, away from the path.
                    double inv = 1.0 / Math.max(dist, 1.0E-3);
                    double push = radius - dist + VOXEL;
                    int tx = toVoxel(px + ox * inv * push) - minX;
                    int ty = toVoxel(py + oy * inv * push) - minY;
                    int tz = toVoxel(pz + oz * inv * push) - minZ;
                    if (tx >= 0 && ty >= 0 && tz >= 0 && tx < nx && ty < ny && tz < nz) {
                        int target = index(tx, ty, tz);
                        if (open[target] && suppress[target] == 0) {
                            density[target] = Math.min(MAX_DENSITY, density[target] + removed * 0.8F);
                        }
                    }
                }
            }
        }
    }

    private void pushEllipsoid(Vec3 c, double rx, double ry, double rz, double vx, double vy, double vz, double speed, float strength) {
        int x0 = Math.max(0, toVoxel(c.x - rx) - minX);
        int x1 = Math.min(nx - 1, toVoxel(c.x + rx) - minX);
        int y0 = Math.max(0, toVoxel(c.y - ry) - minY);
        int y1 = Math.min(ny - 1, toVoxel(c.y + ry) - minY);
        int z0 = Math.max(0, toVoxel(c.z - rz) - minZ);
        int z1 = Math.min(nz - 1, toVoxel(c.z + rz) - minZ);
        double inv = speed > 1.0E-4 ? 1.0 / speed : 0.0;

        for (int y = y0; y <= y1; y++) {
            for (int z = z0; z <= z1; z++) {
                for (int x = x0; x <= x1; x++) {
                    int i = index(x, y, z);
                    if (!open[i] || density[i] <= 0.001F) {
                        continue;
                    }
                    double px = (minX + x + 0.5) * VOXEL - c.x;
                    double py = (minY + y + 0.5) * VOXEL - c.y;
                    double pz = (minZ + z + 0.5) * VOXEL - c.z;
                    double q = Math.sqrt(px * px / (rx * rx) + py * py / (ry * ry) + pz * pz / (rz * rz));
                    if (q >= 1.0) {
                        continue;
                    }
                    float w = (float) ((1.0 - q) * (1.0 - q)) * strength;
                    float moved = density[i] * Math.min(0.9F, w * 1.6F);

                    // Bow wave: push outward from the body, biased along the motion.
                    double len = Math.sqrt(px * px + py * py + pz * pz) + 1.0E-4;
                    double dx = px / len * 0.6 + vx * inv * 0.9;
                    double dy = py / len * 0.3 + vy * inv * 0.5 + 0.05;
                    double dz = pz / len * 0.6 + vz * inv * 0.9;
                    double dl = Math.sqrt(dx * dx + dy * dy + dz * dz) + 1.0E-4;
                    double reach = (1.0 - q) * Math.max(rx, rz) + VOXEL;
                    int tx = toVoxel(c.x + px + dx / dl * reach) - minX;
                    int ty = toVoxel(c.y + py + dy / dl * reach) - minY;
                    int tz = toVoxel(c.z + pz + dz / dl * reach) - minZ;

                    density[i] -= moved;
                    if (tx >= 0 && ty >= 0 && tz >= 0 && tx < nx && ty < ny && tz < nz) {
                        int t = index(tx, ty, tz);
                        if (open[t]) {
                            density[t] = Math.min(MAX_DENSITY, density[t] + moved * 0.95F);
                        }
                    }
                }
            }
        }
    }

    /** Six-neighbour blur that only exchanges between open voxels, so walls stay sharp. */
    private void diffuse() {
        for (int y = 0; y < ny; y++) {
            for (int z = 0; z < nz; z++) {
                for (int x = 0; x < nx; x++) {
                    int i = index(x, y, z);
                    float d = density[i];
                    if (!open[i]) {
                        scratch[i] = 0.0F;
                        continue;
                    }
                    float sum = neighbour(x - 1, y, z, d) + neighbour(x + 1, y, z, d)
                        + neighbour(x, y - 1, z, d) + neighbour(x, y + 1, z, d)
                        + neighbour(x, y, z - 1, d) + neighbour(x, y, z + 1, d);
                    scratch[i] = d + (sum / 6.0F - d) * DIFFUSION;
                }
            }
        }
        float[] swap = density;
        density = scratch;
        scratch = swap;
    }

    private float neighbour(int x, int y, int z, float self) {
        if (x < 0 || y < 0 || z < 0 || x >= nx || y >= ny || z >= nz) {
            return 0.0F;
        }
        int i = index(x, y, z);
        return open[i] ? density[i] : self;
    }

    /** Samples block and sky light for every open voxel (stored as level * 17, i.e. level / 15). */
    private void updateLightLevels(Level level) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int y = 0; y < ny; y++) {
            for (int z = 0; z < nz; z++) {
                for (int x = 0; x < nx; x++) {
                    int i = index(x, y, z);
                    if (!open[i]) {
                        lightLevels[i * 2] = 0;
                        lightLevels[i * 2 + 1] = 0;
                        continue;
                    }
                    pos.set((minX + x + 0.5) * VOXEL, (minY + y + 0.5) * VOXEL, (minZ + z + 0.5) * VOXEL);
                    lightLevels[i * 2] = (byte) (level.getBrightness(LightLayer.BLOCK, pos) * 17);
                    lightLevels[i * 2 + 1] = (byte) (level.getBrightness(LightLayer.SKY, pos) * 17);
                }
            }
        }
        lightVersion++;
    }

    @Override
    public void close() {
        gpu.close();
    }
}
