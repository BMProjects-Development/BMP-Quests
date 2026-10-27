package org.bmp.bmp_quests.mixin;

import dev.ftb.mods.ftblibrary.config.ConfigGroup;
import dev.ftb.mods.ftbquests.quest.ChapterImage;
import org.bmp.bmp_quests.client.VisibilityConfig;
import org.bmp.bmp_quests.image.DynamicImage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ChapterImage.class, remap = false)
public abstract class ChapterImageConfigMixin {
    @Inject(method = "fillConfigGroup", at = @At("TAIL"))
    private void bmpquests$config(ConfigGroup config, CallbackInfo ci) {
        ChapterImage image = (ChapterImage)(Object)this;
        DynamicImage dynamic = (DynamicImage)this;
        VisibilityConfig.fill(config.getOrCreateSubgroup("bmpquests_visibility").setNameKey("bmpquests.visibility"),
                image.getQuestFile(), dynamic.bmpquests$getVisibility(), dynamic::bmpquests$setVisibility);
    }
}
