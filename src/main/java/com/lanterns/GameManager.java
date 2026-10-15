package com.lanterns;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.network.packet.s2c.play.EntityTrackerUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.TeamS2CPacket;
import net.minecraft.scoreboard.Team;
import net.minecraft.entity.boss.BossBar;
import net.minecraft.entity.boss.ServerBossBar;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.s2c.play.SubtitleS2CPacket;
import net.minecraft.network.packet.s2c.play.UpdateSelectedSlotS2CPacket;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.network.packet.s2c.play.TitleFadeS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleS2CPacket;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import net.minecraft.world.World;

import java.util.*;

/** Server-authoritative murder-mystery state machine. */
public class GameManager {

    public enum Phase { IDLE, PLAYING, DISCUSSION, VOTING }
    public enum Role { INNOCENT, MANHUNTER }

    static class PlayerData {
        final UUID id; final String name;
        Role role = Role.INNOCENT;
        boolean alive = true;
        GameMode prevMode = GameMode.SURVIVAL;
        RegistryKey<World> retWorld; Vec3d retPos; float retYaw, retPitch;
        int emergencyUsed = 0;
        int mode = 0;
        final List<TaskAssign> tasks = new ArrayList<>();
        // Will (innocents only; Manhunters never have it). lastSentKey: -2 never sent, -1 hidden, else will*4+tier.
        double will = GameLogic.WILL_MAX;
        GameLogic.WillTier tier = GameLogic.WillTier.STEADY;
        long nextHallucination = 0;
        int lastSentKey = -2;
        PlayerData(UUID id, String name) { this.id = id; this.name = name; }
    }

    static class Body {
        UUID victim; String victimName; RegistryKey<World> world; Vec3d pos;
        long diedAt; boolean duringBlackout; UUID killer; UUID standId;
        List<String> pool = new ArrayList<>(); String killerSide = "north";
        Set<Integer> cluesGiven = new HashSet<>();
        boolean shifted; // killer was shapeshifted at the time
    }

    static class Shift { final UUID target; final String targetName; final long endsAt; Shift(UUID t, String n, long e) { target = t; targetName = n; endsAt = e; } }

    static class Session { final int task; final String ui; final long openedAt; Session(int t, String u, long o) { task = t; ui = u; openedAt = o; } }

    static class TaskAssign { final int station; boolean done; TaskAssign(int s) { station = s; } }
    static class Hold { final int task; final long started; Hold(int t, long s) { task = t; started = s; } }
    static class Pending { final int task, answer; final long expires; Pending(int t, int a, long e) { task = t; answer = a; expires = e; } }

    /** Ring ability state lives with the RING, not the bearer, so transfers preserve everything. */
    static class RingState {
        boolean verdictUsed = false;        // once per entire game
        boolean selfShieldUsed = false;     // once per entire game
        boolean shieldUsedThisRound = false;// refreshed when a meeting ends
        long residualReadyAt = 0;
        UUID shieldTarget = null; long shieldEndsAt = 0;
        int guardianCharge = 0;  // task-charged, max 1 banked
        int guardianLock = 0;    // meetings that must END before it can trigger again
    }

    private static final String[] LANTERN_MODES = {"Verdict of Will", "Residual Will", "Last Light"};
    private static final String[] CORE_MODES = {"System Blackout", "Shapeshift"};

    private final MinecraftServer server;
    private final Random rng = new Random();

    private Phase phase = Phase.IDLE;
    private final Map<UUID, PlayerData> players = new LinkedHashMap<>();
    private final Map<UUID, Long> killReady = new HashMap<>();
    private final Map<UUID, Long> blackoutReady = new HashMap<>();
    private final List<Body> bodies = new ArrayList<>();
    private RingState ring = new RingState();
    private UUID lantern = null;
    private boolean ringGranted = false;
    private long now = 0, blackoutEndsAt = 0, discussionEndsAt, votingEndsAt, meetingStartedAt,
            lastMeetingEndedAt, gameStartedAt;
    private final Map<UUID, UUID> votes = new HashMap<>();
    private int meetingsHeld = 0;
    // --- smoothness helpers
    private final Map<UUID, Long> barQuietUntil = new HashMap<>(); // keeps ability feedback readable
    private final Set<UUID> killPing = new HashSet<>(), blackPing = new HashSet<>();
    private ServerBossBar bossBar;
    // --- Manhunter ally outline (packet-only, per viewer)
    private final Map<UUID, Team> outlineTeams = new HashMap<>();
    private final Set<UUID> outlined = new HashSet<>();
    // --- tasks
    private List<Config.TaskStation> stations = new ArrayList<>();
    private final Map<UUID, Hold> holds = new HashMap<>();
    private final Map<UUID, Pending> pendingQuiz = new HashMap<>();
    private int thresholdsAwarded = 0;
    private final Map<UUID, Session> sessions = new HashMap<>();
    // --- shapeshift
    private final Map<UUID, Shift> shifts = new HashMap<>();
    private final Map<UUID, Long> shiftReady = new HashMap<>();
    /** Admin-forced roles ("manhunter" / "innocent" / "lantern"); persist until cleared or server restart. */
    private final Map<UUID, String> forced = new HashMap<>();

    public GameManager(MinecraftServer server) { this.server = server; }

    // ------------------------------------------------------------------ helpers

    public Phase phase() { return phase; }
    public boolean isRunning() { return phase != Phase.IDLE; }

    private static MutableText prefix() { return Text.literal("[Lanterns] ").formatted(Formatting.GREEN, Formatting.BOLD); }
    private static Text tag(String s, Formatting f) { return prefix().append(Text.literal(s).formatted(f)); }
    private void tell(ServerPlayerEntity p, String s, Formatting f) { p.sendMessage(tag(s, f), false); }
    private void bar(ServerPlayerEntity p, String s, Formatting f) {
        barQuietUntil.put(p.getUuid(), now + 40); // status line pauses for 2s so feedback is readable
        p.sendMessage(Text.literal(s).formatted(f), true);
    }
    private void broadcast(String s, Formatting f) { server.getPlayerManager().broadcast(tag(s, f), false); }

    private void title(ServerPlayerEntity p, String t, String sub, Formatting f) {
        p.networkHandler.sendPacket(new TitleFadeS2CPacket(10, 100, 20));
        p.networkHandler.sendPacket(new SubtitleS2CPacket(Text.literal(sub).formatted(Formatting.GRAY)));
        p.networkHandler.sendPacket(new TitleS2CPacket(Text.literal(t).formatted(f, Formatting.BOLD)));
    }

    private ServerPlayerEntity online(UUID id) { return server.getPlayerManager().getPlayer(id); }

    private boolean isAlive(UUID id) { PlayerData d = players.get(id); return d != null && d.alive; }
    private boolean isManhunter(UUID id) { PlayerData d = players.get(id); return d != null && d.role == Role.MANHUNTER; }

    private List<ServerPlayerEntity> livingOnline() {
        List<ServerPlayerEntity> out = new ArrayList<>();
        for (PlayerData d : players.values()) {
            if (!d.alive) continue;
            ServerPlayerEntity p = online(d.id);
            if (p != null) out.add(p);
        }
        return out;
    }

    private Set<UUID> livingIds() {
        Set<UUID> s = new LinkedHashSet<>();
        for (PlayerData d : players.values()) if (d.alive) s.add(d.id);
        return s;
    }

    private Set<UUID> manhunterIds() {
        Set<UUID> s = new HashSet<>();
        for (PlayerData d : players.values()) if (d.role == Role.MANHUNTER) s.add(d.id);
        return s;
    }

    private void sound(ServerPlayerEntity p, SoundEvent e, float pitch) {
        p.playSoundToPlayer(e, SoundCategory.PLAYERS, 1.0f, pitch);
    }

    private void soundAll(SoundEvent e, float pitch) {
        for (PlayerData d : players.values()) {
            ServerPlayerEntity p = online(d.id);
            if (p != null) sound(p, e, pitch);
        }
    }

    private boolean hasRing(ServerPlayerEntity p) {
        for (int i = 0; i < p.getInventory().size(); i++)
            if (p.getInventory().getStack(i).isOf(ModItems.POWER_RING)) return true;
        return false;
    }

    private boolean hasCore(ServerPlayerEntity p) {
        for (int i = 0; i < p.getInventory().size(); i++)
            if (p.getInventory().getStack(i).isOf(ModItems.BLACKOUT_CORE)) return true;
        return false;
    }

    private void removeCore(ServerPlayerEntity p) {
        for (int i = 0; i < p.getInventory().size(); i++)
            if (p.getInventory().getStack(i).isOf(ModItems.BLACKOUT_CORE)) p.getInventory().setStack(i, ItemStack.EMPTY);
    }

    private void giveCore(ServerPlayerEntity p) {
        if (hasCore(p)) return;
        putInLastSlot(p, new ItemStack(ModItems.BLACKOUT_CORE));
    }

    /** Always hotbar slot 9, and never leave the player holding it, so nobody spots the item on spawn. */
    private void putInLastSlot(ServerPlayerEntity p, ItemStack stack) {
        PlayerInventory inv = p.getInventory();
        ItemStack displaced = inv.getStack(8);
        inv.setStack(8, stack);
        if (!displaced.isEmpty() && !inv.insertStack(displaced)) p.dropItem(displaced, false);
        if (inv.selectedSlot == 8) {
            inv.selectedSlot = 0;
            p.networkHandler.sendPacket(new UpdateSelectedSlotS2CPacket(0));
        }
    }

    /** Right-click with the Blackout Core. */
    public void onCoreUse(ServerPlayerEntity p) {
        PlayerData d = players.get(p.getUuid());
        boolean mh = d != null && d.alive && d.role == Role.MANHUNTER;
        if (mh && phase == Phase.PLAYING && p.isSneaking()) { // sneak + right-click switches mode
            d.mode = (d.mode + 1) % CORE_MODES.length;
            bar(p, "Core mode: " + CORE_MODES[d.mode], Formatting.DARK_PURPLE);
            sound(p, SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, 1.6f);
            return;
        }
        String err = (mh && d.mode % CORE_MODES.length == 1) ? shapeshift(p) : blackout(p);
        if (err != null) bar(p, err, Formatting.RED);
    }

    private void giveRing(ServerPlayerEntity p) {
        if (hasRing(p)) return;
        putInLastSlot(p, new ItemStack(ModItems.POWER_RING));
    }

    private void removeRings(ServerPlayerEntity p) {
        for (int i = 0; i < p.getInventory().size(); i++)
            if (p.getInventory().getStack(i).isOf(ModItems.POWER_RING)) p.getInventory().setStack(i, ItemStack.EMPTY);
    }

    private void teleportTo(ServerPlayerEntity p, RegistryKey<World> key, Vec3d pos, float yaw, float pitch) {
        ServerWorld w = server.getWorld(key);
        if (w != null) p.teleport(w, pos.x, pos.y, pos.z, yaw, pitch);
    }

    /** Look-direction targeting among living, eligible players. */
    private ServerPlayerEntity lookTarget(ServerPlayerEntity src, double range, java.util.function.Predicate<ServerPlayerEntity> ok) {
        Vec3d eye = src.getEyePos();
        Vec3d look = src.getRotationVec(1.0f).normalize();
        ServerPlayerEntity best = null;
        double bestDot = 0.93;
        for (ServerPlayerEntity o : src.getServerWorld().getPlayers()) {
            if (o == src || !isAlive(o.getUuid()) || !ok.test(o)) continue;
            Vec3d center = o.getPos().add(0, o.getHeight() * 0.5, 0);
            Vec3d dir = center.subtract(eye);
            double dist = dir.length();
            if (dist > range || dist < 0.01) continue;
            double dot = dir.normalize().dotProduct(look);
            if (dot > bestDot && src.canSee(o)) { bestDot = dot; best = o; }
        }
        return best;
    }

    /** Shows the caller their own role (title + chat). Private to that player. */
    public void showRole(ServerPlayerEntity p) {
        PlayerData d = players.get(p.getUuid());
        if (phase == Phase.IDLE || d == null) { tell(p, "You are not in a game.", Formatting.GRAY); return; }
        if (!d.alive) { tell(p, "You are dead. You are a ghost.", Formatting.GRAY); return; }
        if (d.role == Role.MANHUNTER) {
            List<String> allies = new ArrayList<>();
            for (PlayerData o : players.values()) if (o.role == Role.MANHUNTER && !o.id.equals(d.id)) allies.add(o.name);
            title(p, "YOU ARE A MANHUNTER", "Eliminate everyone. Stay hidden.", Formatting.DARK_RED);
            tell(p, "YOU ARE A MANHUNTER. " + (allies.isEmpty() ? "You are working alone." : "Fellow Manhunter(s): " + String.join(", ", allies)), Formatting.RED);
            tell(p, "Punch a player to kill them (cooldown applies). Right-click your Blackout Core for System Blackout.", Formatting.GRAY);
            if (!stations.isEmpty()) tell(p, "You have FAKE tasks: they look real but add nothing to team progress. /lantern tasks lists them.", Formatting.GRAY);
        } else {
            title(p, "YOU ARE INNOCENT", "Find the Manhunter before they find you.", Formatting.GREEN);
            tell(p, "YOU ARE INNOCENT. Report bodies, discuss, and vote out the Manhunter(s).", Formatting.GREEN);
            if (!stations.isEmpty()) tell(p, "Tasks: right-click the sparkling task blocks. /lantern tasks lists yours. Team tasks charge the Lantern's Guardian Light.", Formatting.AQUA);
            if (willActive()) tell(p, "WILL: yours drains over time. Finish tasks, stay near other people and get through meetings to restore it. Low Will distorts your senses.", Formatting.AQUA);
            if (d.id.equals(lantern)) tell(p, "You also bear the Green Lantern ring.", Formatting.DARK_GREEN);
        }
    }

    // ------------------------------------------------------------------ start / stop

    /** @return error message or null on success */
    public String start(boolean force) {
        if (phase != Phase.IDLE) return "A game is already running.";
        Config c = Config.get();
        if (!c.meetingPoint.set) return "Set a meeting point first with /lantern setmeetingpoint.";
        if (server.getWorld(c.meetingPoint.key()) == null) return "The saved meeting dimension no longer exists.";
        List<ServerPlayerEntity> list = new ArrayList<>(server.getPlayerManager().getPlayerList());
        int min = force ? 3 : c.minPlayers;
        if (list.size() < min) return "Need at least " + min + " players (have " + list.size() + ").";

        players.clear(); bodies.clear(); votes.clear(); killReady.clear(); blackoutReady.clear();
        barQuietUntil.clear(); killPing.clear(); blackPing.clear(); removeBossBar();
        holds.clear(); pendingQuiz.clear(); stations = new ArrayList<>(); thresholdsAwarded = 0;
        shifts.clear(); shiftReady.clear(); sessions.clear();
        clearOutlines();
        ring = new RingState(); lantern = null; ringGranted = false; meetingsHeld = 0; blackoutEndsAt = 0;
        now = 0; gameStartedAt = 0; lastMeetingEndedAt = 0;

        List<UUID> ids = new ArrayList<>();
        for (ServerPlayerEntity p : list) ids.add(p.getUuid());
        int wanted = c.autoScaleManhunters ? GameLogic.scaledManhunterCount(ids.size()) : c.manhunters;
        int mCount = GameLogic.manhunterCount(ids.size(), wanted);
        Set<UUID> forcedYes = new HashSet<>(), forcedNo = new HashSet<>();
        for (Map.Entry<UUID, String> e : forced.entrySet()) {
            if (e.getValue().equals("manhunter")) forcedYes.add(e.getKey()); else forcedNo.add(e.getKey());
        }
        Set<UUID> mh = GameLogic.assignManhunters(ids, mCount, forcedYes, forcedNo, rng);

        for (ServerPlayerEntity p : list) {
            PlayerData d = new PlayerData(p.getUuid(), p.getName().getString());
            d.prevMode = p.interactionManager.getGameMode();
            d.role = mh.contains(p.getUuid()) ? Role.MANHUNTER : Role.INNOCENT;
            if (d.role == Role.MANHUNTER) d.lastSentKey = -1; // Manhunters have no Will and are never sent any
            players.put(d.id, d);
            p.changeGameMode(GameMode.ADVENTURE);
            p.clearStatusEffects();
            p.setHealth(p.getMaxHealth());
            p.getHungerManager().setFoodLevel(20);
            removeRings(p); removeCore(p); // nobody has a ring until the first meeting ends
            if (d.role == Role.MANHUNTER) {
                giveCore(p);
                killReady.put(d.id, (long) c.initialKillCooldownSec * 20);
                blackoutReady.put(d.id, (long) c.initialKillCooldownSec * 20);
                shiftReady.put(d.id, (long) c.initialKillCooldownSec * 20);
            }
        }
        assignTasks();
        phase = Phase.PLAYING;
        setupAllyOutline();

        for (PlayerData d : players.values()) {
            ServerPlayerEntity p = online(d.id);
            if (p == null) continue;
            showRole(p);
            sound(p, SoundEvents.ENTITY_WARDEN_HEARTBEAT, 0.8f);
        }
        refreshTaskHud();
        for (PlayerData d : players.values()) { ServerPlayerEntity p = online(d.id); if (p != null && hasWill(d)) sendWill(p, d); }
        broadcast("The game has begun. " + mCount + " Manhunter(s) walk among you.", Formatting.YELLOW);
        return null;
    }

    public void stop(String reason) { if (phase != Phase.IDLE) endGame(GameLogic.Winner.NONE, reason); }

    private void restorePlayer(ServerPlayerEntity p, PlayerData d) {
        removeRings(p);
        removeCore(p);
        p.removeStatusEffect(StatusEffects.DARKNESS);
        p.getHungerManager().setFoodLevel(20); // undo the Will sprint lock
        if (d != null) p.changeGameMode(d.prevMode);
    }

    private void endGame(GameLogic.Winner w, String reason) {
        removeBossBar();
        clearOutlines();
        endAllShifts(false);
        clearTaskHud();
        clearWillHud();
        sessions.clear();
        MutableText header = Text.literal("");
        switch (w) {
            case INNOCENTS -> broadcast("INNOCENTS WIN! " + reason, Formatting.GREEN);
            case MANHUNTERS -> broadcast("MANHUNTERS WIN! " + reason, Formatting.DARK_RED);
            default -> broadcast("Game ended. " + reason, Formatting.YELLOW);
        }
        // Final role reveal (only at game end).
        StringBuilder mhs = new StringBuilder();
        for (PlayerData d : players.values()) if (d.role == Role.MANHUNTER) { if (mhs.length() > 0) mhs.append(", "); mhs.append(d.name); }
        broadcast("Manhunters were: " + mhs, Formatting.RED);
        if (lantern != null && players.containsKey(lantern))
            broadcast("The Green Lantern at the end: " + players.get(lantern).name, Formatting.GREEN);

        for (PlayerData d : players.values()) {
            ServerPlayerEntity p = online(d.id);
            if (p != null) { restorePlayer(p, d); title(p, w == GameLogic.Winner.NONE ? "GAME OVER" : (w == GameLogic.Winner.INNOCENTS ? "INNOCENTS WIN" : "MANHUNTERS WIN"), "", Formatting.GOLD); }
        }
        clearBodies();
        phase = Phase.IDLE;
        players.clear(); votes.clear(); killReady.clear(); blackoutReady.clear();
        holds.clear(); pendingQuiz.clear(); stations = new ArrayList<>();
        lantern = null; blackoutEndsAt = 0;
    }

    /** Called on server shutdown. */
    public void shutdown() {
        if (phase == Phase.IDLE) return;
        for (PlayerData d : players.values()) { ServerPlayerEntity p = online(d.id); if (p != null) restorePlayer(p, d); }
        clearBodies();
        removeBossBar();
        clearOutlines();
        endAllShifts(false);
        phase = Phase.IDLE;
    }

    // ------------------------------------------------------------------ win checks

    private boolean checkWinAndEnd() {
        int m = 0, o = 0;
        for (PlayerData d : players.values()) if (d.alive) { if (d.role == Role.MANHUNTER) m++; else o++; }
        GameLogic.Winner w = GameLogic.checkWin(m, o);
        if (w == GameLogic.Winner.INNOCENTS) { endGame(w, "All Manhunters have been eliminated."); return true; }
        if (w == GameLogic.Winner.MANHUNTERS) { endGame(w, "The Manhunters now equal or outnumber everyone else."); return true; }
        if (Config.get().tasksCompleteWins && !stations.isEmpty()) {
            int[] pr = teamProgress();
            if (pr[1] > 0 && pr[0] >= pr[1]) { endGame(GameLogic.Winner.INNOCENTS, "The team completed every task."); return true; }
        }
        return false;
    }

    // ------------------------------------------------------------------ tick

    public void tick() {
        if (phase == Phase.IDLE) return;
        now++;
        if (now % 5 == 0 && !outlineTeams.isEmpty()) refreshOutlines();
        if (now % 40 == 0 && !stations.isEmpty()) refreshTaskHud();

        if (phase == Phase.PLAYING) {
            if (ring.shieldTarget != null) {
                if (now >= ring.shieldEndsAt || !isAlive(ring.shieldTarget)) ring.shieldTarget = null;
                else if (now % 10 == 0 && lantern != null) {
                    ServerPlayerEntity lp = online(lantern), wt = online(ring.shieldTarget);
                    if (lp != null && wt != null && lp.getWorld() == wt.getWorld()) LanternFx.wardPulse(lp, wt);
                }
            }
            if (now % 5 == 0) tickShifts();
            if (now % 20 == 0) { showStationParticles(); tickWill(); }
        } else {
            if (now % 5 == 0) { enforceMeetingPosition(); updateBossBar(); }
            if (phase == Phase.DISCUSSION && now >= discussionEndsAt) beginVoting();
            else if (phase == Phase.VOTING) {
                boolean all = true;
                for (UUID id : livingIds()) if (!votes.containsKey(id)) { all = false; break; }
                if (all && votingEndsAt - now > 60) {
                    votingEndsAt = now + 60; // short beat before results instead of an instant cut
                    broadcast("All votes are in. Results in 3 seconds...", Formatting.YELLOW);
                }
                if (now >= votingEndsAt) { resolveVoting(); return; }
            }
        }
        if (phase == Phase.IDLE) return;

        if (now % 10 == 0) {
            for (PlayerData d : players.values()) {
                ServerPlayerEntity p = online(d.id);
                if (p == null || !d.alive) continue;
                if (now >= barQuietUntil.getOrDefault(d.id, 0L))
                    p.sendMessage(Text.literal(statusLine(d)).formatted(d.role == Role.MANHUNTER ? Formatting.RED : Formatting.GREEN), true);
                if (phase == Phase.PLAYING && d.role == Role.MANHUNTER) {
                    if (now >= killReady.getOrDefault(d.id, 0L)) {
                        if (killPing.add(d.id)) { sound(p, SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 0.7f); bar(p, "Kill ready", Formatting.RED); }
                    } else killPing.remove(d.id);
                    if (now >= blackoutReady.getOrDefault(d.id, 0L)) {
                        if (blackPing.add(d.id)) { sound(p, SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, 0.7f); bar(p, "Blackout ready", Formatting.DARK_PURPLE); }
                    } else blackPing.remove(d.id);
                }
            }
        }
        if (now % 20 == 0) {
            for (PlayerData d : players.values()) {
                ServerPlayerEntity p = online(d.id);
                if (p == null) continue;
                if (d.alive && d.id.equals(lantern)) { if (!hasRing(p)) giveRing(p); }
                else if (hasRing(p)) removeRings(p); // only the current Lantern may hold a ring
                if (d.alive && d.role == Role.MANHUNTER) { if (!hasCore(p)) giveCore(p); }
                else if (hasCore(p)) removeCore(p);   // only living Manhunters hold the core
                if (d.alive) p.getHungerManager().setFoodLevel(foodFor(d));
            }
        }
    }

    private void enforceMeetingPosition() {
        Config.MeetingPoint mp = Config.get().meetingPoint;
        Vec3d pos = new Vec3d(mp.x, mp.y, mp.z);
        for (ServerPlayerEntity p : livingOnline()) {
            boolean sameWorld = p.getWorld().getRegistryKey().equals(mp.key());
            if (!sameWorld || p.getPos().squaredDistanceTo(pos) > Config.get().meetingFreeRadius * Config.get().meetingFreeRadius) teleportTo(p, mp.key(), pos, p.getYaw(), p.getPitch());
        }
    }

    private String statusLine(PlayerData d) {
        String base = d.role == Role.MANHUNTER ? "MANHUNTER" : "INNOCENT";
        if (phase != Phase.PLAYING) return "Role: " + base + " | " + (phase == Phase.VOTING ? "Voting" : "Meeting");
        StringBuilder sb = new StringBuilder("Role: ").append(base);
        if (d.role == Role.MANHUNTER) {
            sb.append(" | Kill ").append(cd(killReady.getOrDefault(d.id, 0L)))
              .append(" | Blackout ").append(cd(blackoutReady.getOrDefault(d.id, 0L)))
              .append(" | Shift ").append(shiftLabel(d))
              .append(" | Core: ").append(CORE_MODES[d.mode % CORE_MODES.length]);
        } else if (d.id.equals(lantern)) {
            sb.append(" (Lantern) | ").append(LANTERN_MODES[d.mode % LANTERN_MODES.length])
              .append(" | Verdict ").append(ring.verdictUsed ? "spent" : "ready")
              .append(" | Residual ").append(cd(ring.residualReadyAt))
              .append(" | Shield ").append(ring.shieldUsedThisRound ? "spent" : "ready").append(ring.selfShieldUsed ? "" : "*")
              .append(" | Guardian ").append(guardianLabel());
        }
        if (!stations.isEmpty()) {
            int mine = 0; for (TaskAssign t : d.tasks) if (t.done) mine++;
            sb.append(" | Tasks ").append(mine).append("/").append(d.tasks.size()).append(" Team ").append(teamPercent()).append("%");
        }
        return sb.toString();
    }

    private String guardianLabel() {
        if (ring.guardianLock > 0) return "locked(" + ring.guardianLock + ")";
        return ring.guardianCharge > 0 ? "READY" : "charging";
    }

    private String cd(long readyAt) {
        long s = GameLogic.remainingSeconds(readyAt, now);
        return s == 0 ? "READY" : s + "s";
    }

    private String[] modesFor(PlayerData d) {
        return d.id.equals(lantern) ? LANTERN_MODES : null;
    }

    // ------------------------------------------------------------------ ring input

    public void onRingUse(ServerPlayerEntity p) {
        PlayerData d = players.get(p.getUuid());
        if (phase == Phase.IDLE || d == null || !d.alive) { bar(p, "The ring is inert.", Formatting.GRAY); return; }
        if (phase != Phase.PLAYING) { bar(p, "The ring is silent during meetings.", Formatting.GRAY); return; }
        String[] modes = modesFor(d);
        if (modes == null) { bar(p, "The ring is dormant.", Formatting.GRAY); return; }
        if (p.isSneaking()) {
            d.mode = (d.mode + 1) % modes.length;
            bar(p, "Mode: " + modes[d.mode], Formatting.GREEN);
            sound(p, SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, 1.6f);
            return;
        }
        String m = modes[d.mode % modes.length];
        switch (m) {
            case "Verdict of Will" -> verdict(p);
            case "Residual Will" -> residual(p);
            case "Last Light" -> shield(p);
            default -> {}
        }
    }

    // ------------------------------------------------------------------ Manhunter actions

    /** Called when a player punches another player. Returns true to cancel the hit (no PvP ever). */
    public boolean onAttack(ServerPlayerEntity attacker, ServerPlayerEntity target) {
        if (phase == Phase.IDLE) return false;
        PlayerData ad = players.get(attacker.getUuid());
        if (ad == null) return false;
        if (ad.alive && ad.role == Role.MANHUNTER && phase == Phase.PLAYING) tryKill(attacker, target);
        return true;
    }

    private void tryKill(ServerPlayerEntity killer, ServerPlayerEntity target) {
        Config c = Config.get();
        long ready = killReady.getOrDefault(killer.getUuid(), 0L);
        if (now < ready) { bar(killer, "Kill ready in " + GameLogic.remainingSeconds(ready, now) + "s", Formatting.RED); return; }
        PlayerData td = players.get(target.getUuid());
        if (td == null || !td.alive) return;
        if (td.role == Role.MANHUNTER) { bar(killer, "That is your ally.", Formatting.GRAY); return; }
        if (killer.squaredDistanceTo(target) > c.killRange * c.killRange) return;

        killReady.put(killer.getUuid(), now + (long) c.killCooldownSec * 20);

        boolean shielded = ring.shieldTarget != null && ring.shieldTarget.equals(target.getUuid()) && now < ring.shieldEndsAt;
        boolean guardian = !shielded && target.getUuid().equals(lantern) && GameLogic.guardianReady(ring.guardianCharge, ring.guardianLock);
        if (shielded || guardian) {
            if (shielded) ring.shieldTarget = null; // shield consumed
            else {
                ring.guardianCharge = 0;
                ring.guardianLock = GameLogic.GUARDIAN_LOCK_MEETINGS;
            }
            // SILENT SENSOR: only the Lantern bearer is told, privately. The protected player and the
            // Manhunter get no message, sound, flash or particle; the punch just does nothing.
            ServerPlayerEntity lp = lantern == null ? null : online(lantern);
            if (lp != null && isAlive(lantern)) {
                LanternFx.wardBreak(lp, target);
                sound(lp, SoundEvents.ITEM_TOTEM_USE, 1.4f);
                bar(lp, "Your light turned aside a strike on " + (target.getUuid().equals(lantern) ? "you" : target.getName().getString())
                        + (guardian ? " (Guardian Light, locked for 2 meetings)." : "."), Formatting.GREEN);
            }
            return;
        }
        eliminateByKill(target, killer);
    }

    private void eliminateByKill(ServerPlayerEntity victim, ServerPlayerEntity killer) {
        PlayerData vd = players.get(victim.getUuid());
        if (vd == null || !vd.alive) return; // simultaneous-kill guard
        spawnBody(victim, killer);
        vd.alive = false;
        hideWill(victim, vd);
        votes.remove(victim.getUuid());
        victim.changeGameMode(GameMode.SPECTATOR);
        title(victim, "YOU WERE KILLED", "You are now a ghost. Do not reveal what you know.", Formatting.DARK_RED);
        if (Config.get().revealRoleOnDeath) broadcast(vd.name + " was " + vd.role + ".", Formatting.GRAY);

        if (victim.getUuid().equals(lantern)) {
            endGame(GameLogic.Winner.MANHUNTERS, "The Green Lantern has fallen.");
            return;
        }
        checkWinAndEnd();
    }

    private void spawnBody(ServerPlayerEntity victim, ServerPlayerEntity killer) {
        Body b = new Body();
        b.victim = victim.getUuid(); b.victimName = victim.getName().getString();
        b.world = victim.getWorld().getRegistryKey(); b.pos = victim.getPos();
        b.diedAt = now; b.duringBlackout = now < blackoutEndsAt; b.killer = killer.getUuid(); b.shifted = shifts.containsKey(killer.getUuid());
        b.killerSide = Direction.getFacing(killer.getX() - victim.getX(), 0, killer.getZ() - victim.getZ()).asString();

        // Pool of suspects: the killer plus up to two other living players (truthful but inconclusive).
        List<String> others = new ArrayList<>();
        for (PlayerData d : players.values())
            if (d.alive && !d.id.equals(victim.getUuid()) && !d.id.equals(killer.getUuid())) others.add(d.name);
        Collections.shuffle(others, rng);
        b.pool.add(players.get(killer.getUuid()).name);
        for (int i = 0; i < Math.min(2, others.size()); i++) b.pool.add(others.get(i));
        Collections.shuffle(b.pool, rng);

        ServerWorld w = victim.getServerWorld();
        ArmorStandEntity s = new ArmorStandEntity(w, victim.getX(), victim.getY(), victim.getZ());
        s.setYaw(victim.getYaw());
        s.setInvulnerable(true);
        s.equipStack(EquipmentSlot.HEAD, new ItemStack(Items.PLAYER_HEAD));
        s.equipStack(EquipmentSlot.CHEST, new ItemStack(Items.LEATHER_CHESTPLATE));
        s.setCustomName(Text.literal("Unidentified Body").formatted(Formatting.DARK_RED));
        s.setCustomNameVisible(true);
        s.addCommandTag("lanterns_body");
        w.spawnEntity(s);
        b.standId = s.getUuid();
        bodies.add(b);
        w.playSound(null, victim.getX(), victim.getY(), victim.getZ(), SoundEvents.ENTITY_PLAYER_DEATH, SoundCategory.PLAYERS, 1.0f, 0.8f);
        w.spawnParticles(ParticleTypes.SOUL, victim.getX(), victim.getY() + 1, victim.getZ(), 25, 0.3, 0.6, 0.3, 0.03);
        w.spawnParticles(ParticleTypes.SMOKE, victim.getX(), victim.getY() + 0.5, victim.getZ(), 20, 0.3, 0.3, 0.3, 0.02);
    }

    private void clearBodies() {
        for (Body b : bodies) {
            ServerWorld w = server.getWorld(b.world);
            if (w == null || b.standId == null) continue;
            Entity e = w.getEntity(b.standId);
            if (e != null) e.discard();
        }
        bodies.clear();
    }

    /** Blackout Core use. Returns an error string, or null on success. */
    public String blackout(ServerPlayerEntity p) {
        PlayerData d = players.get(p.getUuid());
        if (phase != Phase.PLAYING || d == null || !d.alive || d.role != Role.MANHUNTER) return "Nothing happens.";
        Config c = Config.get();
        long ready = blackoutReady.getOrDefault(p.getUuid(), 0L);
        if (now < ready) return "Blackout ready in " + GameLogic.remainingSeconds(ready, now) + "s";
        if (now < blackoutEndsAt) return "A blackout is already active.";
        blackoutReady.put(p.getUuid(), now + (long) c.blackoutCooldownSec * 20);
        blackoutEndsAt = now + (long) c.blackoutDurationSec * 20;
        // Everyone (including the activator) is affected, so it never reveals who triggered it.
        for (ServerPlayerEntity o : livingOnline())
            o.addStatusEffect(new StatusEffectInstance(StatusEffects.DARKNESS, c.blackoutDurationSec * 20, 0, false, false, false));
        soundAll(SoundEvents.BLOCK_BEACON_DEACTIVATE, 0.6f);
        broadcast("SYSTEM BLACKOUT! The lights fail...", Formatting.DARK_GRAY);
        return null;
    }

    private void endBlackout() {
        blackoutEndsAt = 0;
        for (ServerPlayerEntity o : livingOnline()) o.removeStatusEffect(StatusEffects.DARKNESS);
    }

    // ------------------------------------------------------------------ Will (innocents only)

    /**
     * The Will METER is its own system: a personal stress bar for innocents. It is completely separate from the ring's
     * charges (Verdict, Residual Will, Last Light, Guardian Light), which it never reads or changes.
     * Will needs task stations (it is restored by tasks), so with no stations the game plays as before.
     */
    private boolean willActive() { return Config.get().willEnabled && !stations.isEmpty(); }

    /** Manhunters, ghosts and the unassigned never have Will. */
    private boolean hasWill(PlayerData d) {
        return d != null && GameLogic.hasWill(d.alive, d.role == Role.MANHUNTER, Config.get().willEnabled, !stations.isEmpty());
    }

    /** 6 = cannot sprint (Minecraft needs more than 6 food to sprint). Hunger never matters otherwise. */
    private int foodFor(PlayerData d) {
        boolean lock = Config.get().willSprintLock && phase == Phase.PLAYING && hasWill(d)
                && GameLogic.willTier(d.will) == GameLogic.WillTier.BROKEN;
        return lock ? 6 : 20;
    }

    private void sendWill(ServerPlayerEntity p, PlayerData d) {
        int key = (int) Math.round(d.will) * 4 + d.tier.ordinal();
        if (key == d.lastSentKey) return;
        if (!ServerPlayNetworking.canSend(p, WillPayload.ID)) return;
        d.lastSentKey = key;
        ServerPlayNetworking.send(p, new WillPayload((int) Math.round(d.will), d.tier.ordinal()));
    }

    private void hideWill(ServerPlayerEntity p, PlayerData d) {
        if (d.lastSentKey == -1) return; // already hidden (always true for Manhunters)
        d.lastSentKey = -1;
        if (ServerPlayNetworking.canSend(p, WillPayload.ID)) ServerPlayNetworking.send(p, new WillPayload(-1, 0));
    }

    private void clearWillHud() {
        for (PlayerData d : players.values()) {
            ServerPlayerEntity p = online(d.id);
            if (p != null && ServerPlayNetworking.canSend(p, WillPayload.ID)) ServerPlayNetworking.send(p, new WillPayload(-1, 0));
        }
    }

    private void setWill(ServerPlayerEntity p, PlayerData d, double value) {
        d.will = Math.max(0.0, Math.min(GameLogic.WILL_MAX, value));
        GameLogic.WillTier t = GameLogic.willTier(d.will);
        if (t != d.tier) { GameLogic.WillTier old = d.tier; d.tier = t; onWillTierChange(p, d, old, t); }
        sendWill(p, d);
    }

    private void restoreWill(ServerPlayerEntity p, PlayerData d, double amount, String why) {
        if (amount <= 0) return;
        double after = GameLogic.restoreWill(d.will, amount);
        long gain = Math.round(after - d.will);
        if (gain > 0) bar(p, why + "  (Will +" + gain + ")", Formatting.AQUA);
        setWill(p, d, after); // sent last so a tier-change message wins over the plain restore line
    }

    private void onWillTierChange(ServerPlayerEntity p, PlayerData d, GameLogic.WillTier old, GameLogic.WillTier cur) {
        if (cur.ordinal() > old.ordinal()) {
            switch (cur) {
                case SHAKEN -> bar(p, "Your will wavers...", Formatting.YELLOW);
                case FRAYING -> bar(p, "Your will is fraying. You are not sure what you heard.", Formatting.GOLD);
                case BROKEN -> bar(p, "Your will is BROKEN. Finish a task or find company.", Formatting.RED);
                default -> {}
            }
            sound(p, SoundEvents.ENTITY_WARDEN_HEARTBEAT, 0.9f);
        } else {
            bar(p, cur == GameLogic.WillTier.STEADY ? "Your will steadies." : "Your will is recovering.", Formatting.GREEN);
            sound(p, SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, 1.3f);
        }
        if (Config.get().willSprintLock && phase == Phase.PLAYING) p.getHungerManager().setFoodLevel(foodFor(d));
    }

    /** Once per second during play: drain Will, run hallucinations, keep each client's bar in sync. */
    private void tickWill() {
        Config c = Config.get();
        List<ServerPlayerEntity> living = livingOnline();
        double r2 = c.willCompanyRadius * c.willCompanyRadius;
        for (PlayerData d : players.values()) {
            ServerPlayerEntity p = online(d.id);
            if (p == null) continue;
            if (!hasWill(d)) { hideWill(p, d); continue; }
            boolean company = false; // ANY living player counts (any role), so this never hints at who is a Manhunter
            for (ServerPlayerEntity o : living) {
                if (o == p || o.getWorld() != p.getWorld()) continue;
                if (o.squaredDistanceTo(p) <= r2) { company = true; break; }
            }
            setWill(p, d, GameLogic.drainWill(d.will, c.willDrainPerMin, company, c.willCompanyDrainFactor, 1.0));
            if (c.willHallucinations) hallucinate(p, d);
        }
    }

    private void hallucinate(ServerPlayerEntity p, PlayerData d) {
        if (d.tier.ordinal() < GameLogic.WillTier.FRAYING.ordinal()) { d.nextHallucination = 0; return; }
        if (d.nextHallucination == 0) { d.nextHallucination = now + GameLogic.hallucinationDelayTicks(d.tier, rng); return; }
        if (now < d.nextHallucination) return;
        d.nextHallucination = now + GameLogic.hallucinationDelayTicks(d.tier, rng);
        WillFx.hallucinate(p, d.tier == GameLogic.WillTier.BROKEN, rng);
    }

    // ------------------------------------------------------------------ Lantern actions

    private void verdict(ServerPlayerEntity p) {
        if (ring.verdictUsed) { bar(p, "The Verdict of Will has already been spent.", Formatting.RED); return; }
        ServerPlayerEntity t = lookTarget(p, 30, o -> true);
        if (t == null) { bar(p, "Look directly at a living player.", Formatting.GRAY); return; }
        ring.verdictUsed = true; // consumed permanently
        boolean mh = isManhunter(t.getUuid());
        Shift tsh = shifts.get(t.getUuid()); // the ring sees through borrowed faces
        String wearing = tsh == null ? "" : " (wearing " + tsh.targetName + "'s face)";
        tell(p, "VERDICT OF WILL: " + t.getName().getString() + wearing + " is " + (mh ? "a MANHUNTER." : "INNOCENT."), mh ? Formatting.RED : Formatting.GREEN);
        sound(p, SoundEvents.BLOCK_BEACON_ACTIVATE, 1.5f);
        LanternFx.verdict(p, t, mh);
    }

    private void residual(ServerPlayerEntity p) {
        if (now < ring.residualReadyAt) { bar(p, "Residual Will ready in " + GameLogic.remainingSeconds(ring.residualReadyAt, now) + "s", Formatting.RED); return; }
        Body best = null; double bd = 12 * 12;
        for (Body b : bodies) {
            if (!b.world.equals(p.getWorld().getRegistryKey())) continue;
            double dist = b.pos.squaredDistanceTo(p.getPos());
            if (dist < bd) { bd = dist; best = b; }
        }
        if (best == null) { bar(p, "No fresh trace nearby.", Formatting.GRAY); return; }
        List<Integer> avail = new ArrayList<>(List.of(0, 1, 2, 3, 4));
        avail.removeAll(best.cluesGiven);
        if (avail.isEmpty()) { bar(p, "Nothing more can be read from this scene.", Formatting.GRAY); return; }
        int pick = avail.get(rng.nextInt(avail.size()));
        best.cluesGiven.add(pick);
        ring.residualReadyAt = now + (long) Config.get().residualCooldownSec * 20;
        String msg = switch (pick) {
            case 0 -> { long secs = Math.max(5, Math.round((now - best.diedAt) / 20.0 / 10.0) * 10); yield "The will-residue is roughly " + secs + " seconds old."; }
            case 1 -> best.duringBlackout ? "Darkness clung to this death." : "The lights were steady when this happened.";
            case 2 -> "A malevolent trace here belongs to one of: " + String.join(", ", best.pool) + ".";
            case 4 -> best.shifted ? "The attacker wore a borrowed face." : "The attacker wore their own face.";
            default -> "The attacker stood to the " + best.killerSide + " of the victim.";
        };
        tell(p, "RESIDUAL WILL: " + msg, Formatting.AQUA);
        sound(p, SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, 1.0f);
        LanternFx.residual(p, best.pos);
    }

    private void shield(ServerPlayerEntity p) {
        if (ring.shieldUsedThisRound) { bar(p, "Last Light is spent until the next meeting ends.", Formatting.RED); return; }
        ServerPlayerEntity t = lookTarget(p, 10, o -> true);
        boolean self = t == null;
        if (self && ring.selfShieldUsed) { bar(p, "You may shield yourself only once per game. Look at another player.", Formatting.RED); return; }
        ServerPlayerEntity who = self ? p : t;
        ring.shieldUsedThisRound = true;
        if (self) ring.selfShieldUsed = true;
        ring.shieldTarget = who.getUuid();
        ring.shieldEndsAt = Long.MAX_VALUE; // lasts until used or until the next meeting starts
        bar(p, "Last Light shields " + (self ? "you" : who.getName().getString()) + " until the next meeting.", Formatting.GREEN);
        sound(p, SoundEvents.BLOCK_BEACON_POWER_SELECT, 1.2f);
        LanternFx.wardCast(p, who);
    }

    // ------------------------------------------------------------------ ring transfer

    private void transferRing(UUID exclude) { transferRing(exclude, null); }

    private void transferRing(UUID exclude, UUID preferred) {
        Optional<UUID> next = GameLogic.pickRingCandidate(livingIds(), manhunterIds(), exclude, rng, preferred);
        UUID old = lantern;
        lantern = next.orElse(null);
        if (old != null) { ServerPlayerEntity op = online(old); if (op != null) removeRings(op); }
        if (lantern != null) {
            PlayerData d = players.get(lantern);
            d.mode = 0;
            ServerPlayerEntity p = online(lantern);
            if (p != null) {
                giveRing(p);
                title(p, "THE RING CHOOSES YOU", "You are the Green Lantern. Tell no one.", Formatting.GREEN);
                tell(p, "You bear the ring (hotbar/inventory). Right-click = use, sneak + right-click = change mode. Verdict: 1 use per game. Tell no one.", Formatting.GREEN);
                sound(p, SoundEvents.BLOCK_BEACON_ACTIVATE, 1.0f);
            }
        }
    }

    // ------------------------------------------------------------------ meetings

    public String report(ServerPlayerEntity p, Entity clicked) {
        if (phase != Phase.PLAYING) return "Reports are only possible during play.";
        PlayerData d = players.get(p.getUuid());
        if (d == null || !d.alive) return "Only living players can report bodies.";
        Body found = null;
        if (clicked != null) {
            for (Body b : bodies) if (clicked.getUuid().equals(b.standId)) found = b;
        } else {
            double bd = Config.get().reportRange * Config.get().reportRange;
            for (Body b : bodies) {
                if (!b.world.equals(p.getWorld().getRegistryKey())) continue;
                double dist = b.pos.squaredDistanceTo(p.getPos());
                if (dist <= bd) { bd = dist; found = b; }
            }
        }
        if (found == null) return "No body within reach.";
        long since = (now - found.diedAt) / 20;
        startMeeting("BODY REPORTED", shownName(p) + " found the body of " + found.victimName
                + " (died roughly " + (Math.round(since / 10.0) * 10) + "s ago).");
        return null;
    }

    public String emergency(ServerPlayerEntity p) {
        Config c = Config.get();
        if (phase != Phase.PLAYING) return "An emergency meeting can't be called right now.";
        PlayerData d = players.get(p.getUuid());
        if (d == null || !d.alive) return "Only living players can call meetings.";
        if (now - gameStartedAt < (long) c.meetingGraceSec * 20) return "Too early in the round.";
        if (meetingsHeld > 0 && now - lastMeetingEndedAt < (long) c.emergencyCooldownSec * 20) return "Emergency meetings are on cooldown.";
        if (d.emergencyUsed >= c.emergencyMeetingsPerPlayer) return "You have no emergency meetings left.";
        d.emergencyUsed++;
        startMeeting("EMERGENCY MEETING", shownName(p) + " called an emergency meeting.");
        return null;
    }

    private void startMeeting(String headline, String detail) {
        if (phase != Phase.PLAYING) return; // no overlapping meetings
        Config c = Config.get();
        Config.MeetingPoint mp = c.meetingPoint;
        phase = Phase.DISCUSSION;
        meetingStartedAt = now;
        discussionEndsAt = now + (long) c.discussionSec * 20;
        votingEndsAt = discussionEndsAt + (long) c.votingSec * 20;
        votes.clear();
        holds.clear(); pendingQuiz.clear();
        sessions.clear(); closeTaskScreens();
        endAllShifts(true); // everyone looks like themselves in a meeting; cooldown starts now
        ring.shieldTarget = null;
        if (now < blackoutEndsAt) endBlackout();
        Vec3d pos = new Vec3d(mp.x, mp.y, mp.z);
        for (ServerPlayerEntity p : livingOnline()) {
            PlayerData d = players.get(p.getUuid());
            d.retWorld = p.getWorld().getRegistryKey(); d.retPos = p.getPos(); d.retYaw = p.getYaw(); d.retPitch = p.getPitch();
            teleportTo(p, mp.key(), pos, mp.yaw, mp.pitch);
            title(p, headline, detail, Formatting.GOLD);
            sound(p, SoundEvents.BLOCK_BELL_USE, 0.8f);
        }
        broadcast(headline + ": " + detail, Formatting.GOLD);
        broadcast("Discuss! Voting opens in " + c.discussionSec + "s. Bluff, accuse, defend.", Formatting.YELLOW);
        showBossBar();
    }

    private void beginVoting() {
        phase = Phase.VOTING;
        broadcast("VOTING OPEN (" + Config.get().votingSec + "s). Click a name or use /lantern vote <player|skip>:", Formatting.GOLD);
        MutableText line = Text.literal("");
        for (PlayerData d : players.values()) {
            if (!d.alive) continue;
            line.append(Text.literal("[" + d.name + "] ").styled(s -> s.withColor(Formatting.AQUA)
                    .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/lantern vote " + d.name))
                    .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal("Vote to eject " + d.name)))));
        }
        line.append(Text.literal("[SKIP]").styled(s -> s.withColor(Formatting.GRAY)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/lantern vote skip"))));
        for (ServerPlayerEntity p : livingOnline()) { p.sendMessage(line, false); sound(p, SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f); }
    }

    public String vote(ServerPlayerEntity p, UUID target) {
        if (phase != Phase.VOTING) return "Voting is not open.";
        if (!isAlive(p.getUuid())) return "Dead players cannot vote.";
        if (votes.containsKey(p.getUuid())) return "You already voted.";
        if (!target.equals(GameLogic.SKIP) && !isAlive(target)) return "That player is not alive.";
        votes.put(p.getUuid(), target);
        broadcast(p.getName().getString() + " has voted.", Formatting.GRAY);
        return null;
    }

    private void resolveVoting() {
        Set<UUID> living = livingIds();
        GameLogic.TallyResult r = GameLogic.tally(votes, living);
        StringBuilder sb = new StringBuilder("Results: ");
        for (Map.Entry<UUID, Integer> e : r.counts().entrySet())
            if (e.getValue() > 0) sb.append(players.get(e.getKey()).name).append("=").append(e.getValue()).append("  ");
        sb.append("Skip=").append(r.skips());
        broadcast(sb.toString(), Formatting.YELLOW);
        if (Config.get().revealVotesPublicly) {
            for (Map.Entry<UUID, UUID> e : votes.entrySet()) {
                PlayerData voter = players.get(e.getKey());
                if (voter == null) continue;
                String tgt = e.getValue().equals(GameLogic.SKIP) ? "skip" : players.get(e.getValue()) == null ? "?" : players.get(e.getValue()).name;
                broadcast(voter.name + " -> " + tgt, Formatting.GRAY);
            }
        }
        if (r.ejected() == null) {
            broadcast("No one was ejected.", Formatting.YELLOW);
        } else {
            PlayerData ej = players.get(r.ejected());
            ej.alive = false;
            ServerPlayerEntity ep = online(ej.id);
            if (ep != null) { hideWill(ep, ej); ep.changeGameMode(GameMode.SPECTATOR); title(ep, "EJECTED", "You are now a ghost.", Formatting.RED); }
            broadcast(ej.name + " was ejected." + (Config.get().revealRoleOnDeath ? " They were " + ej.role + "." : ""), Formatting.RED);
            if (ej.id.equals(lantern)) transferRing(ej.id); // secret; no Manhunter victory
        }
        if (checkWinAndEnd()) return;
        endMeeting();
    }

    private void endMeeting() {
        removeBossBar();
        Config c = Config.get();
        long dur = now - meetingStartedAt;
        killReady.replaceAll((k, v) -> Math.max(v + dur, now + (long) c.postMeetingKillCooldownSec * 20));
        blackoutReady.replaceAll((k, v) -> v + dur);
        shiftReady.replaceAll((k, v) -> v + dur);
        ring.residualReadyAt += dur;
        ring.shieldUsedThisRound = false; // refreshed when the meeting ends
        ring.guardianLock = GameLogic.afterMeeting(ring.guardianLock); // Guardian Light recovers one step per ended meeting
        ring.shieldTarget = null;
        clearBodies();
        for (ServerPlayerEntity wp : livingOnline()) { // a meeting steadies everyone's nerves
            PlayerData wd = players.get(wp.getUuid());
            if (wd == null || !hasWill(wd)) continue;
            wd.nextHallucination = 0;
            restoreWill(wp, wd, c.willMeetingRestore, "The meeting steadies you");
        }
        if (c.returnPlayersAfterMeeting) {
            for (ServerPlayerEntity p : livingOnline()) {
                PlayerData d = players.get(p.getUuid());
                if (d.retPos != null) teleportTo(p, d.retWorld, d.retPos, d.retYaw, d.retPitch);
            }
        }
        lastMeetingEndedAt = now;
        meetingsHeld++;
        votes.clear();
        phase = Phase.PLAYING;
        if (!ringGranted) {
            ringGranted = true;
            UUID wanted = null;
            for (Map.Entry<UUID, String> e : forced.entrySet()) if (e.getValue().equals("lantern")) wanted = e.getKey();
            transferRing(null, wanted);
        }
        broadcast("The meeting has ended. Stay alert.", Formatting.YELLOW);
    }

    // ------------------------------------------------------------------ tasks

    private void assignTasks() {
        stations = new ArrayList<>();
        for (Config.TaskStation s : Config.get().tasks) if (server.getWorld(s.key()) != null) stations.add(s);
        thresholdsAwarded = 0; holds.clear(); pendingQuiz.clear();
        if (stations.isEmpty()) return;
        int n = Math.min(Math.max(1, Config.get().tasksPerPlayer), stations.size());
        List<int[]> sets = GameLogic.assignTaskSets(stations.size(), n, players.size(), rng);
        int k = 0;
        for (PlayerData d : players.values()) { // Manhunters get fake tasks that look identical; every player gets a different mix
            List<Integer> mine = new ArrayList<>();
            for (int st : sets.get(k++)) mine.add(st);
            Collections.shuffle(mine, rng);
            for (int st : mine) d.tasks.add(new TaskAssign(st));
        }
    }

    /** {done, total}: only innocents count; a dead innocent's unfinished tasks drop out of the total. */
    private int[] teamProgress() {
        int done = 0, total = 0;
        for (PlayerData d : players.values()) {
            if (d.role != Role.INNOCENT) continue;
            for (TaskAssign t : d.tasks) { if (t.done) { done++; total++; } else if (d.alive) total++; }
        }
        return new int[]{done, total};
    }

    private int teamPercent() { int[] p = teamProgress(); return (int) Math.round(100 * GameLogic.progress(p[0], p[1])); }

    private int stationAt(ServerPlayerEntity p, BlockPos pos) {
        String dim = p.getWorld().getRegistryKey().getValue().toString();
        for (int i = 0; i < stations.size(); i++) if (stations.get(i).matches(dim, pos.getX(), pos.getY(), pos.getZ())) return i;
        return -1;
    }

    public boolean isStation(ServerPlayerEntity p, BlockPos pos) { return phase != Phase.IDLE && stationAt(p, pos) >= 0; }

    static String taskTitle(String type) {
        return switch (type) {
            case "wires" -> "Fix Wiring";
            case "keypad", "puzzle" -> "Unlock Manifolds";
            case "shields" -> "Prime Shields";
            case "download", "scan" -> "Download Data";
            case "focus" -> "Will Focus";
            case "spectrum" -> "Spectrum Sequence";
            case "lever" -> "Drain Reserves";
            case "timing" -> "Calibrate Distributor";
            case "calibrate" -> "Power Node";
            default -> "Task";
        };
    }

    /** Which client screen a station type opens. */
    static String uiFor(String type) {
        return switch (type) {
            case "wires" -> "wires";
            case "keypad", "puzzle" -> "keypad";
            case "shields" -> "shields";
            case "focus", "calibrate" -> "focus";
            case "spectrum" -> "spectrum";
            case "lever" -> "lever";
            case "timing" -> "timing";
            default -> "download";
        };
    }

    /** Fastest a human could plausibly finish; anything quicker is rejected by the server. */
    private static long minMillis(String ui) {
        return switch (ui) {
            case "wires" -> 2000; case "keypad" -> 2000; case "shields" -> 1500; case "download" -> 3500;
            case "focus" -> 4500; case "spectrum" -> 4000; case "lever" -> 800; case "timing" -> 2000;
            default -> 2000;
        };
    }

    public void onBlockUse(ServerPlayerEntity p, BlockPos pos) {
        int si = stationAt(p, pos);
        PlayerData d = players.get(p.getUuid());
        if (si < 0 || d == null || !d.alive) return;
        if (phase != Phase.PLAYING) { bar(p, "No tasks during meetings.", Formatting.GRAY); return; }
        int ti = -1;
        for (int i = 0; i < d.tasks.size(); i++) if (d.tasks.get(i).station == si && !d.tasks.get(i).done) ti = i;
        if (ti < 0) { bar(p, "You have no task here.", Formatting.GRAY); return; }
        Config.TaskStation st = stations.get(si);
        String ui = uiFor(st.type);
        if (!ServerPlayNetworking.canSend(p, OpenTaskPayload.ID)) { bar(p, "Your game is missing the Lanterns mod.", Formatting.RED); return; }
        sessions.put(p.getUuid(), new Session(ti, ui, now));
        ServerPlayNetworking.send(p, new OpenTaskPayload(ti, ui, rng.nextInt(), taskTitle(st.type)));
    }

    /** The client reports a finished (or abandoned) task screen. Everything is re-checked here. */
    public void onTaskResult(ServerPlayerEntity p, int ti, boolean success) {
        Session ses = sessions.remove(p.getUuid());
        PlayerData d = players.get(p.getUuid());
        if (ses == null || d == null || !d.alive || phase != Phase.PLAYING || ses.task != ti || ti < 0 || ti >= d.tasks.size()) return;
        if (!success) return; // abandoned: nothing happens, they can reopen it
        TaskAssign t = d.tasks.get(ti);
        if (t.done) return;
        Config.TaskStation st = stations.get(t.station);
        double dist2 = p.squaredDistanceTo(st.x + 0.5, st.y + 0.5, st.z + 0.5);
        if (!p.getWorld().getRegistryKey().getValue().toString().equals(st.dimension) || dist2 > 10 * 10) { bar(p, "You moved too far from the terminal.", Formatting.RED); return; }
        if ((now - ses.openedAt) * 50L < minMillis(ses.ui)) { bar(p, "That was too fast. Try again.", Formatting.RED); return; }
        completeTask(p, d, ti);
    }

    private void closeTaskScreens() {
        for (ServerPlayerEntity p : livingOnline())
            if (ServerPlayNetworking.canSend(p, OpenTaskPayload.ID)) ServerPlayNetworking.send(p, new OpenTaskPayload(-1, "close", 0, ""));
    }

    // ---- HUD tracker: each player gets ONLY their own list (Manhunters get their fake list)
    private void sendTaskList(ServerPlayerEntity p, PlayerData d) {
        if (!ServerPlayNetworking.canSend(p, TaskListPayload.ID)) return;
        StringBuilder sb = new StringBuilder();
        int mine = 0, total = 0;
        if (d != null && d.alive && !stations.isEmpty()) {
            total = d.tasks.size();
            for (TaskAssign t : d.tasks) {
                Config.TaskStation st = stations.get(t.station);
                if (t.done) mine++;
                sb.append(taskTitle(st.type)).append('|').append(st.x).append('|').append(st.y).append('|').append(st.z)
                  .append('|').append(st.dimension).append('|').append(t.done ? 1 : 0).append(';');
            }
        }
        ServerPlayNetworking.send(p, new TaskListPayload(sb.toString(), mine, total, teamPercent()));
    }

    private void refreshTaskHud() {
        for (PlayerData d : players.values()) {
            ServerPlayerEntity p = online(d.id);
            if (p != null) sendTaskList(p, d);
        }
    }

    private void clearTaskHud() {
        for (PlayerData d : players.values()) {
            ServerPlayerEntity p = online(d.id);
            if (p != null && ServerPlayNetworking.canSend(p, TaskListPayload.ID))
                ServerPlayNetworking.send(p, new TaskListPayload("", 0, 0, 0));
        }
    }

    private void completeTask(ServerPlayerEntity p, PlayerData d, int ti) {
        TaskAssign t = d.tasks.get(ti);
        if (t.done) return;
        t.done = true;
        Config.TaskStation s = stations.get(t.station);
        int mine = 0; for (TaskAssign x : d.tasks) if (x.done) mine++;
        tell(p, "Task complete (" + mine + "/" + d.tasks.size() + ")", Formatting.GREEN);
        sound(p, SoundEvents.ENTITY_PLAYER_LEVELUP, 1.2f);
        if (hasWill(d)) restoreWill(p, d, Config.get().willTaskRestore, "Task complete");
        // Power nodes quietly recharge Residual Will for the Lantern only (Verdict is never restored).
        if (d.id.equals(lantern) && s.type.equals("calibrate")) {
            ring.residualReadyAt = now;
            tell(p, "The ring hums: Residual Will is recharged.", Formatting.DARK_GREEN);
        }
        if (d.role == Role.INNOCENT) onProgress();
        refreshTaskHud();
    }

    private void onProgress() {
        int[] pr = teamProgress();
        double frac = GameLogic.progress(pr[0], pr[1]);
        int target = GameLogic.crossedThresholds(Config.get().guardianThresholds, thresholdsAwarded, frac);
        while (thresholdsAwarded < target) { thresholdsAwarded++; awardGuardian(); }
        broadcast("Team tasks: " + teamPercent() + "%", Formatting.AQUA);
        checkWinAndEnd();
    }

    private void awardGuardian() {
        if (ring.guardianCharge >= 1) return; // max one banked charge
        ring.guardianCharge = 1;
        ServerPlayerEntity lp = lantern == null ? null : online(lantern);
        if (lp != null && isAlive(lantern))
            tell(lp, ring.guardianLock > 0 ? "Guardian Light charged, but it stays locked for " + ring.guardianLock + " more meeting(s)."
                    : "GUARDIAN LIGHT is charged: it will turn aside the next killing blow against you.", Formatting.GREEN);
    }

    private void showStationParticles() {
        for (PlayerData d : players.values()) {
            ServerPlayerEntity p = online(d.id);
            if (p == null || !d.alive) continue;
            String dim = p.getWorld().getRegistryKey().getValue().toString();
            for (TaskAssign t : d.tasks) {
                if (t.done) continue;
                Config.TaskStation s = stations.get(t.station);
                if (!s.dimension.equals(dim) || p.squaredDistanceTo(s.x + 0.5, s.y + 0.5, s.z + 0.5) > 32 * 32) continue;
                p.getServerWorld().spawnParticles(p, ParticleTypes.END_ROD, true, s.x + 0.5, s.y + 1.3, s.z + 0.5, 3, 0.25, 0.4, 0.25, 0.01);
            }
        }
    }

    public void showTasks(ServerPlayerEntity p) {
        PlayerData d = players.get(p.getUuid());
        if (phase == Phase.IDLE || d == null) { tell(p, "You are not in a game.", Formatting.GRAY); return; }
        if (stations.isEmpty()) { tell(p, "No task stations are set up for this game.", Formatting.GRAY); return; }
        tell(p, "Your tasks (team progress " + teamPercent() + "%):", Formatting.AQUA);
        for (TaskAssign t : d.tasks) {
            Config.TaskStation s = stations.get(t.station);
            String name = taskTitle(s.type);
            p.sendMessage(Text.literal((t.done ? "[x] " : "[ ] ") + name + " at " + s.x + " " + s.y + " " + s.z)
                    .formatted(t.done ? Formatting.GRAY : Formatting.WHITE), false);
        }
    }

    // ------------------------------------------------------------------ Manhunter ally outline
    // Each Manhunter's client gets (1) a private red scoreboard team containing their allies and
    // (2) a "glowing" flag on those allies. Both are sent ONLY to Manhunters, so nobody else sees them.

    private void setupAllyOutline() {
        if (!Config.get().manhunterOutline) return;
        for (PlayerData v : players.values()) {
            if (v.role != Role.MANHUNTER) continue;
            ServerPlayerEntity vp = online(v.id);
            if (vp == null) continue;
            Team t = new Team(server.getScoreboard(), "lanterns_mh");
            t.setColor(Formatting.RED);
            for (PlayerData a : players.values())
                if (a.role == Role.MANHUNTER && !a.id.equals(v.id)) t.getPlayerList().add(a.name);
            if (t.getPlayerList().isEmpty()) continue; // lone Manhunter: nobody to outline
            outlineTeams.put(v.id, t);
            vp.networkHandler.sendPacket(TeamS2CPacket.updateTeam(t, true));
        }
    }

    /** Vanilla re-sends the real flags whenever they change, so we re-assert the glow bit every 5 ticks. */
    private void refreshOutlines() {
        for (PlayerData v : players.values()) {
            if (v.role != Role.MANHUNTER || !v.alive || !outlineTeams.containsKey(v.id)) continue;
            ServerPlayerEntity vp = online(v.id);
            if (vp == null) continue;
            for (PlayerData a : players.values()) {
                if (a.role != Role.MANHUNTER || a.id.equals(v.id)) continue;
                ServerPlayerEntity ap = online(a.id);
                if (ap == null) continue;
                if (a.alive) sendGlow(vp, ap, true);
                else if (outlined.contains(a.id)) sendGlow(vp, ap, false);
            }
        }
        for (PlayerData a : players.values()) { if (a.alive && a.role == Role.MANHUNTER) outlined.add(a.id); else outlined.remove(a.id); }
    }

    private void sendGlow(ServerPlayerEntity viewer, ServerPlayerEntity target, boolean glow) {
        int flags = 0;
        if (target.isOnFire()) flags |= 1;
        if (target.isSneaking()) flags |= 2;
        if (target.isSprinting()) flags |= 8;
        if (target.isSwimming()) flags |= 16;
        if (target.isInvisible()) flags |= 32;
        if (glow) flags |= 64;
        if (target.isFallFlying()) flags |= 128;
        List<DataTracker.SerializedEntry<?>> entries = List.<DataTracker.SerializedEntry<?>>of(
                DataTracker.SerializedEntry.of(net.minecraft.entity.Entity.FLAGS, Byte.valueOf((byte) flags)));
        viewer.networkHandler.sendPacket(new EntityTrackerUpdateS2CPacket(target.getId(), entries));
    }

    private void clearOutlines() {
        for (Map.Entry<UUID, Team> e : outlineTeams.entrySet()) {
            ServerPlayerEntity vp = online(e.getKey());
            if (vp == null) continue;
            for (String name : e.getValue().getPlayerList()) {
                ServerPlayerEntity ap = server.getPlayerManager().getPlayer(name);
                if (ap != null) sendGlow(vp, ap, false);
            }
            vp.networkHandler.sendPacket(TeamS2CPacket.updateRemovedTeam(e.getValue()));
        }
        outlineTeams.clear();
        outlined.clear();
    }

    // ------------------------------------------------------------------ shapeshift
    // Server owns the truth (who looks like whom); clients render it via DisguisePayload + two client mixins.
    // Innocents (and ghosts) see the disguise; fellow Manhunters see through it (config manhunterSeesDisguise).

    public String shapeshift(ServerPlayerEntity p) {
        PlayerData d = players.get(p.getUuid());
        if (phase != Phase.PLAYING || d == null || !d.alive || d.role != Role.MANHUNTER) return "Nothing happens.";
        if (shifts.containsKey(p.getUuid())) { endShift(p.getUuid(), true, true); return null; } // revert early
        long ready = shiftReady.getOrDefault(p.getUuid(), 0L);
        if (now < ready) return "Shapeshift ready in " + GameLogic.remainingSeconds(ready, now) + "s";
        ServerPlayerEntity t = lookTarget(p, 30, o -> true);
        if (t == null) return "Look at the player you want to become.";
        Config c = Config.get();
        String name = t.getName().getString();
        shifts.put(p.getUuid(), new Shift(t.getUuid(), name, now + (long) c.shapeshiftDurationSec * 20));
        shiftEvidence(p);
        broadcastDisguise(p.getUuid(), t.getUuid(), name);
        bar(p, "You now look like " + name + " for " + c.shapeshiftDurationSec + "s. Right-click again to revert early.", Formatting.DARK_PURPLE);
        return null;
    }

    private void endShift(UUID id, boolean evidence, boolean cooldown) {
        if (shifts.remove(id) == null) return;
        broadcastDisguise(id, null, null);
        if (cooldown) shiftReady.put(id, now + (long) Config.get().shapeshiftCooldownSec * 20);
        ServerPlayerEntity p = online(id);
        if (p != null) {
            if (evidence) shiftEvidence(p);
            bar(p, "You returned to your own form.", Formatting.DARK_PURPLE);
        }
    }

    private void dropShift(UUID id) { if (shifts.remove(id) != null) broadcastDisguise(id, null, null); }

    private void endAllShifts(boolean cooldown) {
        for (UUID id : new ArrayList<>(shifts.keySet())) endShift(id, false, cooldown);
    }

    private void tickShifts() {
        for (UUID id : new ArrayList<>(shifts.keySet())) {
            Shift s = shifts.get(id);
            if (s == null) continue;
            PlayerData d = players.get(id);
            if (d == null || !d.alive || online(id) == null) { dropShift(id); continue; }
            if (!isAlive(s.target) || online(s.target) == null || now >= s.endsAt) endShift(id, true, true);
        }
    }

    /** Among Us style evidence: anyone nearby sees (and hears) the transformation. */
    private void shiftEvidence(ServerPlayerEntity p) {
        if (!Config.get().shapeshiftEvidence) return;
        ServerWorld w = p.getServerWorld();
        w.spawnParticles(ParticleTypes.REVERSE_PORTAL, p.getX(), p.getY() + 1, p.getZ(), 60, 0.4, 0.8, 0.4, 0.15);
        w.spawnParticles(ParticleTypes.WITCH, p.getX(), p.getY() + 1, p.getZ(), 20, 0.4, 0.8, 0.4, 0.05);
        w.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.ENTITY_ENDERMAN_TELEPORT, SoundCategory.PLAYERS, 0.8f, 1.6f);
    }

    private void broadcastDisguise(UUID shifter, UUID target, String name) {
        DisguisePayload pl = new DisguisePayload(shifter, target == null ? GameLogic.SKIP : target, name == null ? "" : name);
        for (ServerPlayerEntity v : server.getPlayerManager().getPlayerList()) {
            if (isManhunter(v.getUuid()) && !Config.get().manhunterSeesDisguise) continue; // allies see through
            if (ServerPlayNetworking.canSend(v, DisguisePayload.ID)) ServerPlayNetworking.send(v, pl);
        }
    }

    private void syncDisguisesTo(ServerPlayerEntity v) {
        if (isManhunter(v.getUuid()) && !Config.get().manhunterSeesDisguise) return;
        if (!ServerPlayNetworking.canSend(v, DisguisePayload.ID)) return;
        for (Map.Entry<UUID, Shift> e : shifts.entrySet())
            ServerPlayNetworking.send(v, new DisguisePayload(e.getKey(), e.getValue().target, e.getValue().targetName));
    }

    private String shiftLabel(PlayerData d) {
        Shift s = shifts.get(d.id);
        if (s != null) return "ACTIVE " + GameLogic.remainingSeconds(s.endsAt, now) + "s";
        return cd(shiftReady.getOrDefault(d.id, 0L));
    }

    private String shownName(ServerPlayerEntity p) {
        Shift s = shifts.get(p.getUuid());
        return s != null ? s.targetName : p.getName().getString();
    }

    /** Name this player is currently disguised as, or null. */
    public String disguiseName(ServerPlayerEntity p) {
        Shift s = phase == Phase.IDLE ? null : shifts.get(p.getUuid());
        return s == null ? null : s.targetName;
    }

    public void broadcastDisguisedChat(ServerPlayerEntity sender, String text) {
        server.getPlayerManager().broadcast(Text.literal("<" + shownName(sender) + "> " + text), false);
    }

    // ------------------------------------------------------------------ boss bar timer

    private void showBossBar() {
        removeBossBar();
        bossBar = new ServerBossBar(Text.literal("Discussion"), BossBar.Color.YELLOW, BossBar.Style.PROGRESS);
        for (PlayerData d : players.values()) {
            ServerPlayerEntity p = online(d.id);
            if (p != null) bossBar.addPlayer(p);
        }
        updateBossBar();
    }

    private void updateBossBar() {
        if (bossBar == null) return;
        Config c = Config.get();
        if (phase == Phase.DISCUSSION) {
            long total = Math.max(1, c.discussionSec * 20L), rem = Math.max(0, discussionEndsAt - now);
            bossBar.setName(Text.literal("Discussion - voting opens in " + (rem + 19) / 20 + "s"));
            bossBar.setColor(BossBar.Color.YELLOW);
            bossBar.setPercent(Math.min(1f, rem / (float) total));
        } else if (phase == Phase.VOTING) {
            long total = Math.max(1, c.votingSec * 20L), rem = Math.max(0, votingEndsAt - now);
            bossBar.setName(Text.literal("VOTE NOW - " + (rem + 19) / 20 + "s"));
            bossBar.setColor(BossBar.Color.RED);
            bossBar.setPercent(Math.min(1f, rem / (float) total));
        }
    }

    private void removeBossBar() {
        if (bossBar != null) { bossBar.clearPlayers(); bossBar = null; }
    }

    // ------------------------------------------------------------------ connection events

    public void onDisconnect(ServerPlayerEntity p) {
        if (phase == Phase.IDLE) return;
        PlayerData d = players.get(p.getUuid());
        if (d == null) return;
        if (d.alive) {
            d.alive = false;
            dropShift(p.getUuid());
            sessions.remove(p.getUuid());
            votes.remove(p.getUuid());
            broadcast(d.name + " left the game and was removed.", Formatting.GRAY);
            if (p.getUuid().equals(lantern)) transferRing(p.getUuid());
        }
        restorePlayer(p, d);
        checkWinAndEnd();
    }

    public void onJoin(ServerPlayerEntity p) {
        if (phase == Phase.IDLE) return;
        syncDisguisesTo(p);
        p.changeGameMode(GameMode.SPECTATOR);
        tell(p, "A game is in progress. You are spectating.", Formatting.GRAY);
    }

    // ------------------------------------------------------------------ misc queries

    /** Admin helper. role = manhunter | innocent | lantern | clear. Returns a status message. */
    public String forceRole(UUID id, String name, String role) {
        switch (role) {
            case "clear" -> { forced.remove(id); return "Cleared forced role for " + name + "."; }
            case "manhunter", "innocent", "lantern" -> {
                forced.put(id, role);
                String extra = role.equals("lantern") ? " (you get the ring when the first meeting ends, if you are alive)" : "";
                return name + " is guaranteed to be " + role.toUpperCase() + " from the next /lantern start" + extra + ".";
            }
            default -> { return "Unknown role. Use manhunter, innocent, lantern or clear."; }
        }
    }

    public boolean isLivingParticipant(ServerPlayerEntity p) { return phase != Phase.IDLE && isAlive(p.getUuid()); }
    public boolean isGhost(ServerPlayerEntity p) { PlayerData d = players.get(p.getUuid()); return phase != Phase.IDLE && d != null && !d.alive; }
    public boolean isBody(Entity e) { return e.getCommandTags().contains("lanterns_body"); }

    public Collection<String> livingNames() {
        List<String> n = new ArrayList<>();
        for (PlayerData d : players.values()) if (d.alive) n.add(d.name);
        return n;
    }

    public void sendGhostChat(ServerPlayerEntity sender, String text) {
        Text t = Text.literal("[Ghost] <" + sender.getName().getString() + "> " + text).formatted(Formatting.GRAY, Formatting.ITALIC);
        for (PlayerData d : players.values()) {
            if (d.alive) continue;
            ServerPlayerEntity p = online(d.id);
            if (p != null) p.sendMessage(t, false);
        }
    }

    public List<Text> status() {
        List<Text> out = new ArrayList<>();
        if (phase == Phase.IDLE) { out.add(tag("No game running.", Formatting.GRAY)); return out; }
        int alive = 0; for (PlayerData d : players.values()) if (d.alive) alive++;
        out.add(tag("Phase: " + phase + "  |  Alive: " + alive + "/" + players.size()
                + "  |  Manhunters at large: " + manhunterIds().size() + " at start (hidden)  |  Meetings held: " + meetingsHeld
                + "  |  Ring granted: " + (ringGranted ? "yes" : "not yet"), Formatting.YELLOW));
        out.add(tag("Living: " + String.join(", ", livingNames()), Formatting.GRAY));
        return out;
    }
}
