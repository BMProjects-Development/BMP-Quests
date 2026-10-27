package org.bmp.bmp_quests.mixin;

import dev.ftb.mods.ftbquests.client.gui.quests.QuestScreen;
import dev.ftb.mods.ftbquests.client.ClientQuestFile;
import dev.ftb.mods.ftbquests.quest.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import java.util.List;

@Mixin(value = QuestScreen.class, remap = false)
public interface QuestScreenAccessor {
    @Accessor("file") ClientQuestFile bmpquests$getFile();
    @Accessor("selectedObjects") List<Movable> bmpquests$getSelection();
    @Accessor("selectedChapter") Chapter bmpquests$getChapter();
}
