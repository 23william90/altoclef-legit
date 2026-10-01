package adris.altoclef.mixins;

import adris.altoclef.eventbus.EventBus;
import adris.altoclef.eventbus.events.ChatMessageEvent;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.chat.GuiMessageTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MessageSignature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChatComponent.class)
public final class ChatReadMixin {

    @Inject(
            method = "addPlayerMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/multiplayer/chat/GuiMessageTag;)V",
            at = @At("HEAD")
    )
    private void onPlayerChatMessage(Component message, MessageSignature signature, GuiMessageTag tag, CallbackInfo ci) {
        if (message != null) {
            EventBus.publish(new ChatMessageEvent(message.getString()));
        }
    }

    @Inject(
            method = "addServerSystemMessage(Lnet/minecraft/network/chat/Component;)V",
            at = @At("HEAD")
    )
    private void onServerChatMessage(Component message, CallbackInfo ci) {
        if (message != null) {
            EventBus.publish(new ChatMessageEvent(message.getString()));
        }
    }
}