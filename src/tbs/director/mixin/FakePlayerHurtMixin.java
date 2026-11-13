package tbs.director.mixin;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import tbs.director.Mimic;

/**
 * FakePlayerEntity из TBS при любом уроне от игрока сразу умирает (kill) и оставляет «Схему».
 * Для мимиков режиссёра урон считаем сами: 20 HP, как у игрока.
 */
@Pseudo
@Mixin(targets = "net.thebrokenscript.entity.circuit.FakePlayerEntity", remap = false)
public abstract class FakePlayerHurtMixin {
    @Inject(method = "hurt(Lnet/minecraft/world/damagesource/DamageSource;F)Z", at = @At("HEAD"), cancellable = true, require = 0)
    private void tbsdirector$hurt(DamageSource src, float amount, CallbackInfoReturnable<Boolean> cir) {
        Entity self = (Entity) (Object) this;
        if (self.level().isClientSide() || self.getServer() == null) {
            return;
        }
        if (!(src.getEntity() instanceof net.minecraft.world.entity.player.Player) && !src.is(DamageTypes.FALLING_ANVIL)) {
            return; // остальной урон TBS и так пропускает как обычный
        }
        if (Mimic.onHit(self.getServer(), self, src.getEntity(), amount)) {
            cir.setReturnValue(false);
        }
    }
}