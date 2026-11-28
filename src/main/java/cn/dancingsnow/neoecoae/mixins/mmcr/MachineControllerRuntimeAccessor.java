package cn.dancingsnow.neoecoae.mixins.mmcr;

import cn.howxu.mmcr.internal.runtime.ControllerRuntimeSnapshot;
import cn.howxu.mmcr.internal.tile.MachineControllerRuntime;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = MachineControllerRuntime.class, remap = false)
public interface MachineControllerRuntimeAccessor {
    @Accessor("publishedSnapshot")
    ControllerRuntimeSnapshot neoecoae$getPublishedSnapshot();
}
