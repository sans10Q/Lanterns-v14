package com.lanterns;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Client-side table of active disguises. Plain Java (no client classes) so it is safe to load anywhere. */
public final class ClientDisguises {
    private ClientDisguises() {}

    public record Disguise(UUID target, String name) {}

    private static final Map<UUID, Disguise> MAP = new ConcurrentHashMap<>();

    public static void apply(DisguisePayload p) {
        if (GameLogic.SKIP.equals(p.target())) MAP.remove(p.shifter());
        else MAP.put(p.shifter(), new Disguise(p.target(), p.targetName()));
    }

    public static Disguise get(UUID shifter) { return MAP.get(shifter); }

    public static void clear() { MAP.clear(); }
}
