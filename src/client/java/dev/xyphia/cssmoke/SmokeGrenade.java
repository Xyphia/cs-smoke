package dev.xyphia.cssmoke;

import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** A thrown smoke grenade: bounces around and pops once it comes to rest. */
public final class SmokeGrenade {
    private static final double GRAVITY = 0.05;
    private static final double DRAG = 0.99;
    private static final double THROW_SPEED = 1.3;
    private static final int MAX_AGE = 100;

    private final Player thrower;
    private Vec3 pos;
    private Vec3 vel;
    private int age;
    private int restingTicks;

    private SmokeGrenade(Player thrower, Vec3 pos, Vec3 vel) {
        this.thrower = thrower;
        this.pos = pos;
        this.vel = vel;
    }

    public static SmokeGrenade thrownBy(Player player) {
        Vec3 look = player.getViewVector(1.0F);
        Vec3 start = player.getEyePosition().add(look.scale(0.3));
        Vec3 vel = look.scale(THROW_SPEED).add(player.getDeltaMovement());
        return new SmokeGrenade(player, start, vel);
    }

    public Vec3 position() {
        return pos;
    }

    /** Advances one tick; returns true when the grenade should pop. */
    public boolean tick(Level level) {
        age++;
        vel = vel.scale(DRAG).add(0.0, -GRAVITY, 0.0);

        // Up to three bounces per tick so fast grenades don't tunnel through corners.
        Vec3 remaining = vel;
        boolean onGround = false;
        for (int i = 0; i < 3 && remaining.lengthSqr() > 1.0E-6; i++) {
            Vec3 target = pos.add(remaining);
            BlockHitResult hit = level.clip(new ClipContext(pos, target, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, thrower));
            if (hit.getType() == HitResult.Type.MISS) {
                pos = target;
                break;
            }
            Direction face = hit.getDirection();
            Vec3 n = new Vec3(face.getStepX(), face.getStepY(), face.getStepZ());
            double travelled = hit.getLocation().subtract(pos).length();
            pos = hit.getLocation().add(n.scale(0.05));
            double fraction = Math.max(0.0, 1.0 - travelled / remaining.length());

            // Reflect off the face, losing most normal speed and some tangential speed.
            double vn = vel.dot(n);
            Vec3 normal = n.scale(vn);
            Vec3 tangent = vel.subtract(normal);
            vel = tangent.scale(0.65).subtract(normal.scale(0.35));
            remaining = vel.scale(fraction);
            if (face == Direction.UP) {
                onGround = true;
            }
        }

        if (level.isClientSide() && age % 2 == 0) {
            level.addParticle(ParticleTypes.SMOKE, pos.x, pos.y, pos.z, 0.0, 0.01, 0.0);
        }

        if (onGround && vel.lengthSqr() < 0.01) {
            restingTicks++;
        } else if (!onGround) {
            restingTicks = 0;
        }
        return restingTicks >= 6 || age >= MAX_AGE;
    }
}
