package dev.xyphia.cssmoke;

import java.nio.ByteBuffer;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import org.lwjgl.system.MemoryUtil;

/**
 * GPU side of one smoke region. There are no 3D textures in 26.3, so the density grid
 * is stored as a 2D atlas of Y slices laid side by side: (nx * ny) x nz, RG8
 * (density, dilated density).
 */
public final class SmokeRegionGpu implements AutoCloseable {
    public static final int UBO_SIZE = 256;

    private GpuTexture texture;
    private GpuTextureView view;
    private GpuBuffer ubo;
    private ByteBuffer staging;
    private int width;
    private int height;

    public void ensureCreated(GpuDevice device, int width, int height) {
        if (texture != null && this.width == width && this.height == height) {
            return;
        }
        close();
        this.width = width;
        this.height = height;
        texture = device.createTexture("cssmoke density", GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
            GpuFormat.RG8_UNORM, width, height, 1, 1);
        view = device.createTextureView(texture);
        ubo = device.createBuffer(() -> "cssmoke info", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, UBO_SIZE);
        staging = MemoryUtil.memAlloc(width * height * 2);
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
