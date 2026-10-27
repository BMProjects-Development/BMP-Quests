package org.bmp.bmp_quests.mixin;

import dev.ftb.mods.ftblibrary.ui.*;
import dev.ftb.mods.ftblibrary.util.TooltipList;
import dev.ftb.mods.ftbquests.client.gui.quests.*;
import dev.ftb.mods.ftbquests.quest.*;
import dev.ftb.mods.ftbquests.quest.theme.property.ThemeProperties;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.bmp.bmp_quests.client.BulkImageEditor;
import org.bmp.bmp_quests.image.*;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;
import java.util.*;

@Mixin(value = ChapterImageButton.class, remap = false)
public abstract class ChapterImageButtonMixin {
    @Shadow @Final private QuestScreen questScreen;
    @Shadow @Final private ChapterImage chapterImage;

    @Inject(method = "draw", at = @At("HEAD"), cancellable = true)
    private void bmpquests$draw(GuiGraphics graphics, Theme theme, int x, int y, int w, int h, CallbackInfo ci) {
        if (!((QuestScreenAccessor)questScreen).bmpquests$getFile().canEdit() && !chapterImage.shouldShowImage(((QuestScreenAccessor)questScreen).bmpquests$getFile().selfTeamData)) ci.cancel();
    }
    @Inject(method = "checkMouseOver", at = @At("HEAD"), cancellable = true)
    private void bmpquests$mouse(int x, int y, CallbackInfoReturnable<Boolean> cir) {
        if (!((QuestScreenAccessor)questScreen).bmpquests$getFile().canEdit() && !chapterImage.shouldShowImage(((QuestScreenAccessor)questScreen).bmpquests$getFile().selfTeamData)) cir.setReturnValue(false);
    }
    @Inject(method = "addMouseOverText", at = @At("TAIL"))
    private void bmpquests$warning(TooltipList list, CallbackInfo ci) {
        if (((QuestScreenAccessor)questScreen).bmpquests$getFile().canEdit() && ((DynamicImage)(Object)chapterImage).bmpquests$getVisibility().hasMissingQuest(((QuestScreenAccessor)questScreen).bmpquests$getFile()))
            list.add(Component.translatable("bmpquests.visibility.missing"));
    }
    @ModifyArg(method = "onClicked", at = @At(value = "INVOKE",
            target = "Ldev/ftb/mods/ftblibrary/ui/BaseScreen;openContextMenu(Ljava/util/List;)Ldev/ftb/mods/ftblibrary/ui/ContextMenu;"), index = 0)
    private List<ContextMenuItem> bmpquests$menu(List<ContextMenuItem> original) {
        List<ContextMenuItem> menu = new ArrayList<>(original);
        List<ChapterImage> selected = ((QuestScreenAccessor)questScreen).bmpquests$getSelection().stream()
                .filter(ChapterImage.class::isInstance).map(ChapterImage.class::cast).toList();
        List<ChapterImage> targets = selected.isEmpty() ? List.of(chapterImage) : selected;
        if (targets.size() > 1) menu.add(new ContextMenuItem(Component.translatable("bmpquests.images.bulk"), ThemeProperties.EDIT_ICON.get(),
                b -> BulkImageEditor.open(questScreen, targets)));
        var quests = questScreen.getSelectedQuests();
        if (quests.size() == 1) {
            Quest quest = quests.iterator().next();
            menu.add(new ContextMenuItem(Component.translatable("bmpquests.visibility.link_selected"), ThemeProperties.EDIT_ICON.get(),
                    b -> BulkImageEditor.send(questScreen, targets, new ImageVisibility(ImageVisibility.Mode.FOLLOW, quest.id, false, List.of()))));
        }
        menu.add(new ContextMenuItem(Component.translatable("bmpquests.visibility.clear"), ThemeProperties.DELETE_ICON.get(),
                b -> BulkImageEditor.send(questScreen, targets, ImageVisibility.ALWAYS))
                .setYesNoText(Component.translatable("bmpquests.visibility.clear_confirm")));
        return menu;
    }
}

