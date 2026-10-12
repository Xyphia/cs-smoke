package dev.xyphia.cssmoke;

import java.nio.ByteBuffer;
import java.util.Optional;

import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryStack;

/**
 * Draws every smoke region after the world (including translucents and clouds). The
 * raymarch runs at half resolution into an offscreen target, one fullscreen draw per
 * region, and is then composited over the main colour target.
 */
public final class SmokeRenderer {
    private static final BindGroupLayout LAYOUT = BindGroupLayout.builder()
        .withUniform("SmokeInfo", UniformType.UNIFORM_BUFFER)
        .withUniform("DensitySampler", UniformType.COMBINED_IMAGE_SAMPLER)
        .withUniform("BrickSampler", UniformType.COMBINED_IMAGE_SAMPLER)
        .withUniform("LightLevelSampler", UniformType.COMBINED_IMAGE_SAMPLER)
        .withUniform("LightmapSampler", UniformType.COMBINED_IMAGE_SAMPLER)
        .withUniform("PuffSampler", UniformType.COMBINED_IMAGE_SAMPLER)
        .withUniform("WarpSampler", UniformType.COMBINED_IMAGE_SAMPLER)
        .withUniform("DepthSampler", UniformType.COMBINED_IMAGE_SAMPLER)
        .build();

    public static final RenderPipeline PIPELINE = RenderPipeline.builder()
        .withLocation(Identifier.fromNamespaceAndPath("cssmoke", "pipeline/smoke"))
        .withVertexShader(Identifier.fromNamespaceAndPath("cssmoke", "core/smoke"))
        .withFragmentShader(Identifier.fromNamespaceAndPath("cssmoke", "core/smoke"))
        .withBindGroupLayout(LAYOUT)
        .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT_PREMULTIPLIED_ALPHA))
        .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
        .withCull(false)
        .build();

    private static final BindGroupLayout COMPOSITE_LAYOUT = BindGroupLayout.builder()
        .withUniform("SmokeSampler", UniformType.COMBINED_IMAGE_SAMPLER)
        .build();

    public static final RenderPipeline COMPOSITE_PIPELINE = RenderPipeline.builder()
        .withLocation(Identifier.fromNamespaceAndPath("cssmoke", "pipeline/smoke_composite"))
        .withVertexShader(Identifier.fromNamespaceAndPath("cssmoke", "core/smoke"))
        .withFragmentShader(Identifier.fromNamespaceAndPath("cssmoke", "core/smoke_composite"))
        .withBindGroupLayout(COMPOSITE_LAYOUT)
        .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT_PREMULTIPLIED_ALPHA))
        .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
        .withCull(false)
        .build();

    /** Voxel coordinates are wrapped before upload so the shader's noise keeps float precision. */
    private static final int VOXEL_WRAP = 4096;
    /** The raymarch runs at 1/RESOLUTION_DIVISOR of the screen resolution on each axis. */
    private static final int RESOLUTION_DIVISOR = 2;

    private static GpuTexture smokeTexture;
    private static GpuTextureView smokeView;
    private static int smokeWidth;
    private static int smokeHeight;

    private SmokeRenderer() {
    }

    public static void render() {
        if (SmokeManager.regions().isEmpty()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }

        RenderTarget main = mc.gameRenderer.mainRenderTarget();
        CameraRenderState camera = mc.gameRenderer.gameRenderState().levelRenderState.cameraRenderState;
        Vec3 cam = camera.pos;
        Matrix4f invViewProj = new Matrix4f(camera.projectionMatrix).mul(camera.viewRotationMatrix).invert();
        float partialTick = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        float time = (float) ((mc.level.getGameTime() % 24000L) + partialTick) / 20.0F;

        GpuDevice device = RenderSystem.getDevice();
        if (!SmokeNoise.ensureUploaded(device)) {
            return;
        }
        CommandEncoder encoder = device.createCommandEncoder();
        ensureSmokeTarget(device, Math.max(1, main.width / RESOLUTION_DIVISOR), Math.max(1, main.height / RESOLUTION_DIVISOR));

        // Uploads first: copies can't be recorded while a render pass is open.
        boolean any = false;
        for (SmokeRegion region : SmokeManager.regions()) {
            SmokeRegionGpu gpu = region.gpu();
            int width = region.nx * region.ny;
            gpu.ensureCreated(device, width, region.nz, region.brickX() * region.brickY(), region.brickZ());

            // The density only changes once per tick; the shader interpolates between ticks.
            if (gpu.needsUpload(region.version())) {
                ByteBuffer staging = gpu.staging();
                region.writeUpload(staging);
                encoder.writeToTexture(gpu.texture(), staging, 0, 0, 0, 0, width, region.nz);
                region.writeBricks(gpu.brickStaging());
                encoder.writeToTexture(gpu.brickTexture(), gpu.brickStaging(), 0, 0, 0, 0, gpu.brickWidth(), gpu.brickHeight());
            }
            if (gpu.needsLightUpload(region.lightVersion())) {
                region.writeLight(gpu.lightStaging());
                encoder.writeToTexture(gpu.lightTexture(), gpu.lightStaging(), 0, 0, 0, 0, width, region.nz);
            }
            int[] occ = region.occupiedBounds();
            if (occ[3] <= occ[0]) {
                continue;
            }
            any = true;

            float v = SmokeRegion.VOXEL;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                ByteBuffer info = Std140Builder.onStack(stack, SmokeRegionGpu.UBO_SIZE)
                    .putMat4f(invViewProj)
                    .putVec4((float) (region.minX * v - cam.x), (float) (region.minY * v - cam.y), (float) (region.minZ * v - cam.z), v)
                    .putVec4(region.nx, region.ny, region.nz, time)
                    .putVec4(Math.floorMod(region.minX, VOXEL_WRAP), Math.floorMod(region.minY, VOXEL_WRAP), Math.floorMod(region.minZ, VOXEL_WRAP), 0.0F)
                    .putVec4(partialTick, 0.0F, 0.0F, 0.0F)
                    .putVec4(occ[0], occ[1], occ[2], 0.0F)
                    .putVec4(occ[3], occ[4], occ[5], 0.0F)
                    .get();
                encoder.writeToBuffer(gpu.ubo().slice(), info);
            }
        }
        if (!any) {
            return;
        }

        try (RenderPass pass = encoder.createRenderPass(() -> "cssmoke raymarch", smokeView, Optional.of(new Vector4f(0.0F, 0.0F, 0.0F, 0.0F)))) {
            pass.setPipeline(RenderSystem.getCompiledPipeline(PIPELINE));
            pass.setUniform("PuffSampler", SmokeNoise.puffs(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
            pass.setUniform("WarpSampler", SmokeNoise.warp(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
            pass.setUniform("DepthSampler", main.getDepthTextureView(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
            // The world's own lightmap, so the smoke is lit exactly like the blocks around it.
            pass.setUniform("LightmapSampler", mc.gameRenderer.levelLightmap(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
            for (SmokeRegion region : SmokeManager.regions()) {
                int[] occ = region.occupiedBounds();
                if (occ[3] <= occ[0]) {
                    continue;
                }
                SmokeRegionGpu gpu = region.gpu();
                pass.setUniform("SmokeInfo", gpu.ubo());
                pass.setUniform("DensitySampler", gpu.view(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
                pass.setUniform("BrickSampler", gpu.brickView(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
                pass.setUniform("LightLevelSampler", gpu.lightView(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
                pass.draw(3, 1, 0, 0);
            }
        }

        try (RenderPass pass = encoder.createRenderPass(() -> "cssmoke composite", main.getColorTextureView(), Optional.empty())) {
            pass.setPipeline(RenderSystem.getCompiledPipeline(COMPOSITE_PIPELINE));
            pass.setUniform("SmokeSampler", smokeView, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
            pass.draw(3, 1, 0, 0);
        }
    }

    private static void ensureSmokeTarget(GpuDevice device, int width, int height) {
        if (smokeTexture != null && smokeWidth == width && smokeHeight == height) {
            return;
        }
        if (smokeTexture != null) {
            smokeView.close();
            smokeTexture.close();
        }
        smokeWidth = width;
        smokeHeight = height;
        smokeTexture = device.createTexture("cssmoke half-res", GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING,
            GpuFormat.RGBA8_UNORM, width, height, 1, 1);
        smokeView = device.createTextureView(smokeTexture);
    }
}
