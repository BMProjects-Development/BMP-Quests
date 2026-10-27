package org.bmp.bmp_quests.net;

import dev.architectury.networking.NetworkManager;
import dev.ftb.mods.ftbquests.integration.PermissionsHelper;
import dev.ftb.mods.ftbquests.quest.ServerQuestFile;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.*;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.bmp.bmp_quests.Constants;
import org.bmp.bmp_quests.bulk.BulkEditor;

public record BulkEditMessage(CompoundTag plan) implements CustomPacketPayload {
    public static final Type<BulkEditMessage> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "bulk_edit"));
    public static final StreamCodec<RegistryFriendlyByteBuf, BulkEditMessage> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.COMPOUND_TAG, BulkEditMessage::plan, BulkEditMessage::new);
    @Override public Type<BulkEditMessage> type() { return TYPE; }

    public static void handle(BulkEditMessage message, NetworkManager.PacketContext context) {
        context.queue(() -> ServerQuestFile.getInstance().ifPresent(file -> {
            if (!PermissionsHelper.canPlayerEdit(context)) return;
            try {
                var transaction = BulkEditor.prepare(file, message.plan);
                file.getHistoryStack().addAndApply(file, transaction);
            } catch (IllegalArgumentException ex) {
                String key = ex.getMessage();
                context.getPlayer().sendSystemMessage(Component.translatable(key != null && key.startsWith("bmpquests.error.") ? key : "bmpquests.error.invalid"));
            } catch (RuntimeException ex) {
                Constants.LOG.error("Unable to prepare bulk edit", ex);
                context.getPlayer().sendSystemMessage(Component.translatable("bmpquests.error.invalid"));
            }
        }));
    }
}
