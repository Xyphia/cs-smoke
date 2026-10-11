package dev.xyphia.cssmoke;

import java.nio.ByteBuffer;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import org.lwjgl.system.MemoryUtil;

/**
 * GPU side of one smoke volume. There are no 3D textures in 26.3, so the density grid
 * is stored as a 2D atlas of Y slices laid side by side: (NX * NY) x NZ, R8.
 */
public final class SmokeVolumeGpu implements AutoCloseable {
    public static final int ATLAS_WIDTH = SmokeVolume.NX * SmokeVolume.NY;
    public static final int ATLAS_HEIGHT = SmokeVolume.NZ;
    public static final int UBO_SIZE = 256;

    private GpuTexture texture;
    private GpuTextureView view;
    private GpuBuffer ubo;
    private ByteBuffer staging;

    public void ensureCreated(GpuDevice device) {
        if (texture != null) {
            return;
        }
        texture = device.createTexture("cssmoke density", GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
            GpuFormat.R8_UNORM, ATLAS_WIDTH, ATLAS_HEIGHT, 1, 1);
        view = device.createTextureView(texture);
        ubo = device.createBuffer(() -> "cssmoke info", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, UBO_SIZE);
        staging = MemoryUtil.memAlloc(ATLAS_WIDTH * ATLAS_HEIGHT);
    }

    public GpuTexture texture() {
        return texture;
    }

    public GpuTextureView view() {
        return view;
    }

    public GpuBuffer ubo() {
        return ubo;
    }

    public ByteBuffer staging() {
        return staging;
    }

    @Override
    public void close() {
        if (view != null) {
            view.close();
            view = null;
        }
        if (texture != null) {
            texture.close();
            texture = null;
        }
        if (ubo != null) {
            ubo.close();
            ubo = null;
        }
        if (staging != null) {
            MemoryUtil.memFree(staging);
            staging = null;
        }
    }
}
