package org.bmp.bmp_quests.mixin;

import dev.ftb.mods.ftbquests.quest.ServerQuestFile;
import org.bmp.bmp_quests.test.IntegrationChecks;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Included only in opt-in development runs, never in release artifacts. */
@Mixin(value = ServerQuestFile.class, remap = false)
public abstract class IntegrationMixin {
    @Unique private static boolean bmpquests$tested;
    @Inject(method = "load(ZZ)V", at = @At("TAIL"))
    private void bmpquests$test(boolean quests, boolean progress, CallbackInfo ci) {
        if (bmpquests$tested) return;
        bmpquests$tested = true;
        IntegrationChecks.run((ServerQuestFile)(Object)this);
    }
}
