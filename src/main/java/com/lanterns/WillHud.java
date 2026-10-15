package com.lanterns;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;

/** Client HUD for Will: a bar under the task list plus a screen-edge vignette that closes in as Will falls. */
public final class WillHud {
    private WillHud() {}

    private static volatile int will = -1; // -1 = hidden (Manhunters, ghosts, no game)
    private static volatile int tier = 0;

    private static final String[] TIER_NAMES = {"Steady", "Shaken", "Fraying", "Broken"};
    private static final int[] TIER_COLORS = {0xFF2ECC71, 0xFFF1C40F, 0xFFE67E22, 0xFFE74C3C};

    public static void apply(WillPayload p) {
        will = p.will();
        tier = Math.max(0, Math.min(3, p.tier()));
    }

    public static void clear() { will = -1; tier = 0; }

    public static void render(DrawContext ctx) {
        int w = will, t = tier;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (w < 0 || mc.player == null || mc.world == null || mc.options.hudHidden) return;

        int sw = ctx.getScaledWindowWidth(), sh = ctx.getScaledWindowHeight();
        drawVignette(ctx, sw, sh, GameLogic.vignetteIntensity(w), t);

        int x = 6, y = TaskHud.bottomY() + 4, bw = 176, bh = 20;
        int color = TIER_COLORS[t];
        ctx.fill(x, y, x + bw, y + bh, 0x90000000);
        ctx.fill(x, y, x + 2, y + bh, color);
        ctx.drawTextWithShadow(mc.textRenderer, "WILL " + w + "%  " + TIER_NAMES[t], x + 6, y + 3, color);
        int bx = x + 6, by = y + 13, barW = bw - 12;
        ctx.fill(bx, by, bx + barW, by + 4, 0xFF222222);
        ctx.fill(bx, by, bx + Math.max(0, Math.min(barW, barW * w / 100)), by + 4, color);
    }

    /** Concentric dark frames, thickest alpha at the very edge. Pulses once Will is Fraying or worse. */
    private static void drawVignette(DrawContext ctx, int sw, int sh, double intensity, int t) {
        if (intensity <= 0.0) return;
        double pulse = t >= 2 ? 0.88 + 0.12 * Math.sin(System.currentTimeMillis() / (t == 3 ? 260.0 : 520.0)) : 1.0;
        double maxAlpha = 0xC8 * Math.min(1.0, intensity * pulse);
        int rgb = t == 3 ? 0x0C0000 : 0x040A06; // broken is tinted blood-dark, otherwise sickly green-black
        final int steps = 26;
        double depth = Math.min(sw, sh) * 0.28;
        double stepPx = depth / steps;
        for (int i = 0; i < steps; i++) {
            double f = 1.0 - (double) i / steps;
            int a = (int) (maxAlpha * f * f);
            if (a <= 0) continue;
            int color = (a << 24) | rgb;
            int in0 = (int) Math.round(i * stepPx), in1 = (int) Math.round((i + 1) * stepPx);
            if (in1 <= in0) continue;
            ctx.fill(in0, in0, sw - in0, in1, color);                 // top
            ctx.fill(in0, sh - in1, sw - in0, sh - in0, color);       // bottom
            ctx.fill(in0, in1, in1, sh - in1, color);                 // left
            ctx.fill(sw - in1, in1, sw - in0, sh - in1, color);       // right
        }
    }
}
