package dev.mrainator.tbsecho;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.CriticalHitEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.SweepAttackEvent;

/** Старый бой (до 1.9): без перезарядки удара и без удара по площади. */
public final class OldMechanics {
    private OldMechanics() {}

    private static void apply(Player player) {
        if (!(player instanceof ServerPlayer p)) return;
        AttributeInstance speed = p.getAttribute(Attributes.ATTACK_SPEED);
        if (speed != null) speed.setBaseValue(TbsEcho.OLD_COMBAT.get() ? 1024.0 : 4.0);
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent e) {
        apply(e.getEntity());
    }

    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent e) {
        apply(e.getEntity());
    }

    @SubscribeEvent
    public static void onDimension(PlayerEvent.PlayerChangedDimensionEvent e) {
        apply(e.getEntity());
    }

    @SubscribeEvent
    public static void onSweep(SweepAttackEvent e) {
        if (TbsEcho.NO_SWEEP.get()) e.setSweeping(false);
    }

    @SubscribeEvent
    public static void onCrit(CriticalHitEvent e) {
        if (TbsEcho.NO_SWEEP.get()) e.setDisableSweep(true);
    }
}
