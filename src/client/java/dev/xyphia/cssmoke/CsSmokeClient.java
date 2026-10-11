package dev.xyphia.cssmoke;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;

public final class CsSmokeClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) -> dispatcher.register(
            ClientCommands.literal("smoke")
                .executes(ctx -> {
                    Player player = ctx.getSource().getPlayer();
                    SmokeManager.throwGrenade(player);
                    return 1;
                })
                .then(ClientCommands.literal("here").executes(ctx -> {
                    Player player = ctx.getSource().getPlayer();
                    SmokeManager.spawn(ctx.getSource().getLevel(), player.position());
                    return 1;
                }))
                .then(ClientCommands.literal("clear").executes(ctx -> {
                    int n = SmokeManager.clear();
                    ctx.getSource().sendFeedback(Component.literal("Cleared " + n + " smoke(s)"));
                    return n;
                }))
        ));

        ClientTickEvents.END_LEVEL_TICK.register(SmokeManager::tick);
    }
}
