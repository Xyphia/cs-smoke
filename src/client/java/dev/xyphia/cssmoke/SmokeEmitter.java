package dev.xyphia.cssmoke;

import java.util.Arrays;
import java.util.PriorityQueue;

import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * One grenade's worth of smoke: the CS2-style flood fill from its landing point and its
 * lifetime. Emitters don't hold density themselves; they tell the {@link SmokeRegion}
 * they live in how much smoke each voxel should have, so overlapping smokes merge.
 */
public final class SmokeEmitter {
    public static final int SX = 40;
    public static final int SY = 16;
    public static final int SZ = 40;

    private static final int GROW_TICKS = 26;
    private static final int LIFE_TICKS = 18 * 20;
    private static final int FADE_TICKS = 3 * 20;
    /** CS2 smoke: ~2.5-3 block radius in open space = ~800-1000 voxels. */
    private static final int VOXEL_BUDGET = 1200;
    /** Tighter cost limit for realistic cloud bounds. */
    private static final float MAX_COST = 8.0F;
    private static final float UP_COST = 1.8F;
    private static final float DOWN_COST = 0.8F;
    /** Confinement factor: how constrained is a voxel (0=free, 1=fully enclosed). */
    private float[] confinement = new float[SX * SY * SZ];

    /** Minimum corner of this emitter's grid, in world voxel coordinates. */
    public final int minX;
    public final int minY;
    public final int minZ;

    private final float[] cost = new float[SX * SY * SZ];
    private final float[] target = new float[SX * SY * SZ];
    private float reachedCost;
    private int age;

    public SmokeEmitter(Level level, Vec3 landing) {
        int vx = SmokeRegion.toVoxel(landing.x);
        int vy = SmokeRegion.toVoxel(landing.y + 0.25);
        int vz = SmokeRegion.toVoxel(landing.z);
        this.minX = vx - SX / 2;
        this.minY = vy - 4;
        this.minZ = vz - SZ / 2;
        floodFill(level, vx - minX, vy - minY, vz - minZ);
    }

    private static int index(int x, int y, int z) {
        return (y * SZ + z) * SX + x;
    }

    /** Calculate confinement factor: ratio of closed neighbors to open neighbors. */
    private void computeConfinement(boolean[] open) {
        for (int y = 0; y < SY; y++) {
            for (int z = 0; z < SZ; z++) {
                for (int x = 0; x < SX; x++) {
                    int i = index(x, y, z);
                    if (!open[i]) {
                        confinement[i] = 0.0F;
                        continue;
                    }
                    int openCount = 0;
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            for (int dx = -1; dx <= 1; dx++) {
                                if (dx == 0 && dy == 0 && dz == 0) continue;
                                int nx = x + dx;
                                int ny = y + dy;
                                int nz = z + dz;
                                if (nx >= 0 && nx < SX && ny >= 0 && ny < SY && nz >= 0 && nz < SZ) {
                                    if (open[index(nx, ny, nz)]) {
                                        openCount++;
                                    }
                                } else {
                                    openCount++;
                                }
                            }
                        }
                    }
                    confinement[i] = 1.0F - (openCount / 26.0F);
                }
            }
        }
    }

    public void tick() {
        age++;
    }

    public boolean isExpired() {
        return age >= LIFE_TICKS + FADE_TICKS;
    }

    /** True while the smoke is still billowing out, so the region fills quickly. */
    public boolean isGrowing() {
        return age < GROW_TICKS + 10;
    }

    /**
     * Writes this emitter's desired density into the region's goal array, keeping the
     * maximum where emitters overlap.
     */
    public void writeGoal(SmokeRegion region, float[] goal, boolean[] fastFill) {
        float grow = Math.min(1.0F, age / (float) GROW_TICKS);
        grow = 1.0F - (1.0F - grow) * (1.0F - grow);
        float reach = grow * reachedCost;
        float life = age < LIFE_TICKS ? 1.0F : Math.max(0.0F, 1.0F - (age - LIFE_TICKS) / (float) FADE_TICKS);
        boolean growing = isGrowing();

        for (int y = 0; y < SY; y++) {
            int ry = minY + y - region.minY;
            if (ry < 0 || ry >= region.ny) {
                continue;
            }
            for (int z = 0; z < SZ; z++) {
                int rz = minZ + z - region.minZ;
                if (rz < 0 || rz >= region.nz) {
                    continue;
                }
                for (int x = 0; x < SX; x++) {
                    int i = index(x, y, z);
                    if (cost[i] > reach) {
                        continue;
                    }
                    int rx = minX + x - region.minX;
                    if (rx < 0 || rx >= region.nx) {
                        continue;
                    }
                    int ri = region.index(rx, ry, rz);
                    float g = target[i] * life;
                    if (g > goal[ri]) {
                        goal[ri] = g;
                    }
                    fastFill[ri] |= growing;
                }
            }
        }
    }

    /**
     * Dijkstra flood fill over the 18-neighbourhood. Rising costs more than spreading
     * sideways, so open ground gives a squat dome; a corridor gives a long tongue of smoke
     * because the fixed voxel budget has to go somewhere.
     */
    private void floodFill(Level level, int sx, int sy, int sz) {
        boolean[] open = new boolean[SX * SY * SZ];
        for (int y = 0; y < SY; y++) {
            for (int z = 0; z < SZ; z++) {
                for (int x = 0; x < SX; x++) {
                    open[index(x, y, z)] = SmokeRegion.isOpen(level, minX + x, minY + y, minZ + z);
                }
            }
        }

        Arrays.fill(cost, Float.MAX_VALUE);
        while (sy < SY - 1 && !open[index(sx, sy, sz)]) {
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

            int x = i % SX;
            int z = (i / SX) % SZ;
            int y = i / (SX * SZ);
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
                        if (nx < 0 || ny < 0 || nz < 0 || nx >= SX || ny >= SY || nz >= SZ) {
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

        computeConfinement(open);

        for (int i = 0; i < cost.length; i++) {
            if (done[i]) {
                // Density adjusts for confinement: tight spaces get denser smoke.
                float edge = (reachedCost - cost[i]) / 3.0F;
                float baseDensity = 0.45F + edge;
                float confinedBoost = confinement[i] * 0.35F;
                target[i] = Math.max(0.25F, Math.min(1.0F, baseDensity + confinedBoost));
            } else {
                cost[i] = Float.MAX_VALUE;
            }
        }
    }
}
