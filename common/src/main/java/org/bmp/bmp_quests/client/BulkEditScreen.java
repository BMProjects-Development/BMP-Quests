package org.bmp.bmp_quests.client;

import dev.architectury.networking.NetworkManager;
import dev.ftb.mods.ftblibrary.config.*;
import dev.ftb.mods.ftblibrary.config.ui.EditConfigScreen;
import dev.ftb.mods.ftblibrary.ui.*;
import dev.ftb.mods.ftblibrary.ui.input.MouseButton;
import dev.ftb.mods.ftbquests.client.ConfigIconItemStack;
import dev.ftb.mods.ftbquests.client.gui.MultilineTextEditorScreen;
import dev.ftb.mods.ftbquests.client.gui.quests.QuestScreen;
import dev.ftb.mods.ftbquests.quest.*;
import dev.ftb.mods.ftbquests.quest.reward.*;
import dev.ftb.mods.ftbquests.util.ConfigQuestObject;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.bmp.bmp_quests.bulk.*;
import org.bmp.bmp_quests.image.ImageVisibility;
import org.bmp.bmp_quests.net.BulkEditMessage;
import org.bmp.bmp_quests.mixin.QuestScreenAccessor;
import java.util.*;
import java.util.function.Consumer;

/** Every field edits a private draft. Only Apply sends a single server transaction. */
public final class BulkEditScreen extends EditConfigScreen {
    public enum DependencyAction { NONE, ADD, REMOVE, CLEAR }
    public enum RewardAction { NONE, ADD, REMOVE_TYPE, CLEAR, COPY, AUTO }
    private final CompoundTag plan;
    private final QuestScreen parentScreen;

    private BulkEditScreen(ConfigGroup group, CompoundTag plan, QuestScreen parent) {
        super(group);
        this.plan = plan;
        parentScreen = parent;
        setAutoclose(false);
    }

    public static void open(QuestScreen parent, Collection<Quest> selection) {
        List<Quest> quests = List.copyOf(selection);
        if (quests.size() < 2 || quests.size() > BulkEditor.MAX_TARGETS) {
            QuestScreen.displayError(Component.translatable("bmpquests.error.limit"));
            return;
        }
        CompoundTag plan = new CompoundTag();
        plan.putLongArray("targets", quests.stream().mapToLong(q -> q.id).toArray());
        plan.putString("locale", ((QuestScreenAccessor)parent).bmpquests$getFile().getLocale());
        ConfigGroup group = new ConfigGroup("bmpquests.bulk") {
            @Override public Component getName() { return Component.translatable("bmpquests.bulk.title", quests.size(), ((QuestScreenAccessor)parent).bmpquests$getFile().getLocale()); }
        };
        ConfigGroup general = group.getOrCreateSubgroup("general", 0);
        property(general, plan, "size", g -> g.addDouble("value", 0, v -> selected(plan, "size", DoubleTag.valueOf(v)), 0, 0, 8));
        property(general, plan, "shape", g -> g.addEnum("value", "default", v -> selected(plan, "shape", StringTag.valueOf(v.equals("default") ? "" : v)), QuestShape.idMapWithDefault));
        property(general, plan, "icon", g -> g.add("value", new ConfigIconItemStack(), ItemStack.EMPTY,
                v -> selected(plan, "icon", v.isEmpty() ? new CompoundTag() : v.save(((QuestScreenAccessor)parent).bmpquests$getFile().holderLookup())), ItemStack.EMPTY));
        tristate(general, plan, "can_repeat");
        property(general, plan, "repeat_cooldown", g -> g.addInt("value", 0, v -> selected(plan, "repeat_cooldown", IntTag.valueOf(v)), 0, 0, 1000000));
        bool(general, plan, "optional");

        ConfigGroup text = group.getOrCreateSubgroup("text", 1);
        for (String name : List.of("title", "subtitle", "description")) text(text.getOrCreateSubgroup(name).setNameKey("bmpquests.field." + name), plan, name);

        ConfigGroup rewards = group.getOrCreateSubgroup("rewards", 2);
        rewards.addEnum("action", RewardAction.NONE, v -> {
            if (v != RewardAction.NONE) section(plan, "rewards").putString("action", v.name());
            else section(plan, "rewards").remove("action");
        }, names(RewardAction.NONE, RewardAction.values(), "bmpquests.reward_action")).setNameKey("bmpquests.action");
        var typeMap = NameMap.of(RewardTypes.TYPES.values().iterator().next(), new ArrayList<>(RewardTypes.TYPES.values()))
                .id(r -> r.getTypeId().toString()).name(RewardType::getDisplayName).icon(RewardType::getIconSupplier).create();
        rewards.addEnum("type", typeMap.defaultValue, v -> section(plan, "rewards").putString("type", v.getTypeId().toString()), typeMap).setNameKey("bmpquests.reward.type");
        rewards.add("template", new RewardTemplateConfig(quests.getFirst()), new CompoundTag(), v -> section(plan, "rewards").put("template", v), new CompoundTag())
                .setNameKey("bmpquests.reward.template");
        rewards.add("source", new ConfigQuestObject<Quest>(o -> o == null || o instanceof Quest), null,
                v -> section(plan, "rewards").putLong("source", v == null ? 0 : v.id), null).setNameKey("bmpquests.reward.source");
        rewards.addEnum("auto", RewardAutoClaim.DEFAULT, v -> section(plan, "rewards").putString("auto", v.id), RewardAutoClaim.NAME_MAP)
                .setNameKey("ftbquests.reward.autoclaim");

        ConfigGroup dependencies = group.getOrCreateSubgroup("dependencies", 3);
        dependencies.addEnum("action", DependencyAction.NONE, v -> {
            if (v != DependencyAction.NONE) section(plan, "dependencies").putString("action", v.name());
            else section(plan, "dependencies").remove("action");
        }, names(DependencyAction.NONE, DependencyAction.values(), "bmpquests.dependency_action")).setNameKey("bmpquests.action");
        dependencies.addList("quests", List.<Quest>of(), new ConfigQuestObject<Quest>(o -> o == null || o instanceof Quest),
                v -> section(plan, "dependencies").putLongArray("quests", v.stream().filter(Objects::nonNull).mapToLong(q -> q.id).toArray()), null)
                .setNameKey("ftbquests.dependencies");
        property(dependencies, plan, "dependency_requirement", g -> g.addEnum("value", DependencyRequirement.ALL_COMPLETED,
                v -> selected(plan, "dependency_requirement", StringTag.valueOf(v.getId())), DependencyRequirement.NAME_MAP));
        property(dependencies, plan, "min_required_dependencies", g -> g.addInt("value", 0,
                v -> selected(plan, "min_required_dependencies", IntTag.valueOf(v)), 0, 0, 127));
        tristate(dependencies, plan, "hide_dependency_lines");
        bool(dependencies, plan, "hide_dependent_lines");

        ConfigGroup visibility = group.getOrCreateSubgroup("visibility", 4);
        for (String key : List.of("hide_until_deps_complete", "hide_until_deps_visible", "hide_details_until_startable", "hide_text_until_complete")) tristate(visibility, plan, key);
        bool(visibility, plan, "invisible");
        bool(visibility, plan, "hide_lock_icon");
        addSelectionStates(group, quests);
        new BulkEditScreen(group, plan, parent).openGui();
    }

    private static <T> NameMap<T> names(T def, T[] values, String key) { return NameMap.of(def, values).baseNameKey(key).create(); }
    private static CompoundTag section(CompoundTag root, String key) {
        if (!root.contains(key, Tag.TAG_COMPOUND)) root.put(key, new CompoundTag());
        return root.getCompound(key);
    }

    private static void property(ConfigGroup parent, CompoundTag plan, String key, java.util.function.Function<ConfigGroup, ConfigValue<?>> factory) {
        ConfigGroup group = parent.getOrCreateSubgroup(key).setNameKey("bmpquests.field." + key);
        var apply = group.addBool("apply", false, v -> {
            if (v) section(plan, "properties").putByte(key, (byte)0);
            else section(plan, "properties").remove(key);
        }, false).setNameKey("bmpquests.apply_field");
        factory.apply(group).setNameKey("bmpquests.value").setCanEdit(apply::getValue);
    }
    private static void selected(CompoundTag plan, String key, Tag value) {
        if (section(plan, "properties").contains(key)) section(plan, "properties").put(key, value);
    }
    private static void bool(ConfigGroup parent, CompoundTag plan, String key) {
        property(parent, plan, key, g -> g.addBool("value", false, v -> selected(plan, key, ByteTag.valueOf(v)), false));
    }
    private static void tristate(ConfigGroup parent, CompoundTag plan, String key) {
        property(parent, plan, key, g -> g.addEnum("value", Tristate.DEFAULT,
                v -> selected(plan, key, ByteTag.valueOf((byte)(v.isDefault() ? -1 : v.isTrue() ? 1 : 0))), Tristate.NAME_MAP));
    }

    private static void text(ConfigGroup group, CompoundTag plan, String key) {
        var apply = group.addBool("apply", false, v -> {}, false).setNameKey("bmpquests.apply_field");
        var mode = group.addEnum("mode", TextOperation.REPLACE, v -> {}, names(TextOperation.REPLACE, TextOperation.values(), "bmpquests.text_mode")).setNameKey("bmpquests.text.mode");
        var find = group.addString("find", "", v -> {}, "").setNameKey("bmpquests.text.find");
        group.add("value", new ListConfig<String, StringConfig>(new StringConfig()) {
            @Override public void onClicked(Widget widget, MouseButton button, ConfigCallback callback) {
                new MultilineTextEditorScreen(Component.translatable("bmpquests.field." + key), this, callback).openGui();
            }
        }, List.of(), lines -> {
            if (apply.getValue()) {
                CompoundTag operation = new CompoundTag();
                operation.putString("mode", mode.getValue().name());
                operation.putString("find", find.getValue());
                operation.putString("text", String.join("\n", lines));
                section(plan, "text").put(key, operation);
            } else section(plan, "text").remove(key);
        }, List.of()).setNameKey("bmpquests.text.value");
    }

    private static void addSelectionStates(ConfigGroup root, List<Quest> quests) {
        List<CompoundTag> data = quests.stream().map(q -> {
            CompoundTag tag = new CompoundTag(); q.writeData(tag, q.getQuestFile().holderLookup()); return tag;
        }).toList();
        for (ConfigGroup category : root.getSubgroups()) {
            for (ConfigGroup field : category.getSubgroups()) {
                String key = field.getId();
                List<String> values = category.getId().equals("text") ? quests.stream().map(q -> switch (key) {
                    case "title" -> q.getRawTitle();
                    case "subtitle" -> q.getRawSubtitle();
                    default -> String.join("\n", q.getRawDescription());
                }).toList() : data.stream().map(t -> t.contains(key) ? t.get(key).getAsString() : "").toList();
                boolean mixed = values.stream().distinct().limit(2).count() > 1;
                String current = mixed ? Component.translatable("bmpquests.mixed").getString() : values.getFirst();
                if (current.isEmpty()) current = Component.translatable("bmpquests.empty").getString();
                current = current.replace("\n", "\\n");
                if (current.length() > 80) current = current.substring(0, 77) + "...";
                field.addString("current", current, v -> {}, "").setNameKey("bmpquests.current").setCanEdit(false);
            }
        }
    }

    @Override public void alignWidgets() {
        if (!bottomPanel.getWidgets().isEmpty() && bottomPanel.getWidgets().getFirst() instanceof SimpleTextButton button)
            button.setTitle(Component.translatable("bmpquests.apply"));
        super.alignWidgets();
    }

    @Override protected void doAccept() {
        super.doAccept();
        CompoundTag request = plan.copy();
        if (!request.getCompound("rewards").contains("action")) request.remove("rewards");
        if (!request.getCompound("dependencies").contains("action")) request.remove("dependencies");
        if (request.getCompound("properties").isEmpty() && request.getCompound("text").isEmpty()
                && !request.contains("rewards") && !request.contains("dependencies")) {
            QuestScreen.displayError(Component.translatable("bmpquests.error.no_changes"));
            return;
        }
        Runnable send = () -> { NetworkManager.sendToServer(new BulkEditMessage(request)); parentScreen.openGui(); };
        String rewards = request.getCompound("rewards").getString("action");
        String dependencies = request.getCompound("dependencies").getString("action");
        boolean destructive = Set.of("CLEAR", "REMOVE_TYPE", "COPY").contains(rewards) || Set.of("CLEAR", "REMOVE").contains(dependencies)
                || request.getCompound("text").getAllKeys().stream().anyMatch(k -> request.getCompound("text").getCompound(k).getString("mode").equals("REPLACE"));
        if (destructive) openYesNo(Component.translatable("bmpquests.bulk.confirm"), Component.translatable("bmpquests.bulk.confirm_detail", request.getLongArray("targets").length), send);
        else send.run();
    }
    @Override protected void doCancel() { parentScreen.openGui(); }

    private static final class RewardTemplateConfig extends ConfigValue<CompoundTag> {
        private final Quest quest;
        private RewardTemplateConfig(Quest quest) { this.quest = quest; }
        @Override public CompoundTag copy(CompoundTag value) { return value.copy(); }
        @Override public Component getStringForGUI(CompoundTag value) {
            return Component.translatable(value.isEmpty() ? "bmpquests.reward.choose" : "bmpquests.reward.configured");
        }
        @Override public void onClicked(Widget widget, MouseButton button, ConfigCallback callback) {
            List<ContextMenuItem> menu = new ArrayList<>();
            RewardTypes.TYPES.values().forEach(type -> {
                if (type.getGuiProvider() != null) menu.add(new ContextMenuItem(type.getDisplayName(), type.getIconSupplier(), b ->
                        type.getGuiProvider().openCreationGui(widget.getParent(), new Quest(0, quest.getChapter()), reward -> {
                            CompoundTag template = new CompoundTag(), data = new CompoundTag();
                            reward.writeData(data, quest.getQuestFile().holderLookup());
                            template.putString("type", type.getTypeId().toString());
                            template.put("data", data);
                            template.putString("title", reward.getProtoTranslation(dev.ftb.mods.ftbquests.quest.translation.TranslationKey.TITLE));
                            setValue(template);
                            callback.save(true);
                        })));
            });
            widget.getGui().openContextMenu(menu);
        }
    }
}

