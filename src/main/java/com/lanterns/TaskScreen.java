package com.lanterns;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.minecraft.util.Util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Task terminal. One screen, eight minigames (Among Us style tasks + Green Lantern flavoured ones).
 * The server still decides whether a task counts: it checks timing, distance and that this session exists.
 */
public class TaskScreen extends Screen {
    private static final int PW = 280, PH = 200;
    private static final int GREEN = 0xFF2ECC71, MINT = 0xFF7CFFB2, RED = 0xFFE53935, YELLOW = 0xFFFDD835,
            BLUE = 0xFF1E88E5, PANEL = 0xFF10161A, CELL = 0xFF263238, OKCELL = 0xFF1B5E37, ERR = 0xFFFF6B6B, GREY = 0xFFB0B0B0;

    private final int taskIndex;
    private final String heading;
    private final Random rnd;
    private final Game game;
    private int px, py;
    private boolean resultSent = false;
    private long closeAtMs = 0;

    public TaskScreen(int taskIndex, String ui, int seed, String heading) {
        super(Text.literal(heading));
        this.taskIndex = taskIndex;
        this.heading = heading;
        this.rnd = new Random(seed);
        this.game = switch (ui) {
            case "wires" -> new Wires();
            case "keypad" -> new Keypad();
            case "shields" -> new Shields();
            case "focus" -> new Focus();
            case "spectrum" -> new Spectrum();
            case "lever" -> new Lever();
            case "timing" -> new Timing();
            default -> new Download();
        };
    }

    // ------------------------------------------------------------------ drawing helpers (local panel coordinates)

    private void rect(DrawContext c, int x1, int y1, int x2, int y2, int col) { c.fill(px + x1, py + y1, px + x2, py + y2, col); }

    private void frame(DrawContext c, int x1, int y1, int x2, int y2, int col) {
        rect(c, x1, y1, x2, y1 + 1, col); rect(c, x1, y2 - 1, x2, y2, col);
        rect(c, x1, y1, x1 + 1, y2, col); rect(c, x2 - 1, y1, x2, y2, col);
    }

    private void text(DrawContext c, String s, int x, int y, int col) { c.drawTextWithShadow(this.textRenderer, s, px + x, py + y, col); }

    private void ctext(DrawContext c, String s, int cx, int y, int col) { c.drawCenteredTextWithShadow(this.textRenderer, s, px + cx, py + y, col); }

    private void line(DrawContext c, int x1, int y1, int x2, int y2, int col, int th) {
        int steps = Math.max(Math.abs(x2 - x1), Math.abs(y2 - y1));
        if (steps == 0) return;
        for (int i = 0; i <= steps; i++) {
            int x = x1 + (x2 - x1) * i / steps, y = y1 + (y2 - y1) * i / steps;
            rect(c, x, y, x + th, y + th, col);
        }
    }

    private void disc(DrawContext c, int cx, int cy, int r, int col) {
        for (int dy = -r; dy <= r; dy++) {
            int w = (int) Math.sqrt(Math.max(0, r * r - dy * dy));
            rect(c, cx - w, cy + dy, cx + w, cy + dy + 1, col);
        }
    }

    private static boolean in(int mx, int my, int x1, int y1, int x2, int y2) { return mx >= x1 && mx <= x2 && my >= y1 && my <= y2; }

    private static int dim(int col) {
        return 0xFF000000 | ((((col >> 16) & 255) / 3) << 16) | ((((col >> 8) & 255) / 3) << 8) | ((col & 255) / 3);
    }

    private static long nowMs() { return Util.getMeasuringTimeMs(); }

    // ------------------------------------------------------------------ screen plumbing

    @Override
    public void render(DrawContext c, int mouseX, int mouseY, float delta) {
        px = (width - PW) / 2;
        py = (height - PH) / 2;
        c.fill(0, 0, width, height, 0xB0000000);
        rect(c, -3, -3, PW + 3, PH + 3, 0xFF0B3D1A);
        rect(c, 0, 0, PW, PH, PANEL);
        frame(c, 0, 0, PW, PH, GREEN);
        rect(c, 0, 0, PW, 22, 0xFF0F2A18);
        // little lantern emblem
        rect(c, 6, 5, 18, 17, GREEN); rect(c, 8, 7, 16, 15, PANEL); rect(c, 10, 9, 14, 13, MINT);
        text(c, heading.toUpperCase(), 26, 7, MINT);
        text(c, "CORPS TERMINAL", PW - 92, 7, 0xFF3C7A55);

        long now = nowMs();
        game.render(c, mouseX - px, mouseY - py, now);

        if (game.won) {
            if (!resultSent) { resultSent = true; send(true); closeAtMs = now + 800; }
            ctext(c, "TASK COMPLETE", PW / 2, PH - 16, MINT);
            if (now >= closeAtMs) close();
        } else {
            text(c, "ESC to leave", 8, PH - 14, 0xFF55655B);
        }
    }

    private void send(boolean ok) { ClientPlayNetworking.send(new TaskResultPayload(taskIndex, ok)); }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (!game.won && button == 0) game.click((int) (mx - px), (int) (my - py));
        return true;
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (!game.won) game.drag((int) (mx - px), (int) (my - py));
        return true;
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if (!game.won) game.release((int) (mx - px), (int) (my - py));
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 32 && !game.won) { game.key(); return true; } // space
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean shouldPause() { return false; }

    @Override
    public void removed() {
        if (!resultSent) { resultSent = true; send(false); } // abandoned
    }

    // ------------------------------------------------------------------ minigames

    private abstract class Game {
        boolean won = false;
        abstract void render(DrawContext c, int mx, int my, long now);
        boolean click(int mx, int my) { return false; }
        void drag(int mx, int my) {}
        void release(int mx, int my) {}
        void key() {}
    }

    /** Among Us: Fix Wiring. Join matching colours. */
    private class Wires extends Game {
        final int[] cols = {RED, BLUE, YELLOW, 0xFFD81B60};
        final int[] right = new int[4];
        final boolean[] linked = new boolean[4];
        final int[] linkTo = {-1, -1, -1, -1};
        int sel = -1;
        long errUntil = 0;

        Wires() {
            List<Integer> o = new ArrayList<>(List.of(0, 1, 2, 3));
            Collections.shuffle(o, rnd);
            for (int i = 0; i < 4; i++) right[i] = o.get(i);
        }

        void render(DrawContext c, int mx, int my, long now) {
            ctext(c, "Connect the matching colours", PW / 2, 32, GREY);
            for (int i = 0; i < 4; i++) {
                int y = 50 + i * 34;
                rect(c, 30, y, 56, y + 20, cols[i]); frame(c, 30, y, 56, y + 20, 0xFFFFFFFF);
                rect(c, PW - 56, y, PW - 30, y + 20, cols[right[i]]); frame(c, PW - 56, y, PW - 30, y + 20, 0xFFFFFFFF);
            }
            for (int i = 0; i < 4; i++) if (linked[i]) line(c, 56, 60 + i * 34, PW - 56, 60 + linkTo[i] * 34, cols[i], 3);
            if (sel >= 0) line(c, 56, 60 + sel * 34, mx, my, cols[sel], 2);
            if (now < errUntil) ctext(c, "That wire doesn't match", PW / 2, PH - 34, ERR);
        }

        boolean click(int mx, int my) {
            for (int i = 0; i < 4; i++)
                if (!linked[i] && in(mx, my, 30, 50 + i * 34, 56, 70 + i * 34)) { sel = i; return true; }
            for (int j = 0; j < 4; j++) {
                if (sel >= 0 && in(mx, my, PW - 56, 50 + j * 34, PW - 30, 70 + j * 34)) {
                    if (right[j] == sel) {
                        linked[sel] = true; linkTo[sel] = j; sel = -1;
                        boolean all = true; for (boolean b : linked) all &= b;
                        if (all) won = true;
                    } else { errUntil = nowMs() + 700; sel = -1; }
                    return true;
                }
            }
            return false;
        }
    }

    /** Among Us: Unlock Manifolds. Press 1 to 10 in order. */
    private class Keypad extends Game {
        final int[] num = new int[10];
        final boolean[] done = new boolean[10];
        int next = 1;
        long errUntil = 0;

        Keypad() {
            List<Integer> o = new ArrayList<>();
            for (int i = 1; i <= 10; i++) o.add(i);
            Collections.shuffle(o, rnd);
            for (int i = 0; i < 10; i++) num[i] = o.get(i);
        }

        int bx(int i) { return 30 + (i % 5) * 46; }
        int by(int i) { return 60 + (i / 5) * 46; }

        void render(DrawContext c, int mx, int my, long now) {
            ctext(c, "Press 1 to 10 in order", PW / 2, 34, GREY);
            for (int i = 0; i < 10; i++) {
                rect(c, bx(i), by(i), bx(i) + 40, by(i) + 38, done[i] ? OKCELL : CELL);
                frame(c, bx(i), by(i), bx(i) + 40, by(i) + 38, GREEN);
                ctext(c, String.valueOf(num[i]), bx(i) + 20, by(i) + 15, done[i] ? MINT : 0xFFFFFFFF);
            }
            if (now < errUntil) ctext(c, "Wrong order - start again", PW / 2, PH - 34, ERR);
        }

        boolean click(int mx, int my) {
            for (int i = 0; i < 10; i++) {
                if (!done[i] && in(mx, my, bx(i), by(i), bx(i) + 40, by(i) + 38)) {
                    if (num[i] == next) { done[i] = true; next++; if (next > 10) won = true; }
                    else { Arrays.fill(done, false); next = 1; errUntil = nowMs() + 700; }
                    return true;
                }
            }
            return false;
        }
    }

    /** Among Us: Prime Shields. Switch off every red panel. */
    private class Shields extends Game {
        final boolean[] lit = new boolean[9];

        Shields() {
            List<Integer> o = new ArrayList<>();
            for (int i = 0; i < 9; i++) o.add(i);
            Collections.shuffle(o, rnd);
            int n = 4 + rnd.nextInt(3);
            for (int k = 0; k < n; k++) lit[o.get(k)] = true;
        }

        int cx(int i) { return PW / 2 - 69 + (i % 3) * 46; }
        int cy(int i) { return 36 + (i / 3) * 46; }

        void render(DrawContext c, int mx, int my, long now) {
            ctext(c, "Click every red panel", PW / 2, 26, GREY);
            for (int i = 0; i < 9; i++) {
                rect(c, cx(i), cy(i), cx(i) + 40, cy(i) + 40, lit[i] ? RED : OKCELL);
                frame(c, cx(i), cy(i), cx(i) + 40, cy(i) + 40, lit[i] ? 0xFFFFFFFF : GREEN);
                if (lit[i]) ctext(c, "!", cx(i) + 20, cy(i) + 16, 0xFFFFFFFF);
            }
        }

        boolean click(int mx, int my) {
            for (int i = 0; i < 9; i++) {
                if (lit[i] && in(mx, my, cx(i), cy(i), cx(i) + 40, cy(i) + 40)) {
                    lit[i] = false;
                    boolean any = false; for (boolean b : lit) any |= b;
                    if (!any) won = true;
                    return true;
                }
            }
            return false;
        }
    }

    /** Among Us: Download Data. Start the transfer and wait. */
    private class Download extends Game {
        boolean started = false;
        long start = 0;
        final long dur = 4000 + rnd.nextInt(2000);

        void render(DrawContext c, int mx, int my, long now) {
            if (!started) {
                ctext(c, "Connect to the Corps archive", PW / 2, 56, GREY);
                rect(c, PW / 2 - 50, 84, PW / 2 + 50, 112, OKCELL);
                frame(c, PW / 2 - 50, 84, PW / 2 + 50, 112, GREEN);
                ctext(c, "START", PW / 2, 94, MINT);
                return;
            }
            double f = Math.min(1.0, (now - start) / (double) dur);
            ctext(c, "Transferring... " + (int) (f * 100) + "%", PW / 2, 56, GREY);
            rect(c, 30, 88, PW - 30, 110, CELL);
            rect(c, 32, 90, 32 + (int) ((PW - 64) * f), 108, GREEN);
            frame(c, 30, 88, PW - 30, 110, GREEN);
            for (int k = 0; k < 6; k++) { // packets
                int x = 34 + (int) (((now / 5 + k * 41) % (PW - 80)));
                rect(c, x, 126 + (k % 2) * 8, x + 8, 132 + (k % 2) * 8, MINT);
            }
            if (f >= 1.0) won = true;
        }

        boolean click(int mx, int my) {
            if (!started && in(mx, my, PW / 2 - 50, 84, PW / 2 + 50, 112)) { started = true; start = nowMs(); return true; }
            return false;
        }
    }

    /** Green Lantern: Will Focus. Keep the cursor inside the drifting ring. */
    private class Focus extends Game {
        double prog = 0;
        long last = 0;
        final double ph1 = rnd.nextDouble() * 6.28, ph2 = rnd.nextDouble() * 6.28;

        void render(DrawContext c, int mx, int my, long now) {
            ctext(c, "Focus your will: keep the cursor in the ring", PW / 2, 28, GREY);
            long dt = last == 0 ? 16 : Math.min(80, now - last);
            last = now;
            int r = 22;
            double cx = PW / 2.0 + 70 * Math.sin(now * 0.0011 + ph1);
            double cy = 34 + (PH - 80) / 2.0 + 30 * Math.sin(now * 0.0017 + ph2);
            boolean inside = (mx - cx) * (mx - cx) + (my - cy) * (my - cy) <= r * r;
            prog += inside ? dt / 5000.0 : -dt / 9000.0;
            prog = Math.max(0, Math.min(1, prog));
            rect(c, 20, 40, PW - 20, PH - 46, 0xFF0B1115);
            frame(c, 20, 40, PW - 20, PH - 46, 0xFF1F3A2A);
            disc(c, (int) cx, (int) cy, r, inside ? MINT : GREEN);
            disc(c, (int) cx, (int) cy, r - 4, PANEL);
            disc(c, (int) cx, (int) cy, 4, inside ? MINT : 0xFF2E7D4F);
            rect(c, 30, PH - 38, PW - 30, PH - 28, CELL);
            rect(c, 31, PH - 37, 31 + (int) ((PW - 62) * prog), PH - 29, GREEN);
            frame(c, 30, PH - 38, PW - 30, PH - 28, GREEN);
            if (prog >= 1.0) won = true;
        }
    }

    /** Green Lantern: Spectrum Sequence. Memorise and repeat the coloured lights. */
    private class Spectrum extends Game {
        final int[] colors = {GREEN, RED, YELLOW, BLUE};
        final String[] names = {"WILL", "RAGE", "FEAR", "HOPE"};
        final int[] seq = new int[5];
        int round = 1, phase = 0, inIdx = 0, flash = -1;
        long phaseStart = nowMs(), flashUntil = 0, errUntil = 0;

        Spectrum() { for (int i = 0; i < 5; i++) seq[i] = rnd.nextInt(4); }

        int px0(int i) { return PW / 2 - 95 + (i % 2) * 100; }
        int py0(int i) { return 44 + (i / 2) * 54; }

        void render(DrawContext c, int mx, int my, long now) {
            int lit = -1;
            if (phase == 0) {
                long el = now - phaseStart - 600;
                if (el >= 0) {
                    int idx = (int) (el / 650);
                    if (idx >= round) { phase = 1; inIdx = 0; }
                    else if (el % 650 < 450) lit = seq[idx];
                }
                ctext(c, "Watch the sequence... (" + round + "/5)", PW / 2, 28, GREY);
            } else {
                ctext(c, "Repeat it (" + inIdx + "/" + round + ")", PW / 2, 28, MINT);
            }
            if (flash >= 0 && now < flashUntil) lit = flash;
            for (int i = 0; i < 4; i++) {
                boolean on = lit == i;
                rect(c, px0(i), py0(i), px0(i) + 90, py0(i) + 46, on ? colors[i] : dim(colors[i]));
                frame(c, px0(i), py0(i), px0(i) + 90, py0(i) + 46, on ? 0xFFFFFFFF : 0xFF37474F);
                ctext(c, names[i], px0(i) + 45, py0(i) + 19, on ? 0xFFFFFFFF : 0xFF90A4AE);
            }
            if (now < errUntil) ctext(c, "Wrong light - watch again", PW / 2, PH - 34, ERR);
        }

        boolean click(int mx, int my) {
            if (phase != 1) return false;
            for (int i = 0; i < 4; i++) {
                if (in(mx, my, px0(i), py0(i), px0(i) + 90, py0(i) + 46)) {
                    flash = i; flashUntil = nowMs() + 200;
                    if (i == seq[inIdx]) {
                        inIdx++;
                        if (inIdx >= round) {
                            round++;
                            if (round > 5) won = true;
                            else { phase = 0; phaseStart = nowMs(); }
                        }
                    } else { phase = 0; phaseStart = nowMs(); errUntil = nowMs() + 900; }
                    return true;
                }
            }
            return false;
        }
    }

    /** Among Us: Empty Chute. Drag the lever all the way down. */
    private class Lever extends Game {
        static final int TOP = 36, BOT = 128;
        int hy = TOP;
        boolean dragging = false;

        void render(DrawContext c, int mx, int my, long now) {
            ctext(c, "Drag the lever all the way down", PW / 2, 26, GREY);
            rect(c, PW / 2 - 8, TOP, PW / 2 + 8, BOT + 22, CELL);
            frame(c, PW / 2 - 8, TOP, PW / 2 + 8, BOT + 22, GREEN);
            rect(c, PW / 2 - 6, TOP + 2, PW / 2 + 6, hy, OKCELL);
            rect(c, PW / 2 - 30, hy, PW / 2 + 30, hy + 22, dragging ? MINT : GREEN);
            frame(c, PW / 2 - 30, hy, PW / 2 + 30, hy + 22, 0xFFFFFFFF);
            ctext(c, "PULL", PW / 2, hy + 7, PANEL);
        }

        boolean click(int mx, int my) {
            if (in(mx, my, PW / 2 - 30, hy, PW / 2 + 30, hy + 22)) { dragging = true; return true; }
            return false;
        }

        void drag(int mx, int my) {
            if (!dragging) return;
            hy = Math.max(TOP, Math.min(BOT, my - 11));
            if (hy >= BOT) { won = true; dragging = false; }
        }

        void release(int mx, int my) {
            dragging = false;
            if (!won) hy = TOP;
        }
    }

    /** Among Us: Calibrate Distributor. Stop each moving marker inside the green zone. */
    private class Timing extends Game {
        final double[] zone = new double[3];
        final boolean[] locked = new boolean[3];
        final long[] t0 = new long[3];
        long errUntil = 0;

        Timing() {
            for (int i = 0; i < 3; i++) zone[i] = 70 + rnd.nextDouble() * (PW - 140);
            Arrays.fill(t0, nowMs());
        }

        double pos(int i, long now) {
            long period = 1500 - i * 250L;
            double f = ((now - t0[i]) % period) / (double) period;
            double p = f < 0.5 ? f * 2 : (1 - f) * 2;
            return 30 + p * (PW - 60);
        }

        int cur() { for (int i = 0; i < 3; i++) if (!locked[i]) return i; return -1; }

        void stop() {
            int i = cur();
            if (i < 0) return;
            long now = nowMs();
            if (Math.abs(pos(i, now) - zone[i]) <= 17) { locked[i] = true; if (cur() < 0) won = true; }
            else errUntil = now + 600;
        }

        void render(DrawContext c, int mx, int my, long now) {
            ctext(c, "Click or press SPACE when the marker is in the green", PW / 2, 28, GREY);
            for (int i = 0; i < 3; i++) {
                int y = 56 + i * 38;
                rect(c, 30, y, PW - 30, y + 16, CELL);
                rect(c, (int) zone[i] - 17, y, (int) zone[i] + 17, y + 16, locked[i] ? GREEN : OKCELL);
                frame(c, 30, y, PW - 30, y + 16, 0xFF37474F);
                int mxp = locked[i] ? (int) zone[i] : (int) pos(i, now);
                rect(c, mxp - 2, y - 3, mxp + 2, y + 19, locked[i] ? MINT : 0xFFFFFFFF);
            }
            if (now < errUntil) ctext(c, "Missed - try again", PW / 2, PH - 34, ERR);
        }

        boolean click(int mx, int my) { stop(); return true; }

        void key() { stop(); }
    }
}
