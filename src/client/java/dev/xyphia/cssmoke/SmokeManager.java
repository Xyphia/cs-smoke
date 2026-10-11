package dev.xyphia.cssmoke;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/** Owns every live grenade and smoke region on the client. */
public final class SmokeManager {
    public static final int MAX_EMITTERS = 16;

    private static final List<SmokeGrenade> GRENADES = new ArrayList<>();
    private static final List<SmokeRegion> REGIONS = new ArrayList<>();
    private static ClientLevel currentLevel;

    private SmokeManager() {
    }

    public static List<SmokeRegion> regions() {
        return REGIONS;
    }

    public static void throwGrenade(Player player) {
        GRENADES.add(SmokeGrenade.thrownBy(player));
    }

    /** Pops a smoke; any regions it overlaps merge with it into one cloud. */
    public static void spawn(Level level, Vec3 pos) {
        int emitters = REGIONS.stream().mapToInt(SmokeRegion::emitterCount).sum();
        if (emitters >= MAX_EMITTERS && !REGIONS.isEmpty()) {
            REGIONS.removeFirst().close();
        }

        SmokeEmitter emitter = new SmokeEmitter(level, pos);
        List<SmokeRegion> touching = new ArrayList<>();
        for (SmokeRegion region : REGIONS) {
            if (region.overlaps(emitter)) {
                touching.add(region);
            }
        }

        SmokeRegion merged = touching.isEmpty() ? null : SmokeRegion.merge(level, touching, emitter);
        if (merged != null) {
            for (SmokeRegion region : touching) {
                REGIONS.remove(region);
                region.close();
            }
            REGIONS.add(merged);
        } else {
            // Nothing to merge with, or the union would be too large for one region.
            REGIONS.add(SmokeRegion.of(level, emitter));
        }
    }

    /** Called for every explosion the client hears about. */
    public static void explode(Vec3 center, float radius) {
        for (SmokeRegion region : REGIONS) {
            region.blast(center, radius);
        }
    }

    public static int clear() {
        int n = REGIONS.stream().mapToInt(SmokeRegion::emitterCount).sum() + GRENADES.size();
        REGIONS.forEach(SmokeRegion::close);
        REGIONS.clear();
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

        for (Iterator<SmokeRegion> it = REGIONS.iterator(); it.hasNext(); ) {
            SmokeRegion region = it.next();
            region.tick(level);
            if (region.isDone()) {
                region.close();
                it.remove();
            }
        }
    }
}
