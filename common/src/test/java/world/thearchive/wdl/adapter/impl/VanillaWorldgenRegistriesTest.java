// Copyright (C) Archive World Downloader contributors
// SPDX-License-Identifier: LGPL-3.0-or-later

package world.thearchive.wdl.adapter.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.RegistryAccess;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import world.thearchive.wdl.testsupport.TestRegistries;

/**
 * The memoized worldgen reconstruction and the cross-thread publication the warm rests on. The load is real (the
 * bundled vanilla data pack on the test classpath); the failing and latching cases substitute a test loader through the
 * reset seam, since the real decode can neither be made to throw nor be stalled mid-flight.
 */
class VanillaWorldgenRegistriesTest {
    private static RegistryAccess.Frozen real;

    @BeforeAll
    static void reconstructOnce() {
        // Bind the vanilla item tags onto BuiltInRegistries first: this MC version's WORLDGEN load decodes the
        // enchantment registry, whose JSONs resolve item tags (enchantable/*). A connected client always has them
        // bound; headless it does not, so prime them exactly as a real client would before the reconstruction.
        TestRegistries.frozen();
        VanillaWorldgenRegistries.resetForTesting();
        real = VanillaWorldgenRegistries.get();
    }

    @BeforeEach
    void coldMemo() {
        VanillaWorldgenRegistries.resetForTesting();
    }

    @AfterEach
    void restoreRealLoader() {
        VanillaWorldgenRegistries.resetForTesting();
    }

    @Test
    void firstGetLoadsOnceAndLaterGetsCache() {
        RegistryAccess.Frozen first = VanillaWorldgenRegistries.get();
        assertEquals(1, VanillaWorldgenRegistries.loadCountForTesting(), "the cold memo loads on the first get");

        RegistryAccess.Frozen second = VanillaWorldgenRegistries.get();
        assertEquals(1, VanillaWorldgenRegistries.loadCountForTesting(), "a warm memo never re-loads");
        assertSame(first, second);
    }

    @Test
    void latchedLoadOnAnotherThreadPublishesTheFrozenSetThroughOneLoad() throws InterruptedException {
        CountDownLatch inLoad = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        VanillaWorldgenRegistries.resetForTesting(() -> {
            inLoad.countDown();
            await(release);
            return real;
        });

        AtomicReference<RegistryAccess.Frozen> onWorker = new AtomicReference<>();
        Thread worker = new Thread(() -> onWorker.set(VanillaWorldgenRegistries.get()), "warm-under-test");
        worker.start();
        inLoad.await(); // the worker now holds the memo monitor, stalled mid-load

        // Free the worker slightly late so this thread is already blocking on the synchronized get, exercising the
        // publication edge rather than a plain cache hit. Correctness does not depend on the exact interleaving.
        Thread releaser = new Thread(() -> {
            sleep(100);
            release.countDown();
        }, "warm-release");
        releaser.start();

        RegistryAccess.Frozen onMain = VanillaWorldgenRegistries.get();
        worker.join();
        releaser.join();

        assertSame(real, onMain, "the main-thread get sees the fully-frozen set the worker published");
        assertSame(real, onWorker.get());
        assertEquals(1, VanillaWorldgenRegistries.loadCountForTesting(), "the two racing gets load exactly once");
    }

    @Test
    void aThrownLoadLeavesTheMemoNullSoTheNextGetReattempts() {
        AtomicInteger attempts = new AtomicInteger();
        VanillaWorldgenRegistries.resetForTesting(() -> {
            if (attempts.getAndIncrement() == 0) {
                throw new IllegalStateException("reconstruction failed");
            }
            return real;
        });

        assertThrows(IllegalStateException.class, VanillaWorldgenRegistries::get);
        assertEquals(1, VanillaWorldgenRegistries.loadCountForTesting());

        RegistryAccess.Frozen recovered = VanillaWorldgenRegistries.get();
        assertSame(real, recovered, "a failed load never poisons the memo; the next get re-attempts cleanly");
        assertEquals(2, VanillaWorldgenRegistries.loadCountForTesting());
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
