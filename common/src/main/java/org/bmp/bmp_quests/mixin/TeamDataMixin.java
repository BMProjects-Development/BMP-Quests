package org.bmp.bmp_quests.mixin;

import dev.ftb.mods.ftbquests.quest.TeamData;
import org.bmp.bmp_quests.image.ProgressRevision;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = TeamData.class, remap = false)
public abstract class TeamDataMixin {
    @Inject(method = "clearCachedProgress", at = @At("HEAD"))
    private void bmpquests$invalidate(CallbackInfo ci) { ProgressRevision.invalidate(); }
}
