package org.bmp.bmp_quests.client;

import dev.ftb.mods.ftblibrary.config.*;
import dev.ftb.mods.ftblibrary.config.ui.EditConfigScreen;
import dev.ftb.mods.ftblibrary.ui.Widget;
import dev.ftb.mods.ftblibrary.ui.input.MouseButton;
import dev.ftb.mods.ftbquests.quest.*;
import dev.ftb.mods.ftbquests.util.ConfigQuestObject;
import net.minecraft.network.chat.Component;
import org.bmp.bmp_quests.image.ImageVisibility;
import java.util.*;
import java.util.function.Consumer;

public final class VisibilityConfig {
    public static final NameMap<ImageVisibility.Mode> MODES = NameMap.of(ImageVisibility.Mode.ALWAYS, ImageVisibility.Mode.values())
            .baseNameKey("bmpquests.mode").create();
    private static final NameMap<ImageVisibility.Mode> CONDITION_MODES = NameMap.of(ImageVisibility.Mode.FOLLOW,
            List.of(ImageVisibility.Mode.FOLLOW, ImageVisibility.Mode.AVAILABLE, ImageVisibility.Mode.COMPLETED))
            .baseNameKey("bmpquests.mode").create();

    private VisibilityConfig() {}

    public static void fill(ConfigGroup group, BaseQuestFile file, ImageVisibility original, Consumer<ImageVisibility> setter) {
        // ConfigValue applies setters only when the enclosing editor is accepted.
        var mode = group.addEnum("mode", original.mode(), v -> {}, MODES).setNameKey("bmpquests.visibility.mode");
        var quest = group.add("quest", new ConfigQuestObject<Quest>(o -> o == null || o instanceof Quest),
                file.getQuest(original.quest()), v -> {}, null).setNameKey("bmpquests.visibility.quest");
        var any = group.addBool("any", original.any(), v -> {}, false).setNameKey("bmpquests.visibility.any");
        group.add("conditions", new ListConfig<>(new ConditionConfig(file)), original.conditions(),
                v -> setter.accept(new ImageVisibility(mode.getValue(),
                        quest.getValue() == null ? original.quest() : quest.getValue().id, any.getValue(), v)), List.of())
                .setNameKey("bmpquests.visibility.conditions");
    }

    private static final class ConditionConfig extends ConfigValue<ImageVisibility.Condition> {
        private final BaseQuestFile file;
        private ConditionConfig(BaseQuestFile file) { this.file = file; }

        @Override public Component getStringForGUI(ImageVisibility.Condition value) {
            if (value == null) return Component.translatable("bmpquests.visibility.new_condition");
            Quest quest = file.getQuest(value.quest());
            Component target = quest == null ? Component.translatable("bmpquests.visibility.missing") : quest.getTitle();
            return target.copy().append(" — ").append(CONDITION_MODES.getDisplayName(value.mode()));
        }

        @Override public void onClicked(Widget widget, MouseButton button, ConfigCallback callback) {
            ImageVisibility.Condition old = getValue() == null ? new ImageVisibility.Condition(0, ImageVisibility.Mode.FOLLOW) : getValue();
            ConfigGroup group = new ConfigGroup("bmpquests.condition", accepted -> callback.save(accepted));
            var mode = group.addEnum("mode", old.mode(), v -> {}, CONDITION_MODES).setNameKey("bmpquests.visibility.mode");
            group.add("quest", new ConfigQuestObject<Quest>(o -> o == null || o instanceof Quest), file.getQuest(old.quest()),
                    v -> setValue(new ImageVisibility.Condition(v == null ? old.quest() : v.id, mode.getValue())), null)
                    .setNameKey("bmpquests.visibility.quest");
            new EditConfigScreen(group).setAutoclose(true).openGui();
        }
    }
}
