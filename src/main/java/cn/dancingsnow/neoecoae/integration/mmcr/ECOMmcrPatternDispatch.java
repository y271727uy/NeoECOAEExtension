package cn.dancingsnow.neoecoae.integration.mmcr;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.stacks.KeyCounter;
import cn.dancingsnow.neoecoae.api.me.diagnostics.ECOProcessingTargetProvider.Target;
import cn.dancingsnow.neoecoae.grid.crafting.execution.batch.provider.ECOBatchAdmission;
import cn.dancingsnow.neoecoae.grid.crafting.execution.batch.provider.ECOBatchAdmission.StopReason;
import cn.dancingsnow.neoecoae.mixins.ae2.accessor.PatternProviderLogicAccessor;
import cn.howxu.mmcr.api.compat.appliedenergistics2.BulkPatternProvider;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.tile.PatternInterfaceBlockEntity;
import net.neoforged.fml.ModList;
import org.jetbrains.annotations.Nullable;

/** Delivers funded copies to MMCR's hosted input inventory using the original pattern. */
public final class ECOMmcrPatternDispatch {
    private ECOMmcrPatternDispatch() {}

    public static boolean isAvailable() {
        return ModList.get().getModContainerById("mmcr")
                .map(mod -> mod.getModInfo().getVersion().toString().equals("0.2.1")).orElse(false);
    }

    @Nullable
    public static Target processingRoot(ICraftingProvider provider) {
        return isAvailable() ? Loaded.processingRoot(provider) : null;
    }

    @Nullable
    public static Session open(ICraftingProvider provider, IPatternDetails pattern) {
        return isAvailable() ? Loaded.open(provider, pattern) : null;
    }

    public interface Session {
        long suggestCopies(KeyCounter[] singleInputs, long requested);
        ECOBatchAdmission submit(KeyCounter[] totalInputs, long copies);
    }

    private static final class Loaded {
        @Nullable
        static Target processingRoot(ICraftingProvider provider) {
            if (provider instanceof PatternProviderLogicAccessor accessor
                    && accessor.neoecoae$getHost() instanceof PatternInterfaceBlockEntity host
                    && !host.isRemoved() && host.getLevel() != null) {
                return new Target(host.getLevel().dimension().location(), host.getBlockPos());
            }
            return null;
        }

        @Nullable
        static Session open(ICraftingProvider provider, IPatternDetails pattern) {
            var hint = BulkPatternProvider.find(provider).orElse(null);
            if (hint == null || !pattern.supportsPushInputsToExternalInventory()
                    || pattern.getInputs().length == 0 || pattern.getOutputs().isEmpty()) return null;
            return new HostedSession(provider, pattern, hint);
        }

        private record HostedSession(ICraftingProvider provider, IPatternDetails pattern,
                BulkPatternProvider hint) implements Session {
            @Override
            public long suggestCopies(KeyCounter[] singleInputs, long requested) {
                try {
                    return Math.min(requested, Math.max(0L, hint.suggestPatternCopies(pattern, singleInputs, requested)));
                } catch (RuntimeException unavailable) {
                    // A failed preview happens before ECO leases inputs or power.
                    return 0L;
                }
            }

            @Override
            public ECOBatchAdmission submit(KeyCounter[] totalInputs, long copies) {
                // As in MMCR's native CPU path, true transfers the full funded packet to hosted storage.
                // MMCR starts machines later, so acceptance does not prove a machine has started.
                return provider.pushPattern(pattern, totalInputs)
                        ? ECOBatchAdmission.accepted(copies, 0L, StopReason.BUFFERED)
                        : ECOBatchAdmission.rejected(StopReason.TARGET_UNAVAILABLE);
            }
        }
    }
}
