package com.lanterns;

import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import org.joml.Vector3f;

import java.util.Random;

/**
 * Hallucinations for low Will. Everything here is sent to ONE player only (playSoundToPlayer and the viewer overload
 * of spawnParticles, the same way LanternFx works), so nobody else can see or hear it and it never reveals a role.
 * None of it is tied to a real player's position.
 */
public final class WillFx {
    private WillFx() {}

    private static final SoundEvent[] SOUNDS = {
            SoundEvents.ENTITY_WARDEN_HEARTBEAT,
            SoundEvents.ENTITY_ENDERMAN_AMBIENT,
            SoundEvents.ENTITY_VEX_AMBIENT,
            SoundEvents.BLOCK_WOODEN_DOOR_CLOSE,
            SoundEvents.BLOCK_GRAVEL_STEP
    };

    private static final Vector3f SHADOW = new Vector3f(0.04f, 0.04f, 0.07f);

    /** A phantom sound, and (always when broken, sometimes when fraying) a dark figure glimpsed at the edge of sight. */
    public static void hallucinate(ServerPlayerEntity p, boolean broken, Random rng) {
        SoundEvent s = SOUNDS[rng.nextInt(SOUNDS.length)];
        float vol = 0.35f + rng.nextFloat() * 0.35f;
        float pitch = 0.6f + rng.nextFloat() * 0.5f;
        p.playSoundToPlayer(s, SoundCategory.PLAYERS, vol, pitch);
        if (broken || rng.nextInt(3) == 0) shadowFigure(p, rng);
    }

    /** A person-shaped smudge of dark dust a few blocks away. It lasts about a second, like something half-seen. */
    private static void shadowFigure(ServerPlayerEntity p, Random rng) {
        double ang = rng.nextDouble() * Math.PI * 2, dist = 5.0 + rng.nextDouble() * 4.0;
        double x = p.getX() + Math.cos(ang) * dist, z = p.getZ() + Math.sin(ang) * dist, y = p.getY();
        DustParticleEffect dust = new DustParticleEffect(SHADOW, 1.7f);
        for (double h = 0.0; h <= 1.6; h += 0.4)
            p.getServerWorld().spawnParticles(p, dust, true, x, y + h, z, 1, 0.12, 0.05, 0.12, 0);
        p.getServerWorld().spawnParticles(p, ParticleTypes.SOUL, true, x, y + 1.8, z, 2, 0.05, 0.05, 0.05, 0.005);
    }
}
