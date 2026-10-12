package dev.xyphia.cssmoke;

import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.minecraft.util.Util;
import org.lwjgl.system.MemoryUtil;

/**
 * Pre-baked tiling noise, so the shader does one texel fetch per pixel cube instead of
 * evaluating Worley and value noise procedurally.
 * <ul>
 *   <li>Puffs: a 128^3 tile at one texel per 1/16-block cube (8 blocks), as 16x8 Y slices
 *       in a 2048x1024 RGBA8 texture. R = large puffs, G = small puffs (1 - Worley F1).</li>
 *   <li>Warp: a 16^3 tile at one texel per half block, as 16 Y slices in a 256x16 RGBA8
 *       texture. RGB = smooth offset in [-1, 1] mapped to [0, 1].</li>
 * </ul>
 */
public final class SmokeNoise {
    public static final int PUFF_TILE = 128;
    public static final int WARP_TILE = 16;
    private static final int PUFF_COLUMNS = 16;
    private static final int BIG_CELLS = 6;
    private static final int SMALL_CELLS = 15;
    private static final int WARP_CELLS = 3;

    private static CompletableFuture<ByteBuffer[]> pending;
    private static GpuTexture puffTexture;
    private static GpuTextureView puffView;
    private static GpuTexture warpTexture;
    private static GpuTextureView warpView;

    private SmokeNoise() {
    }

    /** Starts generating the noise in the background; cheap to call repeatedly. */
    public static void prepare() {
        if (pending == null) {
            pending = CompletableFuture.supplyAsync(() -> new ByteBuffer[] {generatePuffs(), generateWarp()}, Util.backgroundExecutor());
        }
    }

    /** Uploads the noise once it is ready. Returns false while it is still generating. */
    public static boolean ensureUploaded(GpuDevice device) {
        if (puffView != null) {
            return true;
        }
        prepare();
        if (!pending.isDone()) {
            return false;
        }
        ByteBuffer[] data = pending.join();
        int puffW = PUFF_TILE * PUFF_COLUMNS;
        int puffH = PUFF_TILE * (PUFF_TILE / PUFF_COLUMNS);
        puffTexture = device.createTexture("cssmoke puffs", GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
            GpuFormat.RGBA8_UNORM, puffW, puffH, 1, 1);
        warpTexture = device.createTexture("cssmoke warp", GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
            GpuFormat.RGBA8_UNORM, WARP_TILE * WARP_TILE, WARP_TILE, 1, 1);
        var encoder = device.createCommandEncoder();
        encoder.writeToTexture(puffTexture, data[0], 0, 0, 0, 0, puffW, puffH);
        encoder.writeToTexture(warpTexture, data[1], 0, 0, 0, 0, WARP_TILE * WARP_TILE, WARP_TILE);
        MemoryUtil.memFree(data[0]);
        MemoryUtil.memFree(data[1]);
        puffView = device.createTextureView(puffTexture);
        warpView = device.createTextureView(warpTexture);
        return true;
    }

    public static GpuTextureView puffs() {
        return puffView;
    }

    public static GpuTextureView warp() {
        return warpView;
    }

    private static ByteBuffer generatePuffs() {
        int n = PUFF_TILE;
        int width = n * PUFF_COLUMNS;
        ByteBuffer out = MemoryUtil.memAlloc(width * n * (n / PUFF_COLUMNS) * 4);
        for (int y = 0; y < n; y++) {
            int sliceX = (y % PUFF_COLUMNS) * n;
            int sliceY = (y / PUFF_COLUMNS) * n;
            for (int z = 0; z < n; z++) {
                for (int x = 0; x < n; x++) {
                    float big = 1.0F - Math.min(1.0F, worleyF1(x, y, z, BIG_CELLS, 11));
                    float small = 1.0F - Math.min(1.0F, worleyF1(x, y, z, SMALL_CELLS, 23));
                    int texel = ((sliceY + z) * width + sliceX + x) * 4;
                    out.put(texel, toByte(big));
                    out.put(texel + 1, toByte(small));
                    out.put(texel + 2, (byte) 0);
                    out.put(texel + 3, (byte) 255);
                }
            }
        }
        return out;
    }

    /** Periodic Worley F1 distance (in cell units) at a texel centre of the puff tile. */
    private static float worleyF1(int x, int y, int z, int cells, int seed) {
        float scale = cells / (float) PUFF_TILE;
        float ux = (x + 0.5F) * scale;
        float uy = (y + 0.5F) * scale;
        float uz = (z + 0.5F) * scale;
        int cx = (int) Math.floor(ux);
        int cy = (int) Math.floor(uy);
        int cz = (int) Math.floor(uz);
        float best = Float.MAX_VALUE;
        for (int dz = -1; dz <= 1; dz++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    int gx = cx + dx;
                    int gy = cy + dy;
                    int gz = cz + dz;
                    int wx = Math.floorMod(gx, cells);
                    int wy = Math.floorMod(gy, cells);
                    int wz = Math.floorMod(gz, cells);
                    float fx = gx + hash(wx, wy, wz, seed) - ux;
                    float fy = gy + hash(wx, wy, wz, seed + 1) - uy;
                    float fz = gz + hash(wx, wy, wz, seed + 2) - uz;
                    best = Math.min(best, fx * fx + fy * fy + fz * fz);
                }
            }
        }
        return (float) Math.sqrt(best);
    }

    private static ByteBuffer generateWarp() {
        int n = WARP_TILE;
        ByteBuffer out = MemoryUtil.memAlloc(n * n * n * 4);
        for (int y = 0; y < n; y++) {
            for (int z = 0; z < n; z++) {
                for (int x = 0; x < n; x++) {
                    int texel = (z * n * n + y * n + x) * 4;
                    for (int c = 0; c < 3; c++) {
                        out.put(texel + c, toByte(valueNoise(x, y, z, 37 + c * 5)));
                    }
                    out.put(texel + 3, (byte) 255);
                }
            }
        }
        return out;
    }

    /** Periodic smooth value noise in [0, 1] at a texel centre of the warp tile. */
    private static float valueNoise(int x, int y, int z, int seed) {
        float scale = WARP_CELLS / (float) WARP_TILE;
        float ux = (x + 0.5F) * scale;
        float uy = (y + 0.5F) * scale;
        float uz = (z + 0.5F) * scale;
        int ix = (int) Math.floor(ux);
        int iy = (int) Math.floor(uy);
        int iz = (int) Math.floor(uz);
        float fx = smooth(ux - ix);
        float fy = smooth(uy - iy);
        float fz = smooth(uz - iz);
        float result = 0.0F;
        for (int dz = 0; dz <= 1; dz++) {
            for (int dy = 0; dy <= 1; dy++) {
                for (int dx = 0; dx <= 1; dx++) {
                    float v = hash(Math.floorMod(ix + dx, WARP_CELLS), Math.floorMod(iy + dy, WARP_CELLS), Math.floorMod(iz + dz, WARP_CELLS), seed);
                    result += v * (dx == 1 ? fx : 1.0F - fx) * (dy == 1 ? fy : 1.0F - fy) * (dz == 1 ? fz : 1.0F - fz);
                }
            }
        }
        return result;
    }

    private static float smooth(float f) {
        return f * f * (3.0F - 2.0F * f);
    }

    private static float hash(int x, int y, int z, int seed) {
        int h = x * 0x27d4eb2d ^ y * 0x165667b1 ^ z * 0x2c1b3c6d ^ seed * 0x61c88647;
        h ^= h >>> 15;
        h *= 0x2c1b3c6d;
        h ^= h >>> 12;
        h *= 0x297a2d39;
        h ^= h >>> 15;
        return (h & 0xFFFFFF) / (float) 0x1000000;
    }

    private static byte toByte(float v) {
        return (byte) (int) (Math.min(1.0F, Math.max(0.0F, v)) * 255.0F + 0.5F);
    }
}
