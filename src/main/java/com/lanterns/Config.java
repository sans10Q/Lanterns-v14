package com.lanterns;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;

import java.nio.file.Files;
import java.nio.file.Path;

/** JSON config at config/lanterns.json. Single-use limits (Verdict, self-shield) are NOT configurable. */
public class Config {
    public int configVersion = 0; // internal: used to migrate old config files
    public boolean autoScaleManhunters = true; // 1 (<6 players), 2 (6-10), 3 (11+); false = use "manhunters" below
    public int manhunters = 1;                 // only used when autoScaleManhunters is false
    public int minPlayers = 6;
    public int killCooldownSec = 60;
    public int initialKillCooldownSec = 30;
    public int postMeetingKillCooldownSec = 10;
    public double killRange = 3.5;
    public int blackoutDurationSec = 10;
    public int blackoutCooldownSec = 90;
    public int residualCooldownSec = 45;
    public int discussionSec = 45;
    public int votingSec = 30;
    public int emergencyMeetingsPerPlayer = 1;
    public int meetingGraceSec = 20;
    public int emergencyCooldownSec = 30;
    public double reportRange = 8.0;
    public int tasksPerPlayer = 3;
    public boolean tasksCompleteWins = true;     // innocents win when every (remaining) task is done
    public double[] guardianThresholds = {0.25, 0.50, 0.75}; // team task progress that charges Guardian Light
    public java.util.List<TaskStation> tasks = new java.util.ArrayList<>();
    public int shapeshiftDurationSec = 20;
    public int shapeshiftCooldownSec = 60;
    public boolean shapeshiftEvidence = true;      // transformation shimmer + sound visible to everyone nearby
    public boolean manhunterSeesDisguise = false;  // false: fellow Manhunters see through it (true is handy for solo testing)
    public boolean manhunterOutline = true; // Manhunters see allies with a red outline (only they can see it)
    // --- Will METER (v1.14): a personal stress bar for innocents. Manhunters never have it. Separate from the ring (never touches
    // Verdict / Residual Will / Last Light / Guardian Light). Needs task stations.
    public boolean willEnabled = true;
    public double willDrainPerMin = 12.0;       // Will lost per minute when alone (100 -> 0 in about 8 minutes)
    public double willTaskRestore = 35.0;       // restored when an innocent finishes a task
    public double willMeetingRestore = 25.0;    // restored to every living innocent when a meeting ends
    public double willCompanyRadius = 6.0;      // any living player (any role) this close counts as company
    public double willCompanyDrainFactor = 0.5; // drain multiplier while in company
    public boolean willHallucinations = true;   // private fake sounds / glimpses at Fraying and Broken
    public boolean willSprintLock = true;       // Broken innocents cannot sprint
    public double meetingFreeRadius = 3.0; // players may shuffle this far from the meeting point before being pulled back
    public boolean revealRoleOnDeath = false;
    public boolean revealVotesPublicly = false;
    public boolean returnPlayersAfterMeeting = true;
    public MeetingPoint meetingPoint = new MeetingPoint();

    public static class TaskStation {
        public String dimension = "minecraft:overworld";
        public int x, y, z;
        public String type = "scan"; // scan | puzzle | calibrate

        public boolean matches(String dim, int bx, int by, int bz) {
            return dimension.equals(dim) && x == bx && y == by && z == bz;
        }

        public RegistryKey<World> key() {
            return RegistryKey.of(RegistryKeys.WORLD, Identifier.of(dimension));
        }
    }

    public static class MeetingPoint {
        public boolean set = false;
        public String dimension = "minecraft:overworld";
        public double x, y, z;
        public float yaw, pitch;

        public RegistryKey<World> key() {
            return RegistryKey.of(RegistryKeys.WORLD, Identifier.of(dimension));
        }
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Config instance = new Config();

    public static Config get() { return instance; }

    private static Path path() { return FabricLoader.getInstance().getConfigDir().resolve("lanterns.json"); }

    public static void load() {
        try {
            Path p = path();
            if (Files.exists(p)) {
                Config c = GSON.fromJson(Files.readString(p), Config.class);
                if (c != null) instance = c;
                if (instance.meetingPoint == null) instance.meetingPoint = new MeetingPoint();
            }
        } catch (Exception e) {
            LanternsMod.LOGGER.error("Failed to read config, using defaults", e);
        }
        if (instance.configVersion < 2) { // v1.5 defaults: 1 Manhunter, 45s discussion
            instance.manhunters = 1;
            instance.discussionSec = 45;
            instance.configVersion = 2;
        }
        instance.willDrainPerMin = Math.max(0, instance.willDrainPerMin);
        instance.willTaskRestore = Math.max(0, instance.willTaskRestore);
        instance.willMeetingRestore = Math.max(0, instance.willMeetingRestore);
        instance.willCompanyRadius = Math.max(0, instance.willCompanyRadius);
        instance.willCompanyDrainFactor = Math.max(0, instance.willCompanyDrainFactor);
        if (instance.tasks == null) instance.tasks = new java.util.ArrayList<>();
        if (instance.guardianThresholds == null) instance.guardianThresholds = new double[]{0.25, 0.50, 0.75};
        save();
    }

    public static void save() {
        try {
            Files.writeString(path(), GSON.toJson(instance));
        } catch (Exception e) {
            LanternsMod.LOGGER.error("Failed to save config", e);
        }
    }
}
