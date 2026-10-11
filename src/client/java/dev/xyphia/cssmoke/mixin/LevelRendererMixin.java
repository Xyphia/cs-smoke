package dev.xyphia.cssmoke.mixin;

import dev.xyphia.cssmoke.SmokeRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {
    // Inside the "main" frame-graph pass, after the main render pass (solid, translucent,
    // clouds, weather) and the OIT composite are finished, so main depth can be sampled.
    @Inject(
        method = "lambda$addMainPass$0",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/LevelRenderer;executeOutline(Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;)V"
        )
    )
    private void cssmoke$renderSmoke(CallbackInfo ci) {
        SmokeRenderer.render();
    }
}
