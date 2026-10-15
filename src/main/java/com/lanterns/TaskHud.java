package com.lanterns;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.math.MathHelper;

import java.util.ArrayList;
import java.util.List;

/** Client HUD: your own tasks, how far away they are, and which way to walk. */
public final class TaskHud {
    private TaskHud() {}

    private record Entry(String name, int x, int y, int z, String dim, boolean done) {}

    private static volatile List<Entry> entries = List.of();
    private static volatile int done, total, team;
    private static final String[] ARROWS = {"\u2191", "\u2197", "\u2192", "\u2198", "\u2193", "\u2199", "\u2190", "\u2196"};

    public static void apply(TaskListPayload p) {
        List<Entry> list = new ArrayList<>();
        if (!p.data().isEmpty()) {
            for (String row : p.data().split(";")) {
                String[] f = row.split("\\|");
                if (f.length < 6) continue;
                try { list.add(new Entry(f[0], Integer.parseInt(f[1]), Integer.parseInt(f[2]), Integer.parseInt(f[3]), f[4], f[5].equals("1"))); }
                catch (NumberFormatException ignored) {}
            }
        }
        entries = list; done = p.done(); total = p.total(); team = p.team();
    }

    /** Y just below the task panel (or the top margin when it is hidden), so other HUD pieces can stack under it. */
    public static int bottomY() {
        List<Entry> list = entries;
        return list.isEmpty() ? 6 : 6 + 16 + list.size() * 11;
    }

    public static void clear() { entries = List.of(); done = 0; total = 0; team = 0; }

    public static void render(DrawContext ctx) {
        List<Entry> list = entries;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (list.isEmpty() || mc.player == null || mc.world == null || mc.options.hudHidden) return;
        int x = 6, y = 6, w = 176, h = 16 + list.size() * 11;
        ctx.fill(x, y, x + w, y + h, 0x90000000);
        ctx.fill(x, y, x + 2, y + h, 0xFF2ECC71);
        ctx.drawTextWithShadow(mc.textRenderer, "TASKS " + done + "/" + total + "   TEAM " + team + "%", x + 6, y + 4, 0xFF7CFFB2);
        String dimNow = mc.world.getRegistryKey().getValue().toString();
        int line = y + 16;
        for (Entry e : list) {
            String where;
            if (e.done()) where = "done";
            else if (!e.dim().equals(dimNow)) where = "other dimension";
            else {
                double dx = e.x() + 0.5 - mc.player.getX(), dz = e.z() + 0.5 - mc.player.getZ();
                double targetYaw = Math.toDegrees(Math.atan2(-dx, dz));
                double rel = MathHelper.wrapDegrees(targetYaw - mc.player.getYaw());
                int bucket = ((int) Math.round(rel / 45.0) + 8) % 8;
                where = ARROWS[bucket] + " " + (int) Math.sqrt(dx * dx + dz * dz) + "m";
            }
            ctx.drawTextWithShadow(mc.textRenderer, (e.done() ? "[x] " : "[ ] ") + e.name() + "  " + where, x + 6, line, e.done() ? 0xFF8A8A8A : 0xFFFFFFFF);
            line += 11;
        }
    }
}
