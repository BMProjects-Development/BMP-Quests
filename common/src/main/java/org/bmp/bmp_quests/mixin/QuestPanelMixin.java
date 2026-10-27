package org.bmp.bmp_quests.mixin;

import dev.ftb.mods.ftblibrary.ui.Panel;
import dev.ftb.mods.ftbquests.client.gui.quests.*;
import dev.ftb.mods.ftbquests.quest.Chapter;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = QuestPanel.class, remap = false)
public abstract class QuestPanelMixin {
    @Shadow @Final private QuestScreen questScreen;

    @Inject(method = "addWidgets", at = @At("TAIL"))
    private void bmpquests$keepHiddenImages(CallbackInfo ci) {
        Chapter chapter = ((QuestScreenAccessor)questScreen).bmpquests$getChapter();
        if (chapter != null && !((QuestScreenAccessor)questScreen).bmpquests$getFile().canEdit()) {
            // Hidden widgets remain available for live progress updates; draw and hit testing fail closed.
            Panel panel = (Panel)(Object)this;
            chapter.getImages().stream().filter(i -> !i.shouldShowImage(((QuestScreenAccessor)questScreen).bmpquests$getFile().selfTeamData))
                    .forEach(i -> panel.add(new ChapterImageButton(panel, i)));
        }
    }
}

