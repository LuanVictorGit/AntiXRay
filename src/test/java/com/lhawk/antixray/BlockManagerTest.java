package com.lhawk.antixray;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockManagerTest {

    @Test
    void testCoordinatePacking() {
        int[] testCoords = {
            0, 0, 0,
            15, -64, 15,
            -100, 320, 250,
            12345, 64, -54321,
            -30000000, -2048, 30000000
        };

        for (int i = 0; i < testCoords.length; i += 3) {
            int x = testCoords[i];
            int y = testCoords[i + 1];
            int z = testCoords[i + 2];

            long packed = BlockManager.pack(x, y, z);
            assertEquals(x, BlockManager.unpackX(packed));
            assertEquals(y, BlockManager.unpackY(packed));
            assertEquals(z, BlockManager.unpackZ(packed));
        }
    }

    @Test
    void testRevealedStateTracking() {
        BlockManager manager = new BlockManager(null);
        UUID uuid = UUID.randomUUID();

        manager.markRevealed(uuid, 10, 20, 30);
        manager.markRevealed(uuid, 11, 20, 30);

        manager.unmarkRevealed(uuid, 10, 20, 30);
        manager.clearPlayerCache(uuid);
        assertEquals(0, manager.getCachedPlayerCount());
    }

    @Test
    void testConcurrentAddAndRevealOperations() throws InterruptedException {
        BlockManager manager = new BlockManager(null);
        UUID uuid = UUID.randomUUID();
        int threadCount = 8;
        int operationsPerThread = 500;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger errorCount = new AtomicInteger(0);

        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < operationsPerThread; i++) {
                        int x = (threadId << 4) + (i & 15);
                        int y = 64 + (i % 30);
                        int z = (threadId << 4) + ((i >> 4) & 15);

                        manager.markRevealed(uuid, x, y, z);
                        if ((i & 1) == 0) {
                            manager.unmarkRevealed(uuid, x, y, z);
                        }
                    }
                } catch (Throwable ex) {
                    errorCount.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean completed = doneLatch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertTrue(completed);
        assertEquals(0, errorCount.get());
    }

    @Test
    void testPerformanceBenchmarkOldVsNewApproach() {
        int count = 2000;
        long[] positions = new long[count];
        for (int i = 0; i < count; i++) {
            positions[i] = BlockManager.pack(i % 16, 64 + (i % 100), (i / 16) % 16);
        }

        long oldStart = System.nanoTime();
        long[] oldArray = null;
        for (int i = 0; i < count; i++) {
            long pos = positions[i];
            boolean exists = false;
            if (oldArray != null) {
                for (long e : oldArray) {
                    if (e == pos) {
                        exists = true;
                        break;
                    }
                }
            }
            if (!exists) {
                long[] next = oldArray == null ? new long[1] : Arrays.copyOf(oldArray, oldArray.length + 1);
                next[next.length - 1] = pos;
                oldArray = next;
            }
        }
        long oldDurationMs = (System.nanoTime() - oldStart) / 1_000_000;

        long newStart = System.nanoTime();
        java.util.Set<Long> newSet = java.util.concurrent.ConcurrentHashMap.newKeySet();
        for (int i = 0; i < count; i++) {
            newSet.add(positions[i]);
        }
        long newDurationMs = (System.nanoTime() - newStart) / 1_000_000;

        assertEquals(oldArray.length, newSet.size());
        assertTrue(newDurationMs <= oldDurationMs + 15);
    }
}
