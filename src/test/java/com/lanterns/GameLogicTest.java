package com.lanterns;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class GameLogicTest {
    private static List<UUID> ids(int n) { List<UUID> l = new ArrayList<>(); for (int i = 0; i < n; i++) l.add(UUID.randomUUID()); return l; }

    @Test void manhunterCountIsClamped() {
        assertEquals(2, GameLogic.manhunterCount(6, 2));
        assertEquals(2, GameLogic.manhunterCount(12, 2));
        assertEquals(1, GameLogic.manhunterCount(3, 2));
        assertEquals(1, GameLogic.manhunterCount(4, 5));
        assertEquals(1, GameLogic.manhunterCount(10, 0));
    }

    @Test void manhunterCountScalesWithLobbySize() {
        assertEquals(1, GameLogic.scaledManhunterCount(3));
        assertEquals(1, GameLogic.scaledManhunterCount(5));
        assertEquals(2, GameLogic.scaledManhunterCount(6));
        assertEquals(2, GameLogic.scaledManhunterCount(10));
        assertEquals(3, GameLogic.scaledManhunterCount(11));
        assertEquals(3, GameLogic.scaledManhunterCount(20));
        for (int n = 3; n <= 20; n++) assertTrue(GameLogic.manhunterCount(n, GameLogic.scaledManhunterCount(n)) * 2 < n);
    }

    @Test void roleAssignmentPicksExactCountFromPlayers() {
        for (int i = 0; i < 200; i++) {
            List<UUID> p = ids(10);
            Set<UUID> m = GameLogic.assignManhunters(p, 2, new Random(i));
            assertEquals(2, m.size());
            assertTrue(p.containsAll(m));
        }
    }

    @Test void winConditions() {
        assertEquals(GameLogic.Winner.INNOCENTS, GameLogic.checkWin(0, 5));
        assertEquals(GameLogic.Winner.MANHUNTERS, GameLogic.checkWin(2, 2));
        assertEquals(GameLogic.Winner.MANHUNTERS, GameLogic.checkWin(2, 1));
        assertEquals(GameLogic.Winner.NONE, GameLogic.checkWin(2, 3));
        assertEquals(GameLogic.Winner.NONE, GameLogic.checkWin(1, 2));
    }

    @Test void clearMajorityEjects() {
        List<UUID> p = ids(5); Set<UUID> living = new LinkedHashSet<>(p);
        Map<UUID, UUID> v = new HashMap<>();
        v.put(p.get(0), p.get(4)); v.put(p.get(1), p.get(4)); v.put(p.get(2), p.get(3)); v.put(p.get(3), GameLogic.SKIP);
        assertEquals(p.get(4), GameLogic.tally(v, living).ejected());
    }

    @Test void tiesProduceNoElimination() {
        List<UUID> p = ids(4); Set<UUID> living = new LinkedHashSet<>(p);
        Map<UUID, UUID> v = new HashMap<>();
        v.put(p.get(0), p.get(2)); v.put(p.get(1), p.get(3));
        assertNull(GameLogic.tally(v, living).ejected());
    }

    @Test void skipsBeatOrTieTopVote() {
        List<UUID> p = ids(5); Set<UUID> living = new LinkedHashSet<>(p);
        Map<UUID, UUID> v = new HashMap<>();
        v.put(p.get(0), p.get(4)); v.put(p.get(1), GameLogic.SKIP);
        assertNull(GameLogic.tally(v, living).ejected());           // 1 vs 1 skip
        v.put(p.get(2), GameLogic.SKIP);
        assertNull(GameLogic.tally(v, living).ejected());           // skip majority
        v.put(p.get(3), p.get(4));
        v.put(p.get(4), p.get(4));
        assertEquals(p.get(4), GameLogic.tally(v, living).ejected()); // 3 > 2 skips
    }

    @Test void noVotesAndDeadVotersAreIgnored() {
        List<UUID> p = ids(4); Set<UUID> living = new LinkedHashSet<>(p.subList(0, 3));
        assertNull(GameLogic.tally(new HashMap<>(), living).ejected());
        Map<UUID, UUID> v = new HashMap<>();
        v.put(p.get(3), p.get(0));        // dead voter
        v.put(p.get(0), p.get(3));        // vote for dead target
        assertNull(GameLogic.tally(v, living).ejected());
    }

    @Test void ringCandidateNeverManhunterOrExcluded() {
        for (int i = 0; i < 300; i++) {
            List<UUID> p = ids(6);
            Set<UUID> m = new HashSet<>(p.subList(0, 2));
            UUID excl = p.get(2);
            Optional<UUID> c = GameLogic.pickRingCandidate(p, m, excl, new Random(i));
            assertTrue(c.isPresent());
            assertFalse(m.contains(c.get()));
            assertNotEquals(excl, c.get());
        }
    }

    @Test void ringCandidateEmptyWhenNoInnocentsLeft() {
        List<UUID> p = ids(3); Set<UUID> m = new HashSet<>(p.subList(0, 2));
        assertTrue(GameLogic.pickRingCandidate(p, m, p.get(2), new Random(1)).isEmpty());
    }

    @Test void forcedRolesAreRespected() {
        for (int i = 0; i < 300; i++) {
            List<UUID> p = ids(8);
            Set<UUID> m = GameLogic.assignManhunters(p, 2, Set.of(p.get(0)), Set.of(p.get(1), p.get(2)), new Random(i));
            assertEquals(2, m.size());
            assertTrue(m.contains(p.get(0)));
            assertFalse(m.contains(p.get(1)));
            assertFalse(m.contains(p.get(2)));
        }
    }

    @Test void forcedLanternIsPreferredWhenValid() {
        List<UUID> p = ids(6); Set<UUID> m = new HashSet<>(p.subList(0, 1));
        assertEquals(p.get(3), GameLogic.pickRingCandidate(p, m, null, new Random(1), p.get(3)).get());
        assertNotEquals(p.get(0), GameLogic.pickRingCandidate(p, m, null, new Random(1), p.get(0)).get()); // Manhunter can't be Lantern
    }

    @Test void everyPlayerGetsADifferentTaskMix() {
        for (int seed = 0; seed < 200; seed++) {
            int stations = 6 + seed % 5, players = 6 + seed % 7;
            List<int[]> sets = GameLogic.assignTaskSets(stations, 3, players, new Random(seed));
            Set<String> sigs = new HashSet<>();
            for (int[] s : sets) {
                assertEquals(3, s.length);
                assertEquals(3, Arrays.stream(s).distinct().count());
                sigs.add(Arrays.toString(s));
            }
            assertEquals(players, sigs.size());
        }
    }

    @Test void guardianThresholdsAndProgress() {
        double[] th = {0.25, 0.5, 0.75};
        assertEquals(0, GameLogic.crossedThresholds(th, 0, 0.1));
        assertEquals(2, GameLogic.crossedThresholds(th, 0, 0.6));
        assertEquals(2, GameLogic.crossedThresholds(th, 2, 0.6));
        assertEquals(3, GameLogic.crossedThresholds(th, 2, 1.0));
        assertEquals(0.25, GameLogic.progress(3, 12));
        assertEquals(0.0, GameLogic.progress(0, 0));
    }

    @Test void guardianLockLastsTwoMeetings() {
        int lock = GameLogic.GUARDIAN_LOCK_MEETINGS;
        assertFalse(GameLogic.guardianReady(1, lock));
        lock = GameLogic.afterMeeting(lock);
        assertFalse(GameLogic.guardianReady(1, lock));
        lock = GameLogic.afterMeeting(lock);
        assertTrue(GameLogic.guardianReady(1, lock));
        assertFalse(GameLogic.guardianReady(0, 0));
    }

    @Test void cooldownSecondsRoundUp() {
        assertEquals(0, GameLogic.remainingSeconds(100, 100));
        assertEquals(0, GameLogic.remainingSeconds(90, 100));
        assertEquals(1, GameLogic.remainingSeconds(101, 100));
        assertEquals(3, GameLogic.remainingSeconds(160, 100));
    }

    @Test void willTiersAndBoundaries() {
        assertEquals(GameLogic.WillTier.STEADY, GameLogic.willTier(100));
        assertEquals(GameLogic.WillTier.STEADY, GameLogic.willTier(60));
        assertEquals(GameLogic.WillTier.SHAKEN, GameLogic.willTier(59.9));
        assertEquals(GameLogic.WillTier.SHAKEN, GameLogic.willTier(35));
        assertEquals(GameLogic.WillTier.FRAYING, GameLogic.willTier(34.9));
        assertEquals(GameLogic.WillTier.FRAYING, GameLogic.willTier(15));
        assertEquals(GameLogic.WillTier.BROKEN, GameLogic.willTier(14.9));
        assertEquals(GameLogic.WillTier.BROKEN, GameLogic.willTier(0));
    }

    @Test void willDrainAndRestore() {
        assertEquals(88.0, GameLogic.drainWill(100, 12, false, 0.5, 60), 1e-9);
        assertEquals(94.0, GameLogic.drainWill(100, 12, true, 0.5, 60), 1e-9);
        assertEquals(0.0, GameLogic.drainWill(1, 600, false, 1, 60));
        assertEquals(50.0, GameLogic.drainWill(50, -5, false, 1, 60));
        assertEquals(100.0, GameLogic.restoreWill(80, 35));
        assertEquals(40.0, GameLogic.restoreWill(40, -10));
        assertEquals(45.0, GameLogic.restoreWill(10, 35));
    }

    @Test void willHallucinationDelays() {
        for (int i = 0; i < 500; i++) {
            Random r = new Random(i);
            int f = GameLogic.hallucinationDelayTicks(GameLogic.WillTier.FRAYING, r);
            int b = GameLogic.hallucinationDelayTicks(GameLogic.WillTier.BROKEN, r);
            assertTrue(f >= 200 && f <= 360);
            assertTrue(b >= 80 && b <= 200);
        }
        assertEquals(0, GameLogic.hallucinationDelayTicks(GameLogic.WillTier.STEADY, new Random(1)));
        assertEquals(0, GameLogic.hallucinationDelayTicks(GameLogic.WillTier.SHAKEN, new Random(1)));
    }

    @Test void willNeverAppliesToManhunters() {
        assertTrue(GameLogic.hasWill(true, false, true, true));   // innocent (and the Lantern)
        assertFalse(GameLogic.hasWill(true, true, true, true));   // Manhunter
        assertFalse(GameLogic.hasWill(false, false, true, true)); // ghost
        assertFalse(GameLogic.hasWill(true, false, false, true)); // feature off
        assertFalse(GameLogic.hasWill(true, false, true, false)); // no task stations
    }
}
