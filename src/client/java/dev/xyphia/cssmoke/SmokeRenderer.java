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
import com.mojang.renderpearl.api.textures.FilterMode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;

/**
 * Draws every smoke region as voxels, in a fullscreen pass over the main colour target,
 * after the world (including translucents and clouds) has been drawn.
 */
public final class SmokeRenderer {
    private static final BindGroupLayout LAYOUT = BindGroupLayout.builder()
        .withUniform("SmokeInfo", UniformType.UNIFORM_BUFFER)
        .withUniform("DensitySampler", UniformType.COMBINED_IMAGE_SAMPLER)
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

    /** Voxel coordinates are wrapped before upload so the shader's noise keeps float precision. */
    private static final int VOXEL_WRAP = 4096;

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
        CommandEncoder encoder = device.createCommandEncoder();

        for (SmokeRegion region : SmokeManager.regions()) {
            SmokeRegionGpu gpu = region.gpu();
            int width = region.nx * region.ny;
            gpu.ensureCreated(device, width, region.nz);

            ByteBuffer staging = gpu.staging();
            region.writeInterpolated(staging, partialTick);
            encoder.writeToTexture(gpu.texture(), staging, 0, 0, 0, 0, width, region.nz);

            float v = SmokeRegion.VOXEL;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                ByteBuffer info = Std140Builder.onStack(stack, SmokeRegionGpu.UBO_SIZE)
                    .putMat4f(invViewProj)
                    .putVec4((float) (region.minX * v - cam.x), (float) (region.minY * v - cam.y), (float) (region.minZ * v - cam.z), v)
                    .putVec4(region.nx, region.ny, region.nz, time)
                    .putVec4(Math.floorMod(region.minX, VOXEL_WRAP), Math.floorMod(region.minY, VOXEL_WRAP), Math.floorMod(region.minZ, VOXEL_WRAP), region.brightness())
                    .get();
                encoder.writeToBuffer(gpu.ubo().slice(), info);
            }

            try (RenderPass pass = encoder.createRenderPass(() -> "cssmoke", main.getColorTextureView(), Optional.empty())) {
                pass.setPipeline(RenderSystem.getCompiledPipeline(PIPELINE));
                pass.setUniform("SmokeInfo", gpu.ubo());
                pass.setUniform("DensitySampler", gpu.view(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
                pass.setUniform("DepthSampler", main.getDepthTextureView(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
                pass.draw(3, 1, 0, 0);
            }
        }
    }
}
