package com.moakiee.thunderbolt.core.planner;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Seed-ordered request cones skip the linear backbone for feasibility, but a compression DAG
 * inside such a cone must still degrade to the aggregated leaf diagnosis when the recursive
 * search runs out of budget — not to the search tail's craftable intermediates.
 *
 * <p>Trigger for seed-ordered here: the target consumes an ordinary returned catalyst "cat"
 * which is also a reachable byproduct (core16k emits it), flipping
 * {@code requiresSeedOrderedPlanning}. The rest of the graph is the x=169 compression network
 * (see {@link Fib169ReproTest}).
 */
class SeedOrderedCompressionDiagnosisTest {

    private static final String[] TIERS = {
            "256m", "64m", "16m", "4m", "1m", "256k", "64k", "16k", "4k", "1k" };

    private static void ladder(CraftGraph.Builder<String> b, String prefix, String skipTier) {
        for (int i = 0; i + 2 < TIERS.length; i++) {
            if (TIERS[i].equals(skipTier)) {
                continue;
            }
            b.pattern(prefix + TIERS[i], 1, List.of(
                    CraftInput.of(prefix + TIERS[i + 1], 1),
                    CraftInput.of(prefix + TIERS[i + 2], 1)));
        }
    }

    @Test
    void exhaustedSearchInSeedOrderedConeStillReportsLeafDiagnosis() {
        CraftGraph.Builder<String> b = CraftGraph.builder();
        ladder(b, "q", null);
        b.pattern("q4k", 1, List.of(CraftInput.of("c256m", 1), CraftInput.of("q1k", 1)));
        b.pattern("q1k", 1, List.of(CraftInput.of("c256m", 1), CraftInput.of("c64m", 1)));
        ladder(b, "c", null);
        b.pattern("c4k", 1, List.of(CraftInput.of("o256m", 1), CraftInput.of("c1k", 1)));
        b.pattern("c1k", 1, List.of(CraftInput.of("o256m", 1), CraftInput.of("o64m", 1)));
        ladder(b, "o", "64k");
        b.pattern("o4k", 1, List.of(CraftInput.of("core256m", 1), CraftInput.of("o1k", 1)));
        b.pattern("o1k", 1, List.of(CraftInput.of("core256m", 1), CraftInput.of("core64m", 1)));
        // core ladder, but core16k additionally emits the catalyst as a byproduct
        for (int i = 0; i + 2 < TIERS.length; i++) {
            if (TIERS[i].equals("16k")) {
                b.pattern(new CraftPattern<>("core16k", 1,
                        List.of(CraftInput.of("core4k", 1), CraftInput.of("core1k", 1)),
                        List.of(CraftOutput.of("cat", 1)),
                        "core16k-src"));
            } else {
                b.pattern("core" + TIERS[i], 1, List.of(
                        CraftInput.of("core" + TIERS[i + 1], 1),
                        CraftInput.of("core" + TIERS[i + 2], 1)));
            }
        }
        b.pattern("T", 1, List.of(CraftInput.of("q256m", 1), CraftInput.returned("cat", 1)));
        b.stock("cat", 1);
        CraftGraph<String> g = b.build();

        assertTimeoutPreemptively(Duration.ofSeconds(60), () -> {
            PlanningResult<String> result = CraftPlannerV2.planDetailed(g, "T", 1);
            CraftPlan<String> plan = result.plan();

            assertTrue(result.diagnostics().seedOrdered(), "probe must exercise the seed-ordered path");
            assertFalse(plan.feasible());
            assertTrue(plan.budgetExhausted(), "the recursive search must have run out of budget");
            for (String key : plan.missing().keySet()) {
                assertTrue(g.patternsFor(key).isEmpty(),
                        "craftable intermediate misreported as missing: " + key);
            }
            assertEquals(196_418L, plan.missing().get("o64k"));
            assertEquals(17_480_592L, plan.missing().get("core4k"));
            assertEquals(10_803_977L, plan.missing().get("core1k"));
        });
    }
}
