package tbs.director;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;

/**
 * Оберег (пока только для теста — выдаётся командой /null ward или из админки).
 * Лежит в инвентаре и с шансом 60% гасит действие режиссёра. У него 5 зарядов;
 * когда кончаются — превращается в name.null.
 */
final class Ward {
    static final int CHARGES = 5;
    private Ward() {}

    /**
     * Название и описание — на языке игрока, для которого оберег сделан. Предмет общий, поэтому
     * при каждом срабатывании текст переписывается на язык того, у кого он сейчас лежит.
     */
    static ItemStack make(boolean ru) {
        ItemStack s = new ItemStack(Items.AMETHYST_SHARD);
        CompoundTag tag = new CompoundTag();
        tag.putInt("tbsWard", CHARGES);
        s.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        lore(s, CHARGES, ru);
        return s;
    }

    private static void lore(ItemStack s, int left, boolean ru) {
        s.set(DataComponents.CUSTOM_NAME, Component.literal(L.tr(ru, "Оберег", "Ward")).withStyle(ChatFormatting.LIGHT_PURPLE));
        s.set(DataComponents.LORE, new ItemLore(List.of(
                Component.literal(L.tr(ru, "Он не любит, когда на него смотрят.", "He doesn't like being looked at.")).withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC),
                Component.literal(L.tr(ru, "Зарядов: ", "Charges: ") + left + "/" + CHARGES).withStyle(ChatFormatting.GRAY))));
    }

    static void give(ServerPlayer p) {
        ItemStack s = make(L.ru(p));
        if (!p.getInventory().add(s)) {
            p.drop(s, false);
        }
    }

    private static int charges(ItemStack s) {
        CustomData cd = s.get(DataComponents.CUSTOM_DATA);
        return cd == null ? -1 : cd.copyTag().contains("tbsWard") ? cd.copyTag().getInt("tbsWard") : -1;
    }

    /** true — оберег сработал, действие отменено. */
    static boolean blocks(ServerPlayer p, String action) {
        var inv = p.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            int left = charges(s);
            if (left <= 0) {
                continue;
            }
            if (ThreadLocalRandom.current().nextInt(100) >= 60) {
                return false; // не всегда спасает
            }
            left--;
            p.serverLevel().sendParticles(ParticleTypes.END_ROD, p.getX(), p.getY() + 1.2, p.getZ(), 14, 0.4, 0.5, 0.4, 0.02);
            p.playNotifySound(SoundEvents.AMETHYST_BLOCK_BREAK, SoundSource.PLAYERS, 1f, 0.6f);
            boolean ru = L.ru(p);
            Hooks.SELF.set(true);
            try {
                if (left == 0) {
                    inv.setItem(i, Echo.brokenItem(ThreadLocalRandom.current(), 1));
                    p.sendSystemMessage(Component.literal(L.tr(ru, "Оберег рассыпался. Он больше тебя не прячет.", "The ward crumbled. It can't hide you anymore."))
                            .withStyle(ChatFormatting.DARK_RED, ChatFormatting.ITALIC));
                } else {
                    CompoundTag tag = new CompoundTag();
                    tag.putInt("tbsWard", left);
                    s.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
                    lore(s, left, ru);
                    p.sendSystemMessage(Component.literal(L.tr(ru, "Оберег треснул.", "The ward cracked.")).withStyle(ChatFormatting.LIGHT_PURPLE, ChatFormatting.ITALIC));
                }
            } finally {
                Hooks.SELF.set(false);
            }
            DirectorMod.LOG.info("[understudy] оберег {} отвёл {} (осталось {})", p.getGameProfile().getName(), action, left);
            AdminApi.feed("оберег " + p.getGameProfile().getName() + " отвёл «" + action + "», зарядов " + left);
            return true;
        }
        return false;
    }
}