package org.bmp.bmp_quests.mixin;

import dev.ftb.mods.ftbquests.quest.*;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.bmp.bmp_quests.image.*;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;

@Mixin(value = ChapterImage.class, remap = false)
public abstract class ChapterImageMixin implements DynamicImage {
    @Unique private ImageVisibility bmpquests$visibility = ImageVisibility.ALWAYS;
    @Unique private TeamData bmpquests$team;
    @Unique private long bmpquests$revision = -1;
    @Unique private boolean bmpquests$cached;

    @Override public ImageVisibility bmpquests$getVisibility() { return bmpquests$visibility; }
    @Override public void bmpquests$setVisibility(ImageVisibility visibility) {
        bmpquests$visibility = visibility;
        bmpquests$revision = -1;
    }

    @Inject(method = "writeData", at = @At("TAIL"))
    private void bmpquests$write(CompoundTag tag, HolderLookup.Provider provider, CallbackInfo ci) {
        if (!bmpquests$visibility.equals(ImageVisibility.ALWAYS)) tag.put(ImageVisibility.KEY, bmpquests$visibility.toTag());
    }
    @Inject(method = "readData", at = @At("TAIL"))
    private void bmpquests$read(CompoundTag tag, HolderLookup.Provider provider, CallbackInfo ci) {
        bmpquests$setVisibility(ImageVisibility.fromTag(tag.getCompound(ImageVisibility.KEY)));
    }
    @Inject(method = "writeNetData", at = @At("TAIL"))
    private void bmpquests$writeNet(RegistryFriendlyByteBuf buffer, CallbackInfo ci) { buffer.writeNbt(bmpquests$visibility.toTag()); }
    @Inject(method = "readNetData", at = @At("TAIL"))
    private void bmpquests$readNet(RegistryFriendlyByteBuf buffer, CallbackInfo ci) {
        CompoundTag tag = buffer.readNbt();
        bmpquests$setVisibility(tag == null ? ImageVisibility.ALWAYS : ImageVisibility.fromTag(tag));
    }
    @Inject(method = "shouldShowImage", at = @At("RETURN"), cancellable = true)
    private void bmpquests$visible(TeamData team, CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ() || bmpquests$visibility.mode() == ImageVisibility.Mode.ALWAYS) return;
        long revision = ProgressRevision.current();
        if (bmpquests$revision != revision || bmpquests$team != team) {
            bmpquests$team = team;
            bmpquests$cached = bmpquests$visibility.test(((ChapterImage)(Object)this).getQuestFile(), team);
            bmpquests$revision = revision;
        }
        cir.setReturnValue(bmpquests$cached);
    }
}
