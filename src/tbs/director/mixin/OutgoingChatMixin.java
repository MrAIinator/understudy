package tbs.director.mixin;

import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundDisguisedChatPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import tbs.director.Hooks;

/** Подслушивает, что сервер пишет игроку в чат: режиссёр не должен говорить поверх The Broken Script. */
@Mixin(ServerCommonPacketListenerImpl.class)
public abstract class OutgoingChatMixin {
    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketSendListener;)V", at = @At("HEAD"), cancellable = true)
    private void tbsdirector$spy(Packet<?> packet, PacketSendListener listener, CallbackInfo ci) {
        if (!((Object) this instanceof ServerGamePacketListenerImpl game)) {
            return;
        }
        if (packet instanceof ClientboundSystemChatPacket sys && !sys.overlay()) {
            if (Hooks.outgoing(game.player, sys.content())) {
                ci.cancel();
            }
        } else if (packet instanceof ClientboundDisguisedChatPacket dis) {
            if (Hooks.outgoing(game.player, dis.message())) {
                ci.cancel();
            }
        }
    }
}
