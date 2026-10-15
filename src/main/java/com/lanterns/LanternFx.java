package com.lanterns;

import net.minecraft.particle.DustColorTransitionParticleEffect;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.Vec3d;
import org.joml.Vector3f;

/**
 * Every Green Lantern ring visual. Each call targets exactly ONE viewer (the ring bearer) via the
 * viewer overload of spawnParticles, so no other player's client ever receives these packets.
 * Effects are composed from tinted dust (helixes, domes, rings) rather than plain vanilla bursts.
 */
public final class LanternFx {
    private LanternFx() {}

    private static final Vector3f GREEN = new Vector3f(0.10f, 1.00f, 0.35f);
    private static final Vector3f LIME  = new Vector3f(0.65f, 1.00f, 0.55f);
    private static final Vector3f RED   = new Vector3f(1.00f, 0.15f, 0.15f);
    private static final Vector3f PINK  = new Vector3f(1.00f, 0.60f, 0.60f);
    private static final Vector3f CYAN  = new Vector3f(0.20f, 0.90f, 0.90f);
    private static final Vector3f GOLD  = new Vector3f(1.00f, 0.85f, 0.30f);
    private static final Vector3f WHITE = new Vector3f(1.00f, 1.00f, 1.00f);

    private static ParticleEffect dust(Vector3f c, float size) { return new DustParticleEffect(c, size); }
    private static ParticleEffect shift(Vector3f a, Vector3f b, float size) { return new DustColorTransitionParticleEffect(a, b, size); }

    private static void dot(ServerPlayerEntity v, ParticleEffect fx, double x, double y, double z) {
        v.getServerWorld().spawnParticles(v, fx, true, x, y, z, 1, 0, 0, 0, 0);
    }

    private static void ring(ServerPlayerEntity v, Vec3d c, double r, int n, ParticleEffect fx) {
        for (int i = 0; i < n; i++) {
            double a = 2 * Math.PI * i / n;
            dot(v, fx, c.x + Math.cos(a) * r, c.y, c.z + Math.sin(a) * r);
        }
    }

    private static void helix(ServerPlayerEntity v, Vec3d base, double height, double r, double turns, int n, double phase, ParticleEffect fx) {
        for (int i = 0; i < n; i++) {
            double t = (double) i / n, a = phase + turns * 2 * Math.PI * t;
            dot(v, fx, base.x + Math.cos(a) * r, base.y + height * t, base.z + Math.sin(a) * r);
        }
    }

    /** Evenly spread points on a sphere (fibonacci lattice). */
    private static void dome(ServerPlayerEntity v, Vec3d c, double r, int n, ParticleEffect fx) {
        double golden = Math.PI * (3 - Math.sqrt(5));
        for (int i = 0; i < n; i++) {
            double y = 1 - 2.0 * (i + 0.5) / n, rad = Math.sqrt(1 - y * y), a = golden * i;
            dot(v, fx, c.x + Math.cos(a) * rad * r, c.y + y * r, c.z + Math.sin(a) * rad * r);
        }
    }

    /** Verdict of Will: a double helix scans up the target. Green = innocent, red = Manhunter. */
    public static void verdict(ServerPlayerEntity v, ServerPlayerEntity target, boolean manhunter) {
        Vec3d b = target.getPos();
        ParticleEffect main = dust(manhunter ? RED : GREEN, 1.2f), glow = dust(manhunter ? PINK : LIME, 0.8f);
        helix(v, b, 2.2, 0.75, 2.5, 36, 0, main);
        helix(v, b, 2.2, 0.75, 2.5, 36, Math.PI, glow);
        ring(v, b.add(0, 0.05, 0), 1.1, 20, glow);
        v.getServerWorld().spawnParticles(v, ParticleTypes.END_ROD, true, b.x, b.y + 1.0, b.z, 12, 0.3, 0.6, 0.3, 0.03);
    }

    /** Residual Will: a ghostly ring on the ground with wisps rising from the crime scene. */
    public static void residual(ServerPlayerEntity v, Vec3d pos) {
        ring(v, pos.add(0, 0.1, 0), 1.4, 24, shift(CYAN, GREEN, 1.0f));
        for (int k = 0; k < 4; k++) {
            double a = k * Math.PI / 2;
            helix(v, new Vec3d(pos.x + Math.cos(a) * 0.9, pos.y, pos.z + Math.sin(a) * 0.9), 1.8, 0.2, 1.5, 10, a, shift(CYAN, LIME, 0.8f));
        }
        v.getServerWorld().spawnParticles(v, ParticleTypes.SOUL_FIRE_FLAME, true, pos.x, pos.y + 0.5, pos.z, 10, 0.4, 0.4, 0.4, 0.02);
    }

    /** Last Light cast: a green dome and rings close around the protected player. */
    public static void wardCast(ServerPlayerEntity v, ServerPlayerEntity target) {
        Vec3d b = target.getPos();
        dome(v, b.add(0, 1.0, 0), 1.15, 32, dust(GREEN, 0.9f));
        ring(v, b.add(0, 0.05, 0), 1.2, 22, dust(LIME, 1.1f));
        helix(v, b, 2.0, 0.9, 1.0, 18, 0, dust(LIME, 0.8f));
    }

    /** Faint marker the Lantern sees on a currently warded player. */
    public static void wardPulse(ServerPlayerEntity v, ServerPlayerEntity target) {
        Vec3d b = target.getPos();
        ring(v, b.add(0, 0.05, 0), 0.9, 12, dust(GREEN, 0.6f));
        ring(v, b.add(0, 2.15, 0), 0.35, 6, dust(LIME, 0.5f));
    }

    /** Silent sensor: a blocked killing blow shatters the ward in a golden-white burst (Lantern only). */
    public static void wardBreak(ServerPlayerEntity v, ServerPlayerEntity target) {
        Vec3d c = target.getPos().add(0, 1.0, 0);
        dome(v, c, 1.3, 40, dust(GOLD, 1.1f));
        dome(v, c, 0.7, 20, dust(WHITE, 0.9f));
        v.getServerWorld().spawnParticles(v, ParticleTypes.END_ROD, true, c.x, c.y, c.z, 30, 0.8, 0.9, 0.8, 0.12);
    }
}
