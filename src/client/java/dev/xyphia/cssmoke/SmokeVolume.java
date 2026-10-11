package dev.xyphia.cssmoke;

import java.util.PriorityQueue;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * CS2-style voxel smoke. On spawn the smoke flood-fills outward from the landing point,
 * flowing around walls and into corridors until it has used its voxel budget. Each tick
 * the density relaxes toward that shape, diffuses a little, and is shoved aside by
 * anything moving through it.
 */
public final class SmokeVolume implements AutoCloseable {
    public static final int NX = 40;
    public static final int NY = 16;
    public static final int NZ = 40;
    public static final float VOXEL = 0.5F;

    private static final int GROW_TICKS = 26;
    private static final int LIFE_TICKS = 18 * 20;
    private static final int FADE_TICKS = 3 * 20;
    private static final int VOXEL_BUDGET = 3400;
    private static final float MAX_COST = 12.5F;
    private static final float UP_COST = 1.8F;
    private static final float DOWN_COST = 0.8F;
    private static final float FILL_RATE = 0.22F;
    private static final float REFILL_RATE = 0.018F;
    private static final float DECAY_RATE = 0.08F;
    private static final float DIFFUSION = 0.035F;
    private static final float MAX_DENSITY = 1.6F;

    /** World-space minimum corner of the grid. */
    public final double originX;
    public final double originY;
    public final double originZ;
    /** Where the grenade landed; used for lighting. */
    public final BlockPos center;

    private final boolean[] open = new boolean[NX * NY * NZ];
    private final float[] cost = new float[NX * NY * NZ];
    private final float[] target = new float[NX * NY * NZ];
    private float[] density = new float[NX * NY * NZ];
    private float[] previous = new float[NX * NY * NZ];
    private float[] scratch = new float[NX * NY * NZ];
    private float reachedCost;
    private int age;
    private float brightness = 1.0F;

    private final SmokeVolumeGpu gpu = new SmokeVolumeGpu();

    public SmokeVolume(Level level, Vec3 landing) {
        this.originX = landing.x - NX * VOXEL * 0.5;
        this.originY = Math.floor(landing.y) - 2.0;
        this.originZ = landing.z - NZ * VOXEL * 0.5;
        this.center = BlockPos.containing(landing.add(0.0, 0.5, 0.0));
        classifyVoxels(level);
        floodFill(landing);
        updateBrightness(level);
    }

    public static int index(int x, int y, int z) {
        return (y * NZ + z) * NX + x;
    }

    public int age() {
        return age;
    }

    public float brightness() {
        return brightness;
    }

    public SmokeVolumeGpu gpu() {
        return gpu;
    }

    public boolean isDone() {
        if (age < LIFE_TICKS + FADE_TICKS) {
            return false;
        }
        for (float d : density) {
            if (d > 0.01F) {
                return false;
            }
        }
        return true;
    }

    /** Writes the density interpolated between the last two ticks, as bytes. */
    public void writeInterpolated(java.nio.ByteBuffer out, float partialTick) {
        for (int y = 0; y < NY; y++) {
            for (int z = 0; z < NZ; z++) {
                for (int x = 0; x < NX; x++) {
                    int i = index(x, y, z);
                    float d = previous[i] + (density[i] - previous[i]) * partialTick;
                    int v = (int) (Math.min(1.0F, d) * 255.0F + 0.5F);
                    // Atlas layout: Y slices side by side along U, Z along V.
                    out.put(z * (NX * NY) + y * NX + x, (byte) v);
                }
            }
        }
    }

    private void classifyVoxels(Level level) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int y = 0; y < NY; y++) {
            for (int z = 0; z < NZ; z++) {
                for (int x = 0; x < NX; x++) {
                    double wx = originX + (x + 0.5) * VOXEL;
                    double wy = originY + (y + 0.5) * VOXEL;
                    double wz = originZ + (z + 0.5) * VOXEL;
                    pos.set(wx, wy, wz);
                    open[index(x, y, z)] = !isSolidAt(level, pos, wx, wy, wz);
                }
            }
        }
    }

    private static boolean isSolidAt(Level level, BlockPos pos, double wx, double wy, double wz) {
        BlockState state = level.getBlockState(pos);
        VoxelShape shape = state.getCollisionShape(level, pos);
        if (shape.isEmpty()) {
            return false;
        }
        double lx = wx - pos.getX();
        double ly = wy - pos.getY();
        double lz = wz - pos.getZ();
        for (AABB box : shape.toAabbs()) {
            if (lx >= box.minX && lx <= box.maxX && ly >= box.minY && ly <= box.maxY && lz >= box.minZ && lz <= box.maxZ) {
                return true;
            }
        }
        return false;
    }

    /**
     * Dijkstra flood fill over the 18-neighbourhood. Rising costs more than spreading
     * sideways, so open ground gives a squat dome; a corridor gives a long tongue of smoke
     * because the fixed voxel budget has to go somewhere.
     */
    private void floodFill(Vec3 landing) {
        java.util.Arrays.fill(cost, Float.MAX_VALUE);
        int sx = clamp((int) ((landing.x - originX) / VOXEL), 0, NX - 1);
        int sy = clamp((int) ((landing.y + 0.25 - originY) / VOXEL), 0, NY - 1);
        int sz = clamp((int) ((landing.z - originZ) / VOXEL), 0, NZ - 1);
        while (sy < NY - 1 && !open[index(sx, sy, sz)]) {
            sy++;
        }
        if (!open[index(sx, sy, sz)]) {
            return;
        }

        PriorityQueue<long[]> queue = new PriorityQueue<>((a, b) -> Float.compare(Float.intBitsToFloat((int) a[1]), Float.intBitsToFloat((int) b[1])));
        int start = index(sx, sy, sz);
        cost[start] = 0.0F;
        queue.add(new long[] {start, Float.floatToIntBits(0.0F)});
        boolean[] done = new boolean[open.length];
        int accepted = 0;

        while (!queue.isEmpty() && accepted < VOXEL_BUDGET) {
            long[] entry = queue.poll();
            int i = (int) entry[0];
            float c = Float.intBitsToFloat((int) entry[1]);
            if (done[i] || c > cost[i]) {
                continue;
            }
            if (c > MAX_COST * 2.2F) {
                break;
            }
            done[i] = true;
            accepted++;
            reachedCost = c;

            int x = i % NX;
            int z = (i / NX) % NZ;
            int y = i / (NX * NZ);
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        int manhattan = Math.abs(dx) + Math.abs(dy) + Math.abs(dz);
                        if (manhattan == 0 || manhattan == 3) {
                            continue;
                        }
                        int nx = x + dx;
                        int ny = y + dy;
                        int nz = z + dz;
                        if (nx < 0 || ny < 0 || nz < 0 || nx >= NX || ny >= NY || nz >= NZ) {
                            continue;
                        }
                        int ni = index(nx, ny, nz);
                        if (!open[ni] || done[ni]) {
                            continue;
                        }
                        // Edge moves must not squeeze diagonally between two solid voxels.
                        if (manhattan == 2
                            && ((dx != 0 && !open[index(x + dx, y, z)])
                                || (dy != 0 && !open[index(x, y + dy, z)])
                                || (dz != 0 && !open[index(x, y, z + dz)]))) {
                            continue;
                        }
                        float vertical = dy > 0 ? UP_COST : dy < 0 ? DOWN_COST : 0.0F;
                        float step = (float) Math.sqrt(dx * dx + dz * dz + vertical * vertical * dy * dy);
                        float nc = c + step;
                        if (nc < cost[ni]) {
                            cost[ni] = nc;
                            queue.add(new long[] {ni, Float.floatToIntBits(nc)});
                        }
                    }
                }
            }
        }

        for (int i = 0; i < open.length; i++) {
            if (done[i]) {
                // Dense core, softer rim; the shader's noise breaks the rim into billows.
                float edge = (reachedCost - cost[i]) / 3.0F;
                target[i] = Math.max(0.25F, Math.min(1.0F, 0.45F + edge));
            } else {
                cost[i] = Float.MAX_VALUE;
            }
        }
    }

    public void tick(Level level) {
        age++;
        float[] swap = previous;
        previous = density;
        density = swap;
        System.arraycopy(previous, 0, density, 0, density.length);

        float grow = Math.min(1.0F, age / (float) GROW_TICKS);
        grow = 1.0F - (1.0F - grow) * (1.0F - grow);
        float reach = grow * reachedCost;
        float life = age < LIFE_TICKS ? 1.0F : Math.max(0.0F, 1.0F - (age - LIFE_TICKS) / (float) FADE_TICKS);
        float fill = age < GROW_TICKS + 10 ? FILL_RATE : REFILL_RATE;

        for (int i = 0; i < density.length; i++) {
            if (!open[i]) {
                density[i] = 0.0F;
                continue;
            }
            float goal = cost[i] <= reach ? target[i] * life : 0.0F;
            float d = density[i];
            density[i] = d + (goal - d) * (d < goal ? fill : DECAY_RATE);
        }

        pushByEntities(level);
        diffuse();

        if (age % 10 == 0) {
            updateBrightness(level);
        }
    }

    /** Entities carve a hole and shove the smoke ahead of and around them. */
    private void pushByEntities(Level level) {
        double maxX = originX + NX * VOXEL;
        double maxY = originY + NY * VOXEL;
        double maxZ = originZ + NZ * VOXEL;
        AABB bounds = new AABB(originX, originY, originZ, maxX, maxY, maxZ).inflate(1.0);

        for (Entity entity : ((net.minecraft.client.multiplayer.ClientLevel) level).entitiesForRendering()) {
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
            double rx = box.getXsize() * 0.5 + 0.65;
            double ry = box.getYsize() * 0.5 + 0.4;
            double rz = box.getZsize() * 0.5 + 0.65;
            float strength = (float) (0.06 + Math.min(1.0, speed * 4.0) * 0.8);
            pushEllipsoid(c, rx, ry, rz, vx, vy, vz, speed, strength);
        }
    }

    private void pushEllipsoid(Vec3 c, double rx, double ry, double rz, double vx, double vy, double vz, double speed, float strength) {
        int x0 = clamp((int) Math.floor((c.x - rx - originX) / VOXEL), 0, NX - 1);
        int x1 = clamp((int) Math.floor((c.x + rx - originX) / VOXEL), 0, NX - 1);
        int y0 = clamp((int) Math.floor((c.y - ry - originY) / VOXEL), 0, NY - 1);
        int y1 = clamp((int) Math.floor((c.y + ry - originY) / VOXEL), 0, NY - 1);
        int z0 = clamp((int) Math.floor((c.z - rz - originZ) / VOXEL), 0, NZ - 1);
        int z1 = clamp((int) Math.floor((c.z + rz - originZ) / VOXEL), 0, NZ - 1);
        double inv = speed > 1.0E-4 ? 1.0 / speed : 0.0;

        for (int y = y0; y <= y1; y++) {
            for (int z = z0; z <= z1; z++) {
                for (int x = x0; x <= x1; x++) {
                    int i = index(x, y, z);
                    if (!open[i] || density[i] <= 0.001F) {
                        continue;
                    }
                    double px = originX + (x + 0.5) * VOXEL - c.x;
                    double py = originY + (y + 0.5) * VOXEL - c.y;
                    double pz = originZ + (z + 0.5) * VOXEL - c.z;
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
                    int tx = (int) Math.floor((c.x + px + dx / dl * reach - originX) / VOXEL);
                    int ty = (int) Math.floor((c.y + py + dy / dl * reach - originY) / VOXEL);
                    int tz = (int) Math.floor((c.z + pz + dz / dl * reach - originZ) / VOXEL);

                    density[i] -= moved;
                    if (tx >= 0 && ty >= 0 && tz >= 0 && tx < NX && ty < NY && tz < NZ) {
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
        for (int y = 0; y < NY; y++) {
            for (int z = 0; z < NZ; z++) {
                for (int x = 0; x < NX; x++) {
                    int i = index(x, y, z);
                    float d = density[i];
                    if (!open[i]) {
                        scratch[i] = 0.0F;
                        continue;
                    }
                    float sum = 0.0F;
                    sum += neighbour(x - 1, y, z, d);
                    sum += neighbour(x + 1, y, z, d);
                    sum += neighbour(x, y - 1, z, d);
                    sum += neighbour(x, y + 1, z, d);
                    sum += neighbour(x, y, z - 1, d);
                    sum += neighbour(x, y, z + 1, d);
                    scratch[i] = d + (sum / 6.0F - d) * DIFFUSION;
                }
            }
        }
        float[] swap = density;
        density = scratch;
        scratch = swap;
    }

    private float neighbour(int x, int y, int z, float self) {
        if (x < 0 || y < 0 || z < 0 || x >= NX || y >= NY || z >= NZ) {
            return 0.0F;
        }
        int i = index(x, y, z);
        return open[i] ? density[i] : self;
    }

    private void updateBrightness(Level level) {
        BlockPos p = center.above();
        float day = 1.0F - level.getSkyDarken() / 11.0F;
        float sky = level.getBrightness(LightLayer.SKY, p) / 15.0F * day;
        float block = level.getBrightness(LightLayer.BLOCK, p) / 15.0F * 0.85F;
        brightness = 0.1F + 0.9F * Math.max(sky, block);
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : Math.min(v, hi);
    }

    @Override
    public void close() {
        gpu.close();
    }
}
