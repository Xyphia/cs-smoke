package dev.xyphia.cssmoke;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/** Owns every live grenade and smoke volume on the client. */
public final class SmokeManager {
    public static final int MAX_VOLUMES = 8;

    private static final List<SmokeGrenade> GRENADES = new ArrayList<>();
    private static final List<SmokeVolume> VOLUMES = new ArrayList<>();
    private static ClientLevel currentLevel;

    private SmokeManager() {
    }

    public static List<SmokeVolume> volumes() {
        return VOLUMES;
    }

    public static void throwGrenade(Player player) {
        GRENADES.add(SmokeGrenade.thrownBy(player));
    }

    public static void spawn(Level level, Vec3 pos) {
        if (VOLUMES.size() >= MAX_VOLUMES) {
            VOLUMES.removeFirst().close();
        }
        VOLUMES.add(new SmokeVolume(level, pos));
    }

    public static int clear() {
        int n = VOLUMES.size() + GRENADES.size();
        VOLUMES.forEach(SmokeVolume::close);
        VOLUMES.clear();
        GRENADES.clear();
        return n;
    }

    public static void tick(ClientLevel level) {
        if (level != currentLevel) {
            clear();
            currentLevel = level;
        }

        for (Iterator<SmokeGrenade> it = GRENADES.iterator(); it.hasNext(); ) {
            SmokeGrenade grenade = it.next();
            if (grenade.tick(level)) {
                it.remove();
                spawn(level, grenade.position());
            }
        }

        for (Iterator<SmokeVolume> it = VOLUMES.iterator(); it.hasNext(); ) {
            SmokeVolume volume = it.next();
            volume.tick(level);
            if (volume.isDone()) {
                volume.close();
                it.remove();
            }
        }
    }
}
