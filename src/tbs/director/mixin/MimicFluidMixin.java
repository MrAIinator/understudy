package tbs.director.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import tbs.director.Mimic;

/**
 * Все существа TBS (BaseMonster) умеют стоять на любой жидкости, как лавомерка.
 * Мимик режиссёра притворяется игроком — он должен проваливаться в воду и плыть.
 */
@Pseudo
@Mixin(targets = "net.thebrokenscript.brokencore.api.entity.base.BaseMonster", remap = false)
public abstract class MimicFluidMixin {
    @Inject(method = "canStandOnFluid(Lnet/minecraft/world/level/material/FluidState;)Z", at = @At("HEAD"), cancellable = true, require = 0)
    private void tbsdirector$noWaterWalk(FluidState fluid, CallbackInfoReturnable<Boolean> cir) {
        if (Mimic.isMimic((Entity) (Object) this)) {
            cir.setReturnValue(false);
        }
    }
}
