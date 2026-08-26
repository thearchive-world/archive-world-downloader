// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * The open-click intent, unit-tested MC-free: remember the block or entity the local player just right-clicked so a
 * container menu that opens a beat later binds to that CLICKED target, not to the live crosshair. The crosshair keeps
 * tracking the camera during the click-to-open round trip (no screen is open yet, so the camera is not frozen), so a
 * player who nudges their view while a chest opens would otherwise bind the menu to whatever the crosshair drifted
 * onto, or drop it. This intent latches the click.
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
    private static final long OTHER_POS = 7654321L; // a second, different packed pos
    private static final int ENTITY_ID = 42; // an opaque network entity id
    private static final int OTHER_ENTITY_ID = 43; // a second, different entity id
    private static final int VEHICLE_ID = 77; // an opaque network id for a ridden container vehicle
    private static final int OTHER_VEHICLE_ID = 78; // a second, different vehicle
    private static final long WINDOW = 40L; // the freshness window in ticks

    /** A fresh block click resolves to its pos on the same tick it was recorded. */
    @Test
    void resolvesFreshBlockClick() {
        OpenClickIntent intent = new OpenClickIntent(WINDOW);

        intent.recordBlockClick(POS, 5L);

        assertEquals(OpenClickIntent.Target.BLOCK, intent.resolve(5L), "a fresh block click resolves to BLOCK");
        assertEquals(POS, intent.blockPosKey(), "the resolved block pos is the clicked pos");
    }

    /** A fresh entity click resolves to ENTITY (the entity reference itself lives in the adapter). */
    @Test
    void resolvesFreshEntityClick() {
        OpenClickIntent intent = new OpenClickIntent(WINDOW);

        intent.recordEntityClick(ENTITY_ID, 5L, false);

        assertEquals(OpenClickIntent.Target.ENTITY, intent.resolve(5L), "a fresh entity click resolves to ENTITY");
    }

    /** A superseded marker exactly at the window edge still poisons the open (the window is inclusive). */
    @Test
    void aSupersededMarkerAtTheWindowEdgeStillPoisons() {
        OpenClickIntent intent = new OpenClickIntent(WINDOW);

        intent.recordBlockClick(POS, 0L);
        intent.recordEntityClick(ENTITY_ID, 0L, false); // a different target supersedes the block click

        assertEquals(OpenClickIntent.Target.SUPERSEDED, intent.resolve(WINDOW),
                "a marker exactly WINDOW ticks old is still fresh and poisons this open");
    }

    /** Marker freshness is the age nowTick minus clickTick, not any function of the absolute tick values. */
    @Test
    void aFreshSupersededMarkerSurvivesLargeAbsoluteTicks() {
        OpenClickIntent intent = new OpenClickIntent(WINDOW);

        intent.recordBlockClick(POS, 1000L);
        intent.recordEntityClick(ENTITY_ID, 1000L, false);

        assertEquals(OpenClickIntent.Target.SUPERSEDED, intent.resolve(1001L),
                "a one-tick-old marker poisons the open regardless of how large the tick counter has grown");
    }

    /** A click exactly at the window edge is still fresh (the window is inclusive). */
    @Test
    void resolvesAtTheWindowEdge() {
        OpenClickIntent intent = new OpenClickIntent(WINDOW);

        intent.recordBlockClick(POS, 0L);

        assertEquals(OpenClickIntent.Target.BLOCK, intent.resolve(WINDOW), "a click WINDOW ticks old is still fresh");
    }

    /** A click one tick past the window is stale: the open it would have seeded never arrived -> NONE. */
    @Test
    void dropsStaleClickPastTheWindow() {
        OpenClickIntent intent = new OpenClickIntent(WINDOW);

        intent.recordBlockClick(POS, 0L);

        assertEquals(OpenClickIntent.Target.NONE, intent.resolve(WINDOW + 1),
                "a click older than the window is stale -> NONE");
    }

    /** A click seeds at most one bind: the second resolve of the same click is NONE (take-once). */
    @Test
    void resolveIsTakeOnce() {
        OpenClickIntent intent = new OpenClickIntent(WINDOW);
        intent.recordBlockClick(POS, 5L);

        assertEquals(OpenClickIntent.Target.BLOCK, intent.resolve(5L), "the first open resolves the click");
        assertEquals(OpenClickIntent.Target.NONE, intent.resolve(5L),
                "a consumed click must not seed a second open -> NONE");
    }

    /** With no click recorded, an open falls back (resolve is NONE, so the adapter uses the live crosshair). */
    @Test
    void resolvesToNoneWithoutClick() {
        assertEquals(OpenClickIntent.Target.NONE, new OpenClickIntent(WINDOW).resolve(0L),
                "no recent click -> NONE (the adapter falls back to the live hit result)");
    }

    /**
     * A later click on a different target overwrites an earlier one and supersedes the first in-flight open: the
     * overwritten click's own open may still arrive, and binding it to the latched click would write the wrong block.
     * The next resolve is SUPERSEDED (bind nothing), and only the one after that is the latch.
     */
    @Test
    void laterClickOverwritesEarlier() {
        OpenClickIntent intent = new OpenClickIntent(WINDOW);

        intent.recordBlockClick(POS, 0L);
        intent.recordEntityClick(ENTITY_ID, 1L, false);

        assertEquals(OpenClickIntent.Target.SUPERSEDED, intent.resolve(1L),
                "the overwritten click's open binds nothing");
        assertEquals(OpenClickIntent.Target.ENTITY, intent.resolve(1L), "the latest click (entity) wins");
    }

    /** A later block click overwrites an earlier entity click, and its pos is the one the second open gets. */
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

    /** Two rapid clicks on different blocks: the first open is SUPERSEDED, the second binds the second block. */
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

    /** A burst across three blocks leaves two superseded opens; only the last click's open binds. */
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

    /** Re-clicking the same block is a refresh, not a supersede: an impatient double-click stays bindable. */
    @Test
    void samePosReclickDoesNotSupersede() {
        OpenClickIntent intent = new OpenClickIntent(WINDOW);

        intent.recordBlockClick(POS, 0L);
        intent.recordBlockClick(POS, 5L);

        assertEquals(OpenClickIntent.Target.BLOCK, intent.resolve(5L), "the refreshed click binds its open");
        assertEquals(POS, intent.blockPosKey(), "the refreshed click keeps its pos");
    }

    /** Re-clicking the same entity is a refresh, not a supersede. */
    @Test
    void sameEntityReclickDoesNotSupersede() {
        OpenClickIntent intent = new OpenClickIntent(WINDOW);

        intent.recordEntityClick(ENTITY_ID, 0L, false);
        intent.recordEntityClick(ENTITY_ID, 5L, false);

        assertEquals(OpenClickIntent.Target.ENTITY, intent.resolve(5L), "the refreshed click binds its open");
    }

    /** Clicking a different entity supersedes the first entity's in-flight open. */
    @Test
    void differentEntityClickSupersedes() {
        OpenClickIntent intent = new OpenClickIntent(WINDOW);

        intent.recordEntityClick(ENTITY_ID, 0L, false);
        intent.recordEntityClick(OTHER_ENTITY_ID, 1L, false);

        assertEquals(OpenClickIntent.Target.SUPERSEDED, intent.resolve(1L),
                "the first entity's open must not bind the second entity");
        assertEquals(OpenClickIntent.Target.ENTITY, intent.resolve(1L), "the second open pairs with the latch");
    }

    /** A superseded marker ages out with the window, so it cannot poison an unrelated later open. */
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

    /** SUPERSEDED consumes only the marker: the latched click stays armed for its own open. */
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

    // --- vehicle open intent: the click-less third intent kind. Opening the ridden container vehicle's own
    // menu goes through a server command and fires no use event, so the request the client sends is latched as
    // a VEHICLE intent through the same freshness/take-once/supersede machinery as the clicks. Without it, a
    // stray unconsumed click would pair with the vehicle's open and route it onto the block axis (mis-binding
    // the vehicle's items into a size-coinciding block, or dropping them). The intent names the vehicle,
    // because two container vehicles of the same size are indistinguishable to the bind's slot-count guard.

    /** A fresh vehicle open intent resolves to VEHICLE (the ridden vehicle itself lives in the adapter). */
    @Test
    void resolvesFreshVehicleOpenIntent() {
        OpenClickIntent intent = new OpenClickIntent(WINDOW);

        intent.recordVehicleOpenIntent(VEHICLE_ID, 5L);

        assertEquals(OpenClickIntent.Target.VEHICLE, intent.resolve(5L),
                "a fresh open-inventory request while riding resolves to VEHICLE");
        assertEquals(VEHICLE_ID, intent.vehicleId(), "the resolved intent names the vehicle it was sent for");
    }

    /** A vehicle intent seeds at most one bind: the second resolve is NONE (take-once). */
    @Test
    void vehicleIntentIsTakeOnce() {
        OpenClickIntent intent = new OpenClickIntent(WINDOW);
        intent.recordVehicleOpenIntent(VEHICLE_ID, 5L);

        assertEquals(OpenClickIntent.Target.VEHICLE, intent.resolve(5L), "the first open resolves the intent");
        assertEquals(OpenClickIntent.Target.NONE, intent.resolve(5L),
                "a consumed vehicle intent must not seed a second open -> NONE");
    }

    /** Vehicle-intent freshness keys on the press's own tick, inclusive at the window edge. */
    @Test
    void vehicleIntentStaysFreshAtTheWindowEdge() {
        OpenClickIntent intent = new OpenClickIntent(WINDOW);

        intent.recordVehicleOpenIntent(VEHICLE_ID, 100L);

        assertEquals(OpenClickIntent.Target.VEHICLE, intent.resolve(100L + WINDOW),
                "an intent WINDOW ticks old is still fresh, measured from its own press tick");
    }

    /** clear() drops a pending vehicle intent (the dimension-rebind and respawn cases). */
    @Test
    void clearDropsPendingVehicleIntent() {
        OpenClickIntent intent = new OpenClickIntent(WINDOW);
        intent.recordVehicleOpenIntent(VEHICLE_ID, 5L);

        intent.clear();

        assertEquals(OpenClickIntent.Target.NONE, intent.resolve(5L),
                "a cleared vehicle intent must not seed an open");
    }

    /** A vehicle intent older than the window is stale: its open never arrived -> NONE. */
    @Test
    void dropsStaleVehicleIntentPastTheWindow() {
        OpenClickIntent intent = new OpenClickIntent(WINDOW);

        intent.recordVehicleOpenIntent(VEHICLE_ID, 0L);

        assertEquals(OpenClickIntent.Target.NONE, intent.resolve(WINDOW + 1),
                "a vehicle intent older than the window is stale -> NONE");
    }

    /**
     * A vehicle intent supersedes an earlier unconsumed click: the click's own open may still be in flight and must not
     * bind (it would take the vehicle's claim or its own stale target); the vehicle's open is the one after.
     */
    @Test
    void vehicleIntentSupersedesEarlierClick() {
        OpenClickIntent intent = new OpenClickIntent(WINDOW);

        intent.recordBlockClick(POS, 0L);
        intent.recordVehicleOpenIntent(VEHICLE_ID, 1L);

        assertEquals(OpenClickIntent.Target.SUPERSEDED, intent.resolve(1L),
                "the overwritten click's open binds nothing");
        assertEquals(OpenClickIntent.Target.VEHICLE, intent.resolve(1L), "the vehicle's own open pairs with the latch");
    }

    /** A later click supersedes an unconsumed vehicle intent (the vehicle's open binds nothing). */
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

    /**
     * A second request for the SAME vehicle is a refresh, not a supersede (close with escape and ask again, or one held
     * key draining into several requests).
     */
    @Test
    void repeatedVehicleIntentDoesNotSupersede() {
        OpenClickIntent intent = new OpenClickIntent(WINDOW);

        intent.recordVehicleOpenIntent(VEHICLE_ID, 0L);
        intent.recordVehicleOpenIntent(VEHICLE_ID, 5L);

        assertEquals(OpenClickIntent.Target.VEHICLE, intent.resolve(5L), "the refreshed intent binds its open");
        assertEquals(OpenClickIntent.Target.NONE, intent.resolve(5L), "and it is still take-once");
    }

    /**
     * A request for a DIFFERENT vehicle supersedes the earlier one: the first vehicle's menu may still be in flight,
     * and pairing it with the second latch would write the first vehicle's contents onto the second.
     */
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

    // --- dismissEntityClick: a use click on an entity that ends in MOUNTING it (entering a chest boat,
    // riding up a donkey) consumed the interact as a ride, so no menu can ever arrive for it; left
    // latched it would supersede the next real intent and poison that open. The adapter dismisses it as
    // soon as the clicked entity becomes the player's vehicle.

    /** A pending click on the entity the player then mounted is dismissed (no open is owed to it). */
    @Test
    void dismissDropsPendingClickOnTheMountedEntity() {
        OpenClickIntent intent = new OpenClickIntent(WINDOW);
        intent.recordEntityClick(ENTITY_ID, 5L, false);

        intent.dismissEntityClick(ENTITY_ID);

        assertEquals(OpenClickIntent.Target.NONE, intent.resolve(5L),
                "a click consumed by mounting must not seed an open");
    }

    /** Dismissal is entity-precise: a pending click on a different entity stays latched. */
    @Test
    void dismissKeepsPendingClickOnAnotherEntity() {
        OpenClickIntent intent = new OpenClickIntent(WINDOW);
        intent.recordEntityClick(ENTITY_ID, 5L, false);

        intent.dismissEntityClick(OTHER_ENTITY_ID);

        assertEquals(OpenClickIntent.Target.ENTITY, intent.resolve(5L),
                "a click on a different entity is still owed its open");
    }

    /** Dismissal never touches a pending block click or a vehicle intent. */
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

    /** Dismissal is kind-precise: a block click recorded after an entity click keeps its latch. */
    @Test
    void dismissKeepsBlockClickRecordedAfterEntityClick() {
        OpenClickIntent intent = new OpenClickIntent(WINDOW);
        intent.recordEntityClick(ENTITY_ID, 0L, false); // leaves the entity id set
        intent.recordBlockClick(POS, 1L); // overwrites the pending to BLOCK

        intent.dismissEntityClick(ENTITY_ID);

        assertEquals(OpenClickIntent.Target.SUPERSEDED, intent.resolve(1L),
                "the overwritten entity click's marker still drains first");
        assertEquals(OpenClickIntent.Target.BLOCK, intent.resolve(1L),
                "the pending block click must survive a dismissal that matches only the stale entity id");
        assertEquals(POS, intent.blockPosKey(), "the block click keeps its pos");
    }

    /** Dismissal leaves superseded markers untouched (they may be owed to genuinely in-flight opens). */
    @Test
    void dismissLeavesSupersededMarkers() {
        OpenClickIntent intent = new OpenClickIntent(WINDOW);
        intent.recordBlockClick(POS, 0L);
        intent.recordEntityClick(ENTITY_ID, 1L, false); // supersedes the block click -> one marker

        intent.dismissEntityClick(ENTITY_ID);

        assertEquals(OpenClickIntent.Target.SUPERSEDED, intent.resolve(1L),
                "the overwritten block click's marker still poisons its one open");
        assertEquals(OpenClickIntent.Target.NONE, intent.resolve(1L),
                "the dismissed entity click itself is gone");
    }

    /** The mount-then-open-vehicle flow: the entry click is dismissed, so press-E binds cleanly. */
    @Test
    void dismissThenVehicleIntentDoesNotSupersede() {
        OpenClickIntent intent = new OpenClickIntent(WINDOW);
        intent.recordEntityClick(ENTITY_ID, 0L, false); // the boat-entry click
        intent.dismissEntityClick(ENTITY_ID); // the boat became the vehicle

        intent.recordVehicleOpenIntent(VEHICLE_ID, 5L); // the open-inventory request goes out

        assertEquals(OpenClickIntent.Target.VEHICLE, intent.resolve(5L),
                "no marker was minted, so the boat's own open binds on the first press");
    }

    /** A stale resolve consumes the click too, so a following resolve stays NONE (no resurrection). */
    @Test
    void staleResolveClearsTheClick() {
        OpenClickIntent intent = new OpenClickIntent(WINDOW);
        intent.recordBlockClick(POS, 0L);

        assertEquals(OpenClickIntent.Target.NONE, intent.resolve(WINDOW + 1), "the click is stale -> NONE");
        assertEquals(OpenClickIntent.Target.NONE, intent.resolve(0L),
                "a stale-resolved click is cleared and cannot resurrect on an earlier tick");
    }

    /** clear() drops a pending click without acting on it, so a following resolve is NONE (the rebind case). */
    @Test
    void clearDropsPendingClick() {
        OpenClickIntent intent = new OpenClickIntent(WINDOW);
        intent.recordBlockClick(POS, 5L);

        intent.clear();

        assertEquals(OpenClickIntent.Target.NONE, intent.resolve(5L),
                "a cleared click must not seed an open, even on the same tick it was recorded");
    }

    /** clear() drops superseded markers too, so an old dimension's burst cannot poison the new one's opens. */
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

    /** A menu-incapable entity click, overwritten by a block click, mints NO marker: the block open binds. */
    @Test
    void incapableEntityLeavesNoMarker() {
        OpenClickIntent intent = new OpenClickIntent(WINDOW);
        intent.recordEntityClick(ENTITY_ID, 0L, true); // menu-incapable (a pig / nitwit)
        intent.recordBlockClick(POS, 1L);
        assertEquals(OpenClickIntent.Target.BLOCK, intent.resolve(1L),
                "an incapable entity owes no open, so the next container must bind");
        assertEquals(POS, intent.blockPosKey());
    }

    /** A menu-CAPABLE entity click still mints its marker on overwrite (over-suppression guard). */
    @Test
    void capableEntityStillMintsMarker() {
        OpenClickIntent intent = new OpenClickIntent(WINDOW);
        intent.recordEntityClick(ENTITY_ID, 0L, false); // a chest minecart / employed villager
        intent.recordBlockClick(POS, 1L);
        assertEquals(OpenClickIntent.Target.SUPERSEDED, intent.resolve(1L),
                "a capable entity's in-flight open must still be dropped");
        assertEquals(OpenClickIntent.Target.BLOCK, intent.resolve(1L));
    }

    /** Suppression keys on the OVERWRITTEN pending: an incapable entity over a vehicle intent still marks it. */
    @Test
    void incapableEntityOverVehicleStillMintsVehicleMarker() {
        OpenClickIntent intent = new OpenClickIntent(WINDOW);
        intent.recordVehicleOpenIntent(VEHICLE_ID, 0L);
        intent.recordEntityClick(ENTITY_ID, 1L, true); // incapable, overwrites the vehicle intent
        assertEquals(OpenClickIntent.Target.SUPERSEDED, intent.resolve(1L),
                "the overwritten vehicle intent's open must still be dropped");
        assertEquals(OpenClickIntent.Target.ENTITY, intent.resolve(1L));
    }
}
