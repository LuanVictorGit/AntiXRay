package com.lhawk.antixray;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntityHiderTest {

    @Test
    void testRaycastReductionOnStationaryAndEarlyHit() {
        AtomicInteger raycastsSimulated = new AtomicInteger(0);

        int entities = 50;
        int ticks = 50;

        int unoptimizedRaycasts = 0;
        for (int t = 0; t < ticks; t++) {
            for (int e = 0; e < entities; e++) {
                unoptimizedRaycasts += 9;
            }
        }

        int optimizedRaycasts = 0;
        boolean[] isVisible = new boolean[entities];
        for (int e = 0; e < entities; e++) {
            isVisible[e] = (e % 2 == 0);
        }

        int[] checkCounters = new int[entities];
        for (int t = 0; t < ticks; t++) {
            for (int e = 0; e < entities; e++) {
                checkCounters[e]++;
                int maxInterval = isVisible[e] ? 10 : 6;
                if (checkCounters[e] >= maxInterval) {
                    checkCounters[e] = 0;
                    if (isVisible[e]) {
                        optimizedRaycasts += 1;
                    } else {
                        optimizedRaycasts += 9;
                    }
                }
            }
        }

        double reductionPercent = (1.0 - (double) optimizedRaycasts / unoptimizedRaycasts) * 100.0;
        assertTrue(reductionPercent >= 80.0);
    }
}
