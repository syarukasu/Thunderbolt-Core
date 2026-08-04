package com.moakiee.thunderbolt.core.planner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Regression tests from the 2026-08-04 x=169 diagnostic network: four chained Fibonacci ladders
 * (quantum_omni -> complex_omni -> omni -> appflux:core), every tier {@code 256m <- 64m + 16m}
 * style, all coefficients 1, a single processing pattern per output. The recursive fallback
 * expands such a compression DAG per parent-edge, so its work scales with the LEAF DEMAND
 * (~28 million here for one q256m); once its budget ran out it reported craftable intermediates
 * as missing. The aggregate linear pass proves the exact shortfall at the true leaves in O(E),
 * and on a route-unique graph that diagnosis must be returned as-is.
 *
 * <p>The exported save genuinely lacks the {@code omni_64k} pattern (the only non-leaf gap), so
 * the expected exact diagnosis for one q256m from empty stock is
 * {@code omni_64k=196418, core_4k=17480592, core_1k=10803977}.
 */
class Fib169ReproTest {

    private static final String[] TIERS = {
            "256m", "64m", "16m", "4m", "1m", "256k", "64k", "16k", "4k", "1k" };

    private static final long EXPECTED_O64K = 196_418L;
    private static final long EXPECTED_CORE4K = 17_480_592L;
    private static final long EXPECTED_CORE1K = 10_803_977L;

    /** chain tiers: t[i] <- t[i+1] + t[i+2]; the bottom two tiers are fed by the next chain (or are leaves). */
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

    private static void x169Patterns(CraftGraph.Builder<String> b) {
        // quantum ladder + its bottom two tiers fed by the complex chain
        ladder(b, "q", null);
        b.pattern("q4k", 1, List.of(CraftInput.of("c256m", 1), CraftInput.of("q1k", 1)));
        b.pattern("q1k", 1, List.of(CraftInput.of("c256m", 1), CraftInput.of("c64m", 1)));
        // complex ladder + bottom fed by the omni chain
        ladder(b, "c", null);
        b.pattern("c4k", 1, List.of(CraftInput.of("o256m", 1), CraftInput.of("c1k", 1)));
        b.pattern("c1k", 1, List.of(CraftInput.of("o256m", 1), CraftInput.of("o64m", 1)));
        // omni ladder — the exported save has NO pattern for o64k (slot was never encoded)
        ladder(b, "o", "64k");
        b.pattern("o4k", 1, List.of(CraftInput.of("core256m", 1), CraftInput.of("o1k", 1)));
        b.pattern("o1k", 1, List.of(CraftInput.of("core256m", 1), CraftInput.of("core64m", 1)));
        // appflux core ladder; core4k / core1k are true leaves (no pattern)
        ladder(b, "core", null);
    }

    /**
     * Route-unique deep compression DAG, empty stock: the missing list must be exactly the
     * pattern-less leaves with exact aggregate amounts — never a craftable intermediate — and the
     * whole request must resolve on the linear backbone without touching the search budget.
     */
    @Test
    void missingListsOnlyTrueLeavesWithExactAmounts() {
        CraftGraph.Builder<String> b = CraftGraph.builder();
        x169Patterns(b);
        CraftGraph<String> g = b.build();

        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            PlanningResult<String> result = CraftPlannerV2.planDetailed(g, "q256m", 1);
            CraftPlan<String> plan = result.plan();

            assertTrue(plan.supported());
            assertFalse(plan.feasible());
            assertFalse(plan.budgetExhausted(),
                    "route-unique graph must not enter the budgeted recursive search");
            assertEquals(
                    Map.of("o64k", EXPECTED_O64K,
                            "core4k", EXPECTED_CORE4K,
                            "core1k", EXPECTED_CORE1K),
                    plan.missing());

            List<String> craftableButMissing = new ArrayList<>();
            for (String key : plan.missing().keySet()) {
                if (!g.patternsFor(key).isEmpty()) {
                    craftableButMissing.add(key);
                }
            }
            assertTrue(craftableButMissing.isEmpty(),
                    "craftable intermediates misreported as missing: " + craftableButMissing);
            assertEquals(0, result.diagnostics().consumedSearchBudget());
            assertEquals(37, plan.firings().size(),
                    "the partial plan still schedules every reachable pattern");
        });
    }

    /** Control: with the leaves stocked the linear backbone is feasible with exact draws. */
    @Test
    void controlWithSufficientLeafStockIsFeasible() {
        CraftGraph.Builder<String> b = CraftGraph.builder();
        x169Patterns(b);
        b.stock("o64k", 200_000);
        b.stock("core4k", 20_000_000);
        b.stock("core1k", 11_000_000);
        CraftGraph<String> g = b.build();

        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            CraftPlan<String> plan = CraftPlannerV2.plan(g, "q256m", 1);
            assertTrue(plan.feasible());
            assertFalse(plan.budgetExhausted());
            assertTrue(plan.missing().isEmpty());
            assertEquals(EXPECTED_O64K, plan.usedStock().get("o64k"));
            assertEquals(EXPECTED_CORE4K, plan.usedStock().get("core4k"));
            assertEquals(EXPECTED_CORE1K, plan.usedStock().get("core1k"));
        });
    }

    /**
     * A contended root forces the recursive search, whose budget dies inside the compression DAG.
     * The committed search tail alone would list craftable intermediates; the plan must instead
     * carry the strictly better aggregate diagnosis (exact pattern-less leaves), still flagged
     * budget-exhausted because the search itself never completed.
     */
    @Test
    void exhaustedSearchPrefersAggregateDiagnosis() {
        CraftGraph.Builder<String> b = CraftGraph.builder();
        x169Patterns(b);
        // Second route for the target: makes the root contended so the gate cannot short-circuit.
        // "exotic" has no pattern and no stock, so this route is never the better one.
        b.pattern("q256m", 1, List.of(CraftInput.of("exotic", 1)));
        CraftGraph<String> g = b.build();

        assertTimeoutPreemptively(Duration.ofSeconds(60), () -> {
            PlanningResult<String> result = CraftPlannerV2.planDetailed(g, "q256m", 1);
            CraftPlan<String> plan = result.plan();

            assertFalse(plan.feasible());
            assertTrue(plan.budgetExhausted(), "the recursive search must have run out of budget");
            for (String key : plan.missing().keySet()) {
                assertTrue(g.patternsFor(key).isEmpty(),
                        "craftable intermediate misreported as missing: " + key);
            }
            assertEquals(EXPECTED_O64K + EXPECTED_CORE4K + EXPECTED_CORE1K,
                    plan.missing().getOrDefault("o64k", 0L)
                            + plan.missing().getOrDefault("core4k", 0L)
                            + plan.missing().getOrDefault("core1k", 0L),
                    "aggregate diagnosis must survive the exhausted search: " + plan.missing());
        });
    }

    /**
     * Gate soundness: a route-unique graph whose byproduct feeds a sibling demand is
     * execution-order sensitive (the fixed topological order of the linear pass visits the
     * consumer before the producer fired). The planner must still fall through to the recursive
     * search and find the feasible ordering instead of returning the linear miss.
     */
    @Test
    void byproductOrderSensitiveGraphStillResolvesViaSearch() {
        CraftPattern<String> top = new CraftPattern<>(
                "T", 1, List.of(CraftInput.of("A", 1), CraftInput.of("B", 1)), "top");
        CraftPattern<String> makeA = new CraftPattern<>(
                "A", 1, List.of(CraftInput.of("raw1", 1)), List.of(CraftOutput.of("S", 1)), "makeA");
        CraftPattern<String> makeB = new CraftPattern<>(
                "B", 1, List.of(CraftInput.of("S", 1)), "makeB");
        CraftGraph<String> g = CraftGraph.<String>builder()
                .pattern(top)
                .pattern(makeA)
                .pattern(makeB)
                .stock("raw1", 1)
                .build();

        CraftPlan<String> plan = CraftPlannerV2.plan(g, "T", 1);

        assertTrue(plan.feasible(),
                "S is A's byproduct; the search order must feed it to B: " + plan.missing());
        assertTrue(plan.missing().isEmpty());
    }
}
