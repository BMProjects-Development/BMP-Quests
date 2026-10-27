package org.bmp.bmp_quests.client;

import dev.architectury.networking.NetworkManager;
import dev.ftb.mods.ftblibrary.config.ConfigGroup;
import dev.ftb.mods.ftblibrary.config.ui.EditConfigScreen;
import dev.ftb.mods.ftbquests.client.gui.quests.QuestScreen;
import dev.ftb.mods.ftbquests.quest.ChapterImage;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import org.bmp.bmp_quests.bulk.BulkEditor;
import org.bmp.bmp_quests.image.ImageVisibility;
import org.bmp.bmp_quests.net.BulkEditMessage;
import org.bmp.bmp_quests.mixin.QuestScreenAccessor;
import java.util.*;

public final class BulkImageEditor {
    private BulkImageEditor() {}
    public static void send(QuestScreen screen, List<ChapterImage> images, ImageVisibility rule) {
        if (images.isEmpty() || images.size() > BulkEditor.MAX_TARGETS) {
            QuestScreen.displayError(Component.translatable("bmpquests.error.limit")); return;
        }
        CompoundTag plan = new CompoundTag();
        plan.putLongArray("targets", images.stream().mapToLong(i -> i.id).toArray());
        plan.putString("locale", ((QuestScreenAccessor)screen).bmpquests$getFile().getLocale());
        plan.putBoolean("images", true);
        plan.put("visibility", rule.toTag());
        NetworkManager.sendToServer(new BulkEditMessage(plan));
    }
    public static void open(QuestScreen screen, List<ChapterImage> images) {
        ImageVisibility[] rule = {ImageVisibility.ALWAYS};
        ConfigGroup group = new ConfigGroup("bmpquests.visibility", accepted -> {
            if (accepted) send(screen, images, rule[0]);
            screen.openGui();
        }) {
            @Override public Component getName() { return Component.translatable("bmpquests.images.title", images.size()); }
        };
        VisibilityConfig.fill(group, ((QuestScreenAccessor)screen).bmpquests$getFile(), rule[0], v -> rule[0] = v);
        new EditConfigScreen(group).setAutoclose(false).openGui();
    }
}

