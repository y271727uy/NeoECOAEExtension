package cn.dancingsnow.neoecoae.integration.mmcr.processing;

import appeng.api.stacks.AEKey;
import cn.dancingsnow.neoecoae.api.me.diagnostics.ECOProcessingStatusReader;
import cn.dancingsnow.neoecoae.api.me.diagnostics.ECOProcessingTargetProvider.Target;
import cn.dancingsnow.neoecoae.grid.crafting.display.processing.ECOProcessingProgress.Machine;
import cn.dancingsnow.neoecoae.grid.crafting.display.processing.ECOProcessingProgress.State;
import cn.dancingsnow.neoecoae.grid.crafting.display.processing.ECOProcessingSamples;
import cn.dancingsnow.neoecoae.integration.mmcr.ECOMmcrPatternDispatch;
import cn.dancingsnow.neoecoae.mixins.mmcr.MachineControllerAccessor;
import cn.dancingsnow.neoecoae.mixins.mmcr.MachineControllerRuntimeAccessor;
import cn.howxu.mmcr.api.capability.status.ExecutionStatus;
import cn.howxu.mmcr.api.recipe.MachineOutput;
import cn.howxu.mmcr.api.recipe.helper.CraftingStatus;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.adapter.AE2ResourceFamilies;
import cn.howxu.mmcr.internal.runtime.ControllerRecipePresentation;
import cn.howxu.mmcr.internal.runtime.ControllerRuntimeSnapshot;
import cn.howxu.mmcr.internal.tile.IOPortBlockEntity;
import cn.howxu.mmcr.internal.tile.MachineControllerBlockEntity;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.Nullable;

/** Shows MMCR's published recipe progress through the pattern interface's linked controllers. */
public final class MmcrProcessingStatus implements ECOProcessingStatusReader {
    @Override
    public boolean supports(BlockEntity machine) {
        return ECOMmcrPatternDispatch.isAvailable() && Loaded.supports(machine);
    }

    @Override
    public Observation read(BlockEntity machine, Context context) {
        if (!ECOMmcrPatternDispatch.isAvailable() || machine.isRemoved()
                || !(machine.getLevel() instanceof ServerLevel level) || !level.getServer().isSameThread()) {
            return Observation.EMPTY;
        }
        return Loaded.read(machine, level, context.output());
    }

    private static final class Loaded {
        static boolean supports(BlockEntity machine) {
            return machine instanceof IOPortBlockEntity || machine instanceof MachineControllerBlockEntity;
        }

        static Observation read(BlockEntity machine, ServerLevel level, AEKey output) {
            if (machine instanceof IOPortBlockEntity port) {
                var dimension = level.dimension().location();
                return new Observation(null, port.linkedControllerPositions().stream()
                        .map(pos -> new Target(dimension, pos)).toList());
            }
            var snapshot = publishedSnapshot(machine);
            if (snapshot == null) return Observation.EMPTY;
            var family = AE2ResourceFamilies.forType(output.getType());
            if (family == null) return Observation.EMPTY;
            var expected = family.patternOutput(output, 1L);
            if (expected == null) return Observation.EMPTY;
            boolean formed = snapshot.structure().formed() && snapshot.structure().structureAreaLoaded();
            var lanes = new ArrayList<Machine>();
            if (snapshot.factoryControllerPresent()) {
                var factory = snapshot.factory();
                for (var lane : factory.presentationLanes()) {
                    if (!lane.active() || !matchesOutput(lane.presentation(), expected)) continue;
                    var failure = lane.failure() == null ? factory.failure() : lane.failure();
                    lanes.add(sample(machine, lane.recipeId(), lane.tick(), lane.totalTick(), lane.parallelism(),
                            formed, factory.paused(), null, failure));
                }
            } else {
                var cycle = snapshot.crafting();
                if (cycle.recipeId() != null && matchesOutput(snapshot.recipePresentation(), expected)) {
                    lanes.add(sample(machine, cycle.recipeId().toString(), cycle.tick(), cycle.totalTick(),
                            cycle.parallelism(), formed, cycle.status() != null && cycle.status().isPaused(),
                            cycle.status(), cycle.failure()));
                }
            }
            // MMCR identifies running recipes and outputs, with no ECO job UUID.
            return lanes.isEmpty() ? Observation.EMPTY : Observation.sample(ECOProcessingSamples.combine(machine, lanes,
                    Component.translatable("gui.neoecoae.processing.association.recipe")));
        }

        @Nullable
        private static ControllerRuntimeSnapshot publishedSnapshot(BlockEntity machine) {
            if (!(machine instanceof MachineControllerAccessor controller)
                    || controller.neoecoae$isLoadingRuntime()
                    || controller.neoecoae$getPendingFactoryRuntimeInput() != null
                    || controller.neoecoae$getPendingCraftingRuntimeInput() != null
                    || controller.neoecoae$getPendingNormalPatternMemory() != null) return null;
            // MMCR's public snapshot getter can restore pending NBT and refresh runtime state.
            return ((MachineControllerRuntimeAccessor) (Object) controller.neoecoae$getRuntime())
                    .neoecoae$getPublishedSnapshot();
        }

        private static boolean matchesOutput(@Nullable ControllerRecipePresentation presentation,
                MachineOutput expected) {
            return presentation != null && presentation.outputs().stream()
                    .anyMatch(entry -> entry.amount() > 0 && entry.output().matchesPatternResource(expected));
        }

        private static Machine sample(BlockEntity machine, String recipe, int tick, int totalTick, long parallelism,
                boolean formed, boolean paused, @Nullable CraftingStatus status, @Nullable ExecutionStatus failure) {
            State state = !formed ? State.UNAVAILABLE
                    : failure != null ? failure.id().toString().contains("output") ? State.WAITING : State.PAUSED
                    : paused ? State.PAUSED
                    : status != null && status.getStatus() == CraftingStatus.Status.NO_RECIPE ? State.WAITING : State.RUNNING;
            var failureReason = failure == null ? null : failure.reason();
            String key = failureReason == null ? null : failureReason.translationKey();
            Component reason;
            if (key != null && !key.isBlank()) reason = Component.translatable(key);
            else if (!formed) reason = Component.translatable("gui.neoecoae.processing.paused");
            else if (paused) reason = Component.translatable("gui.mmcr.controller.redstone_stopped");
            else if (status != null && state != State.RUNNING) {
                String message = status.getUnlocMessage();
                reason = message != null && !message.isBlank() ? Component.translatable(message)
                        : Component.translatable("gui.neoecoae.processing.paused");
            } else reason = failure == null ? Component.empty() : Component.translatable("gui.neoecoae.processing.paused");
            return ECOProcessingSamples.cycle(machine, recipe, tick, totalTick, parallelism, state, reason);
        }
    }
}
