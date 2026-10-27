package org.bmp.bmp_quests.mixin;

import dev.ftb.mods.ftbquests.quest.translation.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import java.util.Map;

@Mixin(value = TranslationManager.class, remap = false)
public interface TranslationManagerAccessor {
    @Accessor("map") Map<String, TranslationTable> bmpquests$getTables();
}
