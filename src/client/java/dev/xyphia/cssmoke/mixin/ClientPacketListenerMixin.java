package dev.xyphia.cssmoke.mixin;

import dev.xyphia.cssmoke.SmokeManager;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {
    // TAIL, not HEAD: the handler first bounces from the network thread to the render
    // thread, and only the render-thread call gets this far.
    @Inject(method = "handleExplosion", at = @At("TAIL"))
    private void cssmoke$clearSmoke(ClientboundExplodePacket packet, CallbackInfo ci) {
        SmokeManager.explode(packet.center(), packet.radius());
    }
}
