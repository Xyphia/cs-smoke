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
 * Raymarches every smoke volume as a fullscreen pass over the main colour target,
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

    /** Noise is evaluated in world space; wrap the camera so floats stay precise. */
    private static final double NOISE_WRAP = 512.0;

    private SmokeRenderer() {
    }

    public static void render() {
        if (SmokeManager.volumes().isEmpty()) {
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
        float noiseX = (float) (cam.x - Math.floor(cam.x / NOISE_WRAP) * NOISE_WRAP);
        float noiseY = (float) (cam.y - Math.floor(cam.y / NOISE_WRAP) * NOISE_WRAP);
        float noiseZ = (float) (cam.z - Math.floor(cam.z / NOISE_WRAP) * NOISE_WRAP);

        GpuDevice device = RenderSystem.getDevice();
        CommandEncoder encoder = device.createCommandEncoder();

        for (SmokeVolume volume : SmokeManager.volumes()) {
            SmokeVolumeGpu gpu = volume.gpu();
            gpu.ensureCreated(device);

            ByteBuffer staging = gpu.staging();
            volume.writeInterpolated(staging, partialTick);
            encoder.writeToTexture(gpu.texture(), staging, 0, 0, 0, 0, SmokeVolumeGpu.ATLAS_WIDTH, SmokeVolumeGpu.ATLAS_HEIGHT);

            try (MemoryStack stack = MemoryStack.stackPush()) {
                ByteBuffer info = Std140Builder.onStack(stack, SmokeVolumeGpu.UBO_SIZE)
                    .putMat4f(invViewProj)
                    .putVec4((float) (volume.originX - cam.x), (float) (volume.originY - cam.y), (float) (volume.originZ - cam.z), SmokeVolume.VOXEL)
                    .putVec4(SmokeVolume.NX, SmokeVolume.NY, SmokeVolume.NZ, time)
                    .putVec4(noiseX, noiseY, noiseZ, volume.brightness())
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
