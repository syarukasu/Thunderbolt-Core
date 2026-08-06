package com.moakiee.thunderbolt.ae2.crafting;

import java.util.IdentityHashMap;
import java.util.Map;

import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.stacks.AEKey;
import appeng.crafting.CraftingPlan;
import net.minecraft.resources.ResourceLocation;

import com.moakiee.thunderbolt.ThunderboltCore;
import com.moakiee.thunderbolt.api.crafting.CraftingPlanningEngine;
import com.moakiee.thunderbolt.api.crafting.PlanningAttempt;
import com.moakiee.thunderbolt.api.crafting.PlanningEngineSession;
import com.moakiee.thunderbolt.api.crafting.PlanningRequest;
import com.moakiee.thunderbolt.core.planner.ReusableStockUsageKey;

/** Adapter that exposes Thunderbolt's current V2 fast planner through the multi-engine API. */
public final class ThunderboltV2PlanningEngine implements CraftingPlanningEngine {
    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(
            ThunderboltCore.MODID, "v2");
    public static final ThunderboltV2PlanningEngine INSTANCE = new ThunderboltV2PlanningEngine();

    private ThunderboltV2PlanningEngine() {
    }

    @Override
    public ResourceLocation id() {
        return ID;
    }

    @Override
    public boolean check(IGrid grid, PlanningRequest request) {
        return request.requestedAmount() > 0
                && request.output() != null
                && request.requester().getGridNode() != null
                && request.requester().getGridNode().getGrid() == grid;
    }

    @Override
    public PlanningEngineSession createSession(IGrid grid, PlanningRequest request) {
        return new Session(request);
    }

    private static final class Session implements PlanningEngineSession {
        private final PlanningRequest request;
        private final FastCraftingPlanner.CalculationSession delegate =
                new FastCraftingPlanner.CalculationSession();
        private final Map<CraftingPlan, Map<ReusableStockUsageKey<AEKey>, Long>> reusableStock =
                new IdentityHashMap<>();

        private Session(PlanningRequest request) {
            this.request = request;
        }

        @Override
        public PlanningAttempt attempt(long amount, boolean simulate) {
            var result = FastCraftingPlanner.tryAttempt(
                    request.craftingService(), request.networkInventory(), request.level(),
                    request.output(), amount, simulate,
                    request.requester() instanceof ReservedStockCraftingRequester reserved
                            ? reserved : null,
                    delegate);
            if (!result.handled()) {
                return PlanningAttempt.DECLINE;
            }
            if (result.plan() != null) {
                reusableStock.put(result.plan(), result.usedReusableStock());
            }
            if (result.simulationFallback() != null) {
                reusableStock.put(result.simulationFallback(), result.usedReusableStock());
            }
            return new PlanningAttempt(
                    PlanningAttempt.Status.HANDLED,
                    result.plan(),
                    result.simulationFallback());
        }

        @Override
        public ICraftingPlan finish(ICraftingPlan result) {
            Map<ReusableStockUsageKey<AEKey>, Long> used = null;
            if (result instanceof CraftingPlan craftingPlan) {
                used = reusableStock.get(craftingPlan);
            }
            reusableStock.clear();
            return LoopCraftingPlan.wrapIfNeeded(result, used);
        }
    }
}
