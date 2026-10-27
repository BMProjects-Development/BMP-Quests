package org.bmp.bmp_quests.mixin;

import dev.architectury.networking.NetworkManager;
import dev.ftb.mods.ftbquests.integration.PermissionsHelper;
import dev.ftb.mods.ftbquests.net.EditObjectResponseMessage;
import dev.ftb.mods.ftbquests.quest.*;
import dev.ftb.mods.ftbquests.quest.history.EditRecord;
import dev.ftb.mods.ftbquests.quest.history.events.ModifyQuestObjects;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.List;

/** Native history updates editors; readers with an open book also need the new image rules. */
@Mixin(value = ModifyQuestObjects.class, remap = false)
public abstract class ImageEditSyncMixin {
    @Inject(method = "apply", at = @At("RETURN"))
    private void bmpquests$apply(ServerQuestFile file, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ()) bmpquests$sync(file, ((ModifyQuestObjects)(Object)this).newRecords());
    }
    @Inject(method = "applyUndo", at = @At("RETURN"))
    private void bmpquests$undo(ServerQuestFile file, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ()) bmpquests$sync(file, ((ModifyQuestObjects)(Object)this).oldRecords());
    }
    @org.spongepowered.asm.mixin.Unique
    private static void bmpquests$sync(ServerQuestFile file, List<EditRecord> records) {
        var images = records.stream().filter(rec -> file.getBase(rec.id()) instanceof ChapterImage).toList();
        if (!images.isEmpty()) NetworkManager.sendToPlayers(file.server.getPlayerList().getPlayers().stream()
                .filter(player -> !PermissionsHelper.canPlayerEdit(player)).toList(), new EditObjectResponseMessage(images));
    }
}
