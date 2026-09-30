// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 *
 * <p>The decision is a freshness window plus take-once: a click older than the window is stale (the open it would have
 * seeded never arrived), and a click is consumed by the first open that resolves it (so one click can seed at most one
 * bind). A later click on a DIFFERENT target overwrites an earlier unconsumed one (last-click-wins) and leaves a
 * superseded marker: the overwritten click's own open may still be in flight, and pairing it with the latched click
 * would bind the wrong block (the first open would claim the second click's target), so each marker poisons exactly one
 * later open into SUPERSEDED, which binds nothing at all. All inputs are primitives (a packed
 * {@code BlockPos.toLong()}, an entity id, and a tick), so the whole decision is verified with hand-fed ticks and no
 * running game; the clicked entity reference for an ENTITY intent lives in the adapter, the way
 * {@link ContainerAssociation} keeps the bound entity UUID there.
 */
class OpenClickIntentTest {
    private static final long POS = 1234567L; // an opaque packed BlockPos.toLong()
    private static final long OTHER_POS = 7654321L;
    private static final int ENTITY_ID = 42;
    private static final int OTHER_ENTITY_ID = 43;
    private static final int VEHICLE_ID = 77;
    private static final int OTHER_VEHICLE_ID = 78;
    private static final long WINDOW = 40L;

    @Test
    void resolvesFreshBlockClick() {
        OpenClickIntent intent = new OpenClickIntent(WINDOW);

        intent.recordBlockClick(POS, 5L);

        assertEquals(OpenClickIntent.Target.BLOCK, intent.resolve(5L), "a fresh block click resolves to BLOCK");
        assertEquals(POS, intent.blockPosKey(), "the resolved block pos is the clicked pos");
    }

    @Test
    void resolvesFreshEntityClick() {
        OpenClickIntent intent = new OpenClickIntent(WINDOW);

        intent.recordEntityClick(ENTITY_ID, 5L, false);

        assertEquals(OpenClickIntent.Target.ENTITY, intent.resolve(5L), "a fresh entity click resolves to ENTITY");
    }

    @Test
    void aSupersededMarkerAtTheWindowEdgeStillPoisons() {
        OpenClickIntent intent = new OpenClickIntent(WINDOW);

        intent.recordBlockClick(POS, 0L);
        intent.recordEntityClick(ENTITY_ID, 0L, false);

        assertEquals(OpenClickIntent.Target.SUPERSEDED, intent.resolve(WINDOW),
                "a marker exactly WINDOW ticks old is still fresh and poisons this open");
    }

    @Test
    void aFreshSupersededMarkerSurvivesLargeAbsoluteTicks() {
        OpenClickIntent intent = new OpenClickIntent(WINDOW);

        intent.recordBlockClick(POS, 1000L);
        intent.recordEntityClick(ENTITY_ID, 1000L, false);

        assertEquals(OpenClickIntent.Target.SUPERSEDED, intent.resolve(1001L),
                "a one-tick-old marker poisons the open regardless of how large the tick counter has grown");
    }

    @Test
    void resolvesAtTheWindowEdge() {
        OpenClickIntent intent = new OpenClickIntent(WINDOW);

        intent.recordBlockClick(POS, 0L);

        assertEquals(OpenClickIntent.Target.BLOCK, intent.resolve(WINDOW), "a click WINDOW ticks old is still fresh");
    }

    @Test
    void dropsStaleClickPastTheWindow() {
        OpenClickIntent intent = new OpenClickIntent(WINDOW);

        intent.recordBlockClick(POS, 0L);

        assertEquals(OpenClickIntent.Target.NONE, intent.resolve(WINDOW + 1),
                "a click older than the window is stale -> NONE");
    }

    @Test
    void resolveIsTakeOnce() {
        OpenClickIntent intent = new OpenClickIntent(WINDOW);
        intent.recordBlockClick(POS, 5L);

        assertEquals(OpenClickIntent.Target.BLOCK, intent.resolve(5L), "the first open resolves the click");
        assertEquals(OpenClickIntent.Target.NONE, intent.resolve(5L),
                "a consumed click must not seed a second open -> NONE");
    }

    @Test
    void resolvesToNoneWithoutClick() {
        assertEquals(OpenClickIntent.Target.NONE, new OpenClickIntent(WINDOW).resolve(0L),
                "no recent click -> NONE (the adapter falls back to the live hit result)");
    }

    @Test
    void laterClickOverwritesEarlier() {
        OpenClickIntent intent = new OpenClickIntent(WINDOW);

        intent.recordBlockClick(POS, 0L);
        intent.recordEntityClick(ENTITY_ID, 1L, false);

        assertEquals(OpenClickIntent.Target.SUPERSEDED, intent.resolve(1L),
                "the overwritten click's open binds nothing");
        assertEquals(OpenClickIntent.Target.ENTITY, intent.resolve(1L), "the latest click (entity) wins");
    }

    @Test
    void laterBlockClickOverwritesEarlierEntityClick() {
        OpenClickIntent intent = new OpenClickIntent(WINDOW);

        intent.recordEntityClick(ENTITY_ID, 0L, false);
        intent.recordBlockClick(POS, 1L);

        assertEquals(OpenClickIntent.Target.SUPERSEDED, intent.resolve(1L),
                "the overwritten click's open binds nothing");
        assertEquals(OpenClickIntent.Target.BLOCK, intent.resolve(1L), "the latest click (block) wins");
        assertEquals(POS, intent.blockPosKey(), "the resolved pos is the latest block click's pos");
    }

    @Test
    void rapidClicksOnTwoBlocksSupersedeTheFirstOpen() {
        OpenClickIntent intent = new OpenClickIntent(WINDOW);

        intent.recordBlockClick(POS, 0L);
        intent.recordBlockClick(OTHER_POS, 1L);

        assertEquals(OpenClickIntent.Target.SUPERSEDED, intent.resolve(2L),
                "the first chest's open must not bind the second chest");
        assertEquals(OpenClickIntent.Target.BLOCK, intent.resolve(2L), "the second open pairs with the latch");
        assertEquals(OTHER_POS, intent.blockPosKey(), "the second open binds the second block");
    }

    @Test
    void clickBurstSupersedesAllButTheLastOpen() {
        OpenClickIntent intent = new OpenClickIntent(WINDOW);

        intent.recordBlockClick(POS, 0L);
        intent.recordBlockClick(OTHER_POS, 1L);
        intent.recordBlockClick(POS, 2L);

        assertEquals(OpenClickIntent.Target.SUPERSEDED, intent.resolve(3L), "first open: superseded");
        assertEquals(OpenClickIntent.Target.SUPERSEDED, intent.resolve(3L), "second open: superseded");
        assertEquals(OpenClickIntent.Target.BLOCK, intent.resolve(3L), "third open: the latch");
        assertEquals(POS, intent.blockPosKey(), "the last click's pos wins");
    }

    @Test
    void samePosReclickDoesNotSupersede() {
        OpenClickIntent intent = new OpenClickIntent(WINDOW);

        intent.recordBlockClick(POS, 0L);
        intent.recordBlockClick(POS, 5L);

        assertEquals(OpenClickIntent.Target.BLOCK, intent.resolve(5L), "the refreshed click binds its open");
        assertEquals(POS, intent.blockPosKey(), "the refreshed click keeps its pos");
    }

    @Test
    void sameEntityReclickDoesNotSupersede() {
        OpenClickIntent intent = new OpenClickIntent(WINDOW);

        intent.recordEntityClick(ENTITY_ID, 0L, false);
        intent.recordEntityClick(ENTITY_ID, 5L, false);

        assertEquals(OpenClickIntent.Target.ENTITY, intent.resolve(5L), "the refreshed click binds its open");
    }

    @Test
    void differentEntityClickSupersedes() {
        OpenClickIntent intent = new OpenClickIntent(WINDOW);

        intent.recordEntityClick(ENTITY_ID, 0L, false);
        intent.recordEntityClick(OTHER_ENTITY_ID, 1L, false);

        assertEquals(OpenClickIntent.Target.SUPERSEDED, intent.resolve(1L),
                "the first entity's open must not bind the second entity");
        assertEquals(OpenClickIntent.Target.ENTITY, intent.resolve(1L), "the second open pairs with the latch");
    }

    @Test
    void supersededMarkerExpiresWithTheWindow() {
        OpenClickIntent intent = new OpenClickIntent(WINDOW);
        intent.recordBlockClick(POS, 0L);
        intent.recordBlockClick(OTHER_POS, 1L);

        intent.recordBlockClick(OTHER_POS, WINDOW + 10L);

        assertEquals(OpenClickIntent.Target.BLOCK, intent.resolve(WINDOW + 10L),
                "an expired marker must not poison a fresh unrelated open");
        assertEquals(OTHER_POS, intent.blockPosKey(), "the fresh click binds normally");
    }

    @Test
    void supersededResolveKeepsTheLatch() {
        OpenClickIntent intent = new OpenClickIntent(WINDOW);
        intent.recordBlockClick(POS, 0L);
        intent.recordBlockClick(OTHER_POS, 1L);

        assertEquals(OpenClickIntent.Target.SUPERSEDED, intent.resolve(10L), "marker consumed first");
        assertEquals(OpenClickIntent.Target.BLOCK, intent.resolve(WINDOW + 1L),
                "the latch stays fresh relative to its own click tick");
        assertEquals(OTHER_POS, intent.blockPosKey(), "the latch pos survives the superseded resolve");
    }

    @Test
    void resolvesFreshVehicleOpenIntent() {
        OpenClickIntent intent = new OpenClickIntent(WINDOW);

        intent.recordVehicleOpenIntent(VEHICLE_ID, 5L);

        assertEquals(OpenClickIntent.Target.VEHICLE, intent.resolve(5L),
                "a fresh open-inventory request while riding resolves to VEHICLE");
        assertEquals(VEHICLE_ID, intent.vehicleId(), "the resolved intent names the vehicle it was sent for");
    }

    @Test
    void vehicleIntentIsTakeOnce() {
        OpenClickIntent intent = new OpenClickIntent(WINDOW);
        intent.recordVehicleOpenIntent(VEHICLE_ID, 5L);

        assertEquals(OpenClickIntent.Target.VEHICLE, intent.resolve(5L), "the first open resolves the intent");
        assertEquals(OpenClickIntent.Target.NONE, intent.resolve(5L),
                "a consumed vehicle intent must not seed a second open -> NONE");
    }

    @Test
    void vehicleIntentStaysFreshAtTheWindowEdge() {
        OpenClickIntent intent = new OpenClickIntent(WINDOW);

        intent.recordVehicleOpenIntent(VEHICLE_ID, 100L);

        assertEquals(OpenClickIntent.Target.VEHICLE, intent.resolve(100L + WINDOW),
                "an intent WINDOW ticks old is still fresh, measured from its own press tick");
    }

    @Test
    void clearDropsPendingVehicleIntent() {
        OpenClickIntent intent = new OpenClickIntent(WINDOW);
        intent.recordVehicleOpenIntent(VEHICLE_ID, 5L);

        intent.clear();

        assertEquals(OpenClickIntent.Target.NONE, intent.resolve(5L), "a cleared vehicle intent must not seed an open");
    }

    @Test
    void dropsStaleVehicleIntentPastTheWindow() {
        OpenClickIntent intent = new OpenClickIntent(WINDOW);

        intent.recordVehicleOpenIntent(VEHICLE_ID, 0L);

        assertEquals(OpenClickIntent.Target.NONE, intent.resolve(WINDOW + 1),
                "a vehicle intent older than the window is stale -> NONE");
    }

    @Test
    void vehicleIntentSupersedesEarlierClick() {
        OpenClickIntent intent = new OpenClickIntent(WINDOW);

        intent.recordBlockClick(POS, 0L);
        intent.recordVehicleOpenIntent(VEHICLE_ID, 1L);

        assertEquals(OpenClickIntent.Target.SUPERSEDED, intent.resolve(1L),
                "the overwritten click's open binds nothing");
        assertEquals(OpenClickIntent.Target.VEHICLE, intent.resolve(1L), "the vehicle's own open pairs with the latch");
    }

    @Test
    void laterClickSupersedesVehicleIntent() {
        OpenClickIntent intent = new OpenClickIntent(WINDOW);

        intent.recordVehicleOpenIntent(VEHICLE_ID, 0L);
        intent.recordBlockClick(POS, 1L);

        assertEquals(OpenClickIntent.Target.SUPERSEDED, intent.resolve(1L),
                "the overwritten vehicle intent's open binds nothing");
        assertEquals(OpenClickIntent.Target.BLOCK, intent.resolve(1L), "the click's open pairs with the latch");
        assertEquals(POS, intent.blockPosKey(), "the resolved pos is the clicked pos");
    }

    @Test
    void repeatedVehicleIntentDoesNotSupersede() {
        OpenClickIntent intent = new OpenClickIntent(WINDOW);

        intent.recordVehicleOpenIntent(VEHICLE_ID, 0L);
        intent.recordVehicleOpenIntent(VEHICLE_ID, 5L);

        assertEquals(OpenClickIntent.Target.VEHICLE, intent.resolve(5L), "the refreshed intent binds its open");
        assertEquals(OpenClickIntent.Target.NONE, intent.resolve(5L), "and it is still take-once");
    }

    @Test
    void differentVehicleSupersedesEarlierVehicleIntent() {
        OpenClickIntent intent = new OpenClickIntent(WINDOW);

        intent.recordVehicleOpenIntent(VEHICLE_ID, 0L);
        intent.recordVehicleOpenIntent(OTHER_VEHICLE_ID, 5L);

        assertEquals(OpenClickIntent.Target.SUPERSEDED, intent.resolve(5L),
                "the overwritten vehicle's open binds nothing");
        assertEquals(OpenClickIntent.Target.VEHICLE, intent.resolve(5L),
                "the second vehicle's open pairs with the latch");
        assertEquals(OTHER_VEHICLE_ID, intent.vehicleId(), "and it names the second vehicle, not the first");
    }

    @Test
    void dismissDropsPendingClickOnTheMountedEntity() {
        OpenClickIntent intent = new OpenClickIntent(WINDOW);
        intent.recordEntityClick(ENTITY_ID, 5L, false);

        intent.dismissEntityClick(ENTITY_ID);

        assertEquals(OpenClickIntent.Target.NONE, intent.resolve(5L),
                "a click consumed by mounting must not seed an open");
    }

    @Test
    void dismissKeepsPendingClickOnAnotherEntity() {
        OpenClickIntent intent = new OpenClickIntent(WINDOW);
        intent.recordEntityClick(ENTITY_ID, 5L, false);

        intent.dismissEntityClick(OTHER_ENTITY_ID);

        assertEquals(OpenClickIntent.Target.ENTITY, intent.resolve(5L),
                "a click on a different entity is still owed its open");
    }

    @Test
    void dismissKeepsNonEntityIntents() {
        OpenClickIntent blockIntent = new OpenClickIntent(WINDOW);
        blockIntent.recordBlockClick(POS, 5L);
        blockIntent.dismissEntityClick(ENTITY_ID);
        assertEquals(OpenClickIntent.Target.BLOCK, blockIntent.resolve(5L),
                "a pending block click survives an entity dismissal");

        OpenClickIntent vehicleIntent = new OpenClickIntent(WINDOW);
        vehicleIntent.recordVehicleOpenIntent(VEHICLE_ID, 5L);
        vehicleIntent.dismissEntityClick(ENTITY_ID);
        assertEquals(OpenClickIntent.Target.VEHICLE, vehicleIntent.resolve(5L),
                "a pending vehicle intent survives an entity dismissal");
    }

    @Test
    void dismissKeepsBlockClickRecordedAfterEntityClick() {
        OpenClickIntent intent = new OpenClickIntent(WINDOW);
        intent.recordEntityClick(ENTITY_ID, 0L, false);
        intent.recordBlockClick(POS, 1L);

        intent.dismissEntityClick(ENTITY_ID);

        assertEquals(OpenClickIntent.Target.SUPERSEDED, intent.resolve(1L),
                "the overwritten entity click's marker still drains first");
        assertEquals(OpenClickIntent.Target.BLOCK, intent.resolve(1L),
                "the pending block click must survive a dismissal that matches only the stale entity id");
        assertEquals(POS, intent.blockPosKey(), "the block click keeps its pos");
    }

    @Test
    void dismissLeavesSupersededMarkers() {
        OpenClickIntent intent = new OpenClickIntent(WINDOW);
        intent.recordBlockClick(POS, 0L);
        intent.recordEntityClick(ENTITY_ID, 1L, false);

        intent.dismissEntityClick(ENTITY_ID);

        assertEquals(OpenClickIntent.Target.SUPERSEDED, intent.resolve(1L),
                "the overwritten block click's marker still poisons its one open");
        assertEquals(OpenClickIntent.Target.NONE, intent.resolve(1L), "the dismissed entity click itself is gone");
    }

    @Test
    void dismissThenVehicleIntentDoesNotSupersede() {
        OpenClickIntent intent = new OpenClickIntent(WINDOW);
        intent.recordEntityClick(ENTITY_ID, 0L, false);
        intent.dismissEntityClick(ENTITY_ID);

        intent.recordVehicleOpenIntent(VEHICLE_ID, 5L);

        assertEquals(OpenClickIntent.Target.VEHICLE, intent.resolve(5L),
                "no marker was minted, so the vehicle's own open binds on the first press");
    }

    @Test
    void staleResolveClearsTheClick() {
        OpenClickIntent intent = new OpenClickIntent(WINDOW);
        intent.recordBlockClick(POS, 0L);

        assertEquals(OpenClickIntent.Target.NONE, intent.resolve(WINDOW + 1), "the click is stale -> NONE");
        assertEquals(OpenClickIntent.Target.NONE, intent.resolve(0L),
                "a stale-resolved click is cleared and cannot resurrect on an earlier tick");
    }

    @Test
    void clearDropsPendingClick() {
        OpenClickIntent intent = new OpenClickIntent(WINDOW);
        intent.recordBlockClick(POS, 5L);

        intent.clear();

        assertEquals(OpenClickIntent.Target.NONE, intent.resolve(5L),
                "a cleared click must not seed an open, even on the same tick it was recorded");
    }

    @Test
    void clearDropsSupersededMarkers() {
        OpenClickIntent intent = new OpenClickIntent(WINDOW);
        intent.recordBlockClick(POS, 0L);
        intent.recordBlockClick(OTHER_POS, 1L);

        intent.clear();
        intent.recordBlockClick(POS, 2L);

        assertEquals(OpenClickIntent.Target.BLOCK, intent.resolve(2L),
                "a cleared marker must not poison a fresh open after the reset");
    }

    @Test
    void incapableEntityLeavesNoMarker() {
        OpenClickIntent intent = new OpenClickIntent(WINDOW);
        intent.recordEntityClick(ENTITY_ID, 0L, true);
        intent.recordBlockClick(POS, 1L);
        assertEquals(OpenClickIntent.Target.BLOCK, intent.resolve(1L),
                "an incapable entity owes no open, so the next container must bind");
        assertEquals(POS, intent.blockPosKey());
    }

    @Test
    void capableEntityStillMintsMarker() {
        OpenClickIntent intent = new OpenClickIntent(WINDOW);
        intent.recordEntityClick(ENTITY_ID, 0L, false);
        intent.recordBlockClick(POS, 1L);
        assertEquals(OpenClickIntent.Target.SUPERSEDED, intent.resolve(1L),
                "a capable entity's in-flight open must still be dropped");
        assertEquals(OpenClickIntent.Target.BLOCK, intent.resolve(1L));
    }

    @Test
    void incapableEntityOverVehicleStillMintsVehicleMarker() {
        OpenClickIntent intent = new OpenClickIntent(WINDOW);
        intent.recordVehicleOpenIntent(VEHICLE_ID, 0L);
        intent.recordEntityClick(ENTITY_ID, 1L, true);
        assertEquals(OpenClickIntent.Target.SUPERSEDED, intent.resolve(1L),
                "the overwritten vehicle intent's open must still be dropped");
        assertEquals(OpenClickIntent.Target.ENTITY, intent.resolve(1L));
    }
}
