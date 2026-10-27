package org.bmp.bmp_quests.mixin;

import dev.ftb.mods.ftblibrary.ui.ContextMenuItem;
import dev.ftb.mods.ftbquests.client.gui.quests.*;
import dev.ftb.mods.ftbquests.quest.Quest;
import dev.ftb.mods.ftbquests.quest.theme.property.ThemeProperties;
import net.minecraft.network.chat.Component;
import org.bmp.bmp_quests.client.BulkEditScreen;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.*;

@Mixin(value = QuestButton.class, remap = false)
public abstract class QuestButtonMixin {
    @Shadow @Final protected QuestScreen questScreen;

    @Inject(method = "addMultiSelectionContextMenuItems", at = @At("HEAD"))
    private void bmpquests$bulk(Collection<Quest> quests, List<ContextMenuItem> menu, CallbackInfo ci) {
        if (quests.size() > 1 && ((QuestScreenAccessor)questScreen).bmpquests$getFile().canEdit()) {
            menu.add(new ContextMenuItem(Component.translatable("bmpquests.bulk"), ThemeProperties.EDIT_ICON.get(),
                    button -> BulkEditScreen.open(questScreen, quests)));
        }
    }
}

