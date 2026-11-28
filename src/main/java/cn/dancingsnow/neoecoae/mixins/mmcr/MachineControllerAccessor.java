package cn.dancingsnow.neoecoae.mixins.mmcr;

import cn.howxu.mmcr.internal.tile.MachineControllerBlockEntity;
import cn.howxu.mmcr.internal.tile.MachineControllerRuntime;
import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = MachineControllerBlockEntity.class, remap = false)
public interface MachineControllerAccessor {
    @Accessor("runtime")
    MachineControllerRuntime neoecoae$getRuntime();

    @Accessor("loadingRuntime")
    boolean neoecoae$isLoadingRuntime();

    @Accessor("pendingFactoryRuntimeInput")
    CompoundTag neoecoae$getPendingFactoryRuntimeInput();

    @Accessor("pendingCraftingRuntimeInput")
    CompoundTag neoecoae$getPendingCraftingRuntimeInput();

    @Accessor("pendingNormalPatternMemory")
    CompoundTag neoecoae$getPendingNormalPatternMemory();
}
