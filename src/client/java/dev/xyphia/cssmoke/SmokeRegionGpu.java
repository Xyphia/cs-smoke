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
 * is stored as a 2D atlas of Y slices laid side by side: (nx * ny) x nz, RGBA8
 * (previous density, density, light, dilated density).
 */
public final class SmokeRegionGpu implements AutoCloseable {
    public static final int UBO_SIZE = 256;

    private GpuTexture texture;
    private GpuTextureView view;
    private GpuBuffer ubo;
    private GpuTexture brickTexture;
    private GpuTextureView brickView;
    private ByteBuffer brickStaging;
    private int brickWidth;
    private int brickHeight;
    private ByteBuffer staging;
    private int width;
    private int height;
    private int uploadedVersion = -1;
    private GpuTexture lightTexture;
    private GpuTextureView lightView;
    private ByteBuffer lightStaging;
    private int uploadedLightVersion = -1;

    public void ensureCreated(GpuDevice device, int width, int height, int brickWidth, int brickHeight) {
        if (texture != null && this.width == width && this.height == height) {
            return;
        }
        close();
        uploadedVersion = -1;
        uploadedLightVersion = -1;
        this.width = width;
        this.height = height;
        texture = device.createTexture("cssmoke density", GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
            GpuFormat.RGBA8_UNORM, width, height, 1, 1);
        view = device.createTextureView(texture);
        ubo = device.createBuffer(() -> "cssmoke info", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, UBO_SIZE);
        staging = MemoryUtil.memAlloc(width * height * 4);
        this.brickWidth = brickWidth;
        this.brickHeight = brickHeight;
        brickTexture = device.createTexture("cssmoke bricks", GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
            GpuFormat.R8_UNORM, brickWidth, brickHeight, 1, 1);
        brickView = device.createTextureView(brickTexture);
        brickStaging = MemoryUtil.memAlloc(brickWidth * brickHeight);
        lightTexture = device.createTexture("cssmoke light levels", GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
            GpuFormat.RG8_UNORM, width, height, 1, 1);
        lightView = device.createTextureView(lightTexture);
        lightStaging = MemoryUtil.memAlloc(width * height * 2);
    }

    public GpuTexture lightTexture() {
        return lightTexture;
    }

    public GpuTextureView lightView() {
        return lightView;
    }

    public ByteBuffer lightStaging() {
        return lightStaging;
    }

    /** True if the light levels changed since the last upload; marks them uploaded. */
    public boolean needsLightUpload(int version) {
        if (version == uploadedLightVersion) {
            return false;
        }
        uploadedLightVersion = version;
        return true;
    }

    public GpuTexture brickTexture() {
        return brickTexture;
    }

    public GpuTextureView brickView() {
        return brickView;
    }

    public ByteBuffer brickStaging() {
        return brickStaging;
    }

    public int brickWidth() {
        return brickWidth;
    }

    public int brickHeight() {
        return brickHeight;
    }

    /** True if the region has changed since the last upload; marks it uploaded. */
    public boolean needsUpload(int version) {
        if (version == uploadedVersion) {
            return false;
        }
        uploadedVersion = version;
        return true;
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
        if (brickView != null) {
            brickView.close();
            brickView = null;
        }
        if (brickTexture != null) {
            brickTexture.close();
            brickTexture = null;
        }
        if (brickStaging != null) {
            MemoryUtil.memFree(brickStaging);
            brickStaging = null;
        }
        if (lightView != null) {
            lightView.close();
            lightView = null;
        }
        if (lightTexture != null) {
            lightTexture.close();
            lightTexture = null;
        }
        if (lightStaging != null) {
            MemoryUtil.memFree(lightStaging);
            lightStaging = null;
        }
    }
}
