package org.bmp.bmp_quests.bulk;

import dev.ftb.mods.ftbquests.quest.*;
import dev.ftb.mods.ftbquests.quest.history.CreateOrDeleteRecord;
import dev.ftb.mods.ftbquests.quest.reward.*;
import dev.ftb.mods.ftbquests.quest.task.Task;
import dev.ftb.mods.ftbquests.quest.translation.TranslationKey;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import org.bmp.bmp_quests.image.ImageVisibility;
import java.util.*;

public final class BulkEditor {
    public static final int MAX_TARGETS = 256;
    private static final Set<String> TRISTATES = Set.of("can_repeat", "hide_until_deps_complete", "hide_until_deps_visible",
            "hide_details_until_startable", "hide_text_until_complete", "hide_dependency_lines");
    private static final Set<String> BOOLEANS = Set.of("optional", "invisible", "hide_lock_icon", "hide_dependent_lines");
    private BulkEditor() {}

    public static BulkTransaction prepare(ServerQuestFile file, CompoundTag plan) {
        long[] ids = plan.getLongArray("targets");
        require(ids.length > 0 && ids.length <= MAX_TARGETS, "limit");
        require(Arrays.stream(ids).distinct().count() == ids.length, "invalid");
        String locale = plan.getString("locale");
        require(locale.matches("[a-z0-9_]{1,32}"), "locale");
        Map<Long, BulkTransaction.State> before = new LinkedHashMap<>();
        Map<Long, BulkTransaction.State> after = new LinkedHashMap<>();
        boolean images = plan.getBoolean("images");
        List<Quest> quests = new ArrayList<>();
        for (long id : ids) {
            QuestObjectBase object = file.getBase(id);
            require(images ? object instanceof ChapterImage : object instanceof Quest, "missing");
            var state = BulkTransaction.State.capture(object);
            before.put(id, state);
            if (images) {
                require(plan.contains("visibility", Tag.TAG_COMPOUND), "invalid");
                CompoundTag data = state.object().nbt().copy();
                data.put(ImageVisibility.KEY, ImageVisibility.fromTag(plan.getCompound("visibility")).toTag());
                after.put(id, state.withData(data));
            } else {
                quests.add((Quest)object);
                CompoundTag data = state.object().nbt().copy();
                patchProperties(data, plan.getCompound("properties"));
                editDependencies(file, (Quest)object, data, plan.getCompound("dependencies"));
                after.put(id, editText(file, object, state.withData(data), locale, plan.getCompound("text")));
            }
        }
        if (!images) {
            if (!plan.getCompound("dependencies").isEmpty()) validateDependencies(file, after);
            editRewards(file, quests, plan.getCompound("rewards"), locale, before, after);
        }
        require(after.size() <= 4096, "limit");
        require(!before.equals(after), "no_changes");
        return new BulkTransaction(before, after, ids.length);
    }

    private static void patchProperties(CompoundTag data, CompoundTag properties) {
        for (String key : properties.getAllKeys()) {
            Tag value = properties.get(key);
            if (TRISTATES.contains(key)) require(value instanceof ByteTag && properties.getByte(key) >= -1 && properties.getByte(key) <= 1, "invalid");
            else if (BOOLEANS.contains(key)) require(value instanceof ByteTag && (properties.getByte(key) == 0 || properties.getByte(key) == 1), "invalid");
            else switch (key) {
                case "size" -> require(value instanceof NumericTag && Double.isFinite(properties.getDouble(key)) && properties.getDouble(key) >= 0 && properties.getDouble(key) <= 8, "invalid");
                case "shape" -> require(value instanceof StringTag && properties.getString(key).length() <= 128, "invalid");
                case "icon" -> require(value instanceof CompoundTag, "invalid");
                case "dependency_requirement" -> require(Arrays.stream(DependencyRequirement.values()).anyMatch(v -> v.getId().equals(properties.getString(key))), "invalid");
                case "min_required_dependencies", "repeat_cooldown" -> require(value instanceof IntTag && properties.getInt(key) >= 0 && properties.getInt(key) <= 1000000, "invalid");
                default -> throw error("invalid");
            }
            if (TRISTATES.contains(key) && properties.getByte(key) == -1) data.remove(key);
            else data.put(key, value.copy());
        }
    }

    private static BulkTransaction.State editText(ServerQuestFile file, QuestObjectBase object, BulkTransaction.State state,
                                                   String locale, CompoundTag operations) {
        Map<String, CompoundTag> translations = new HashMap<>(state.translations());
        CompoundTag entries = translations.getOrDefault(locale, new CompoundTag()).copy();
        for (String name : operations.getAllKeys()) {
            TranslationKey key = switch (name) {
                case "title" -> TranslationKey.TITLE;
                case "subtitle" -> TranslationKey.QUEST_SUBTITLE;
                case "description" -> TranslationKey.QUEST_DESC;
                default -> throw error("invalid");
            };
            CompoundTag operation = operations.getCompound(name);
            TextOperation mode;
            try { mode = TextOperation.valueOf(operation.getString("mode")); }
            catch (IllegalArgumentException ex) { throw error("invalid"); }
            String text = operation.getString("text"), find = operation.getString("find");
            require(text.length() <= 32767 && find.length() <= 32767, "limit");
            String old = key.isListVal() ? String.join("\n", file.getTranslationManager().getStringListTranslation(object, locale, key).orElse(List.of()))
                    : file.getTranslationManager().getStringTranslation(object, locale, key).orElse("");
            String result = mode.apply(old, text, find);
            require(result.length() <= 32767, "limit");
            require(key != TranslationKey.TITLE || !result.isBlank(), "empty_title");
            String fullKey = BulkTransaction.prefix(object.getObjectType(), object.id) + key.getName();
            if (key.isListVal()) {
                ListTag list = new ListTag();
                if (!result.isEmpty()) Arrays.stream(result.split("\n", -1)).forEach(s -> list.add(StringTag.valueOf(s)));
                entries.put(fullKey, list);
            } else entries.putString(fullKey, result);
        }
        if (!operations.isEmpty()) translations.put(locale, entries);
        return new BulkTransaction.State(state.object(), true, Map.copyOf(translations));
    }

    private static void editDependencies(ServerQuestFile file, Quest quest, CompoundTag data, CompoundTag operation) {
        if (operation.isEmpty()) return;
        String action = operation.getString("action");
        require(Set.of("ADD", "REMOVE", "CLEAR").contains(action), "invalid");
        LinkedHashSet<Long> dependencies = new LinkedHashSet<>();
        quest.streamDependencies().forEach(q -> dependencies.add(q.id));
        if (action.equals("CLEAR")) dependencies.clear();
        else {
            long[] requested = operation.getLongArray("quests");
            require(requested.length > 0 && requested.length <= MAX_TARGETS, "missing");
            for (long id : requested) {
                require(file.getQuest(id) != null, "missing");
                if (action.equals("ADD")) { require(id != quest.id, "cycle"); dependencies.add(id); }
                else dependencies.remove(id);
            }
        }
        ListTag list = new ListTag();
        dependencies.forEach(id -> list.add(StringTag.valueOf(QuestObjectBase.getCodeString(id))));
        data.put("dependencies", list);
        // Control points for removed connections must not survive a clear/remove operation.
        CompoundTag points = data.getCompound("dep_control_pts").copy();
        for (String id : new HashSet<>(points.getAllKeys())) if (!dependencies.contains(QuestObjectBase.parseCodeString(id))) points.remove(id);
        data.put("dep_control_pts", points);
    }

    private static void validateDependencies(ServerQuestFile file, Map<Long, BulkTransaction.State> after) {
        Map<Long, Set<Long>> graph = new HashMap<>();
        for (QuestObjectBase object : file.getAllObjects()) {
            Set<Long> edges = new HashSet<>();
            if (object instanceof Quest quest) {
                if (after.containsKey(quest.id)) {
                    ListTag deps = after.get(quest.id).object().nbt().getList("dependencies", Tag.TAG_STRING);
                    for (int i = 0; i < deps.size(); i++) edges.add(QuestObjectBase.parseCodeString(deps.getString(i)));
                } else quest.streamDependencies().forEach(d -> edges.add(d.id));
            } else if (object instanceof Chapter chapter) chapter.getQuests().forEach(q -> edges.add(q.id));
            else if (object instanceof Task task) edges.add(task.getQuest().id);
            else if (object instanceof QuestLink link) link.getQuest().ifPresent(q -> edges.add(q.id));
            graph.put(object.id, edges);
        }
        require(DependencyGraph.isAcyclic(graph), "cycle");
    }

    private static void editRewards(ServerQuestFile file, List<Quest> quests, CompoundTag operation, String locale,
                                    Map<Long, BulkTransaction.State> before, Map<Long, BulkTransaction.State> after) {
        if (operation.isEmpty()) return;
        String action = operation.getString("action");
        require(Set.of("ADD", "REMOVE_TYPE", "CLEAR", "COPY", "AUTO").contains(action), "invalid");
        Quest source = file.getQuest(operation.getLong("source"));
        if (action.equals("COPY")) require(source != null, "missing");
        if (action.equals("REMOVE_TYPE")) require(!operation.getString("type").isBlank(), "missing");
        if (action.equals("AUTO")) require(Arrays.stream(RewardAutoClaim.values()).anyMatch(v -> v.id.equals(operation.getString("auto"))), "invalid");
        List<BulkTransaction.State> templates = new ArrayList<>();
        if (action.equals("COPY")) source.getRewards().forEach(r -> templates.add(BulkTransaction.State.capture(r)));
        for (Quest quest : quests) {
            if (action.equals("COPY") && quest == source) continue;
            for (Reward reward : quest.getRewards()) {
                boolean remove = action.equals("CLEAR") || action.equals("COPY") || action.equals("REMOVE_TYPE") && reward.getType().getTypeId().toString().equals(operation.getString("type"));
                if (remove || action.equals("AUTO")) {
                    var state = BulkTransaction.State.capture(reward);
                    before.put(reward.id, state);
                    CompoundTag data = state.object().nbt().copy();
                    data.putString("auto", operation.getString("auto"));
                    after.put(reward.id, remove ? state.absent() : state.withData(data));
                }
            }
            if (action.equals("ADD")) {
                CompoundTag template = operation.getCompound("template");
                Reward prototype = RewardType.createReward(0, quest, template.getString("type"));
                require(prototype != null, "missing");
                prototype.readData(template.getCompound("data"), file.holderLookup());
                CompoundTag data = new CompoundTag();
                prototype.writeData(data, file.holderLookup());
                long id = file.newID();
                var rec = new CreateOrDeleteRecord(id, quest.id, QuestObjectType.REWARD, prototype.getTitle(), data, prototype.getType().makeExtraNBT());
                Map<String, CompoundTag> texts = new HashMap<>();
                if (template.contains("title")) {
                    CompoundTag title = new CompoundTag();
                    title.putString(BulkTransaction.prefix(QuestObjectType.REWARD, id) + TranslationKey.TITLE.getName(), template.getString("title"));
                    texts.put(locale, title);
                }
                before.put(id, new BulkTransaction.State(rec, false, Map.of()));
                after.put(id, new BulkTransaction.State(rec, true, texts));
            } else if (action.equals("COPY")) {
                for (var template : templates) {
                    long id = file.newID();
                    var rec = template.object();
                    var clone = new CreateOrDeleteRecord(id, quest.id, QuestObjectType.REWARD, rec.title(), rec.nbt().copy(), rec.extra().copy());
                    Map<String, CompoundTag> texts = new HashMap<>();
                    String oldPrefix = BulkTransaction.prefix(QuestObjectType.REWARD, rec.id());
                    String newPrefix = BulkTransaction.prefix(QuestObjectType.REWARD, id);
                    template.translations().forEach((lang, entries) -> {
                        CompoundTag copied = new CompoundTag();
                        for (String key : entries.getAllKeys()) copied.put(newPrefix + key.substring(oldPrefix.length()), entries.get(key).copy());
                        texts.put(lang, copied);
                    });
                    before.put(id, new BulkTransaction.State(clone, false, Map.of()));
                    after.put(id, new BulkTransaction.State(clone, true, texts));
                }
            }
            require(after.size() <= 4096, "limit");
        }
    }

    private static void require(boolean condition, String message) { if (!condition) throw error(message); }
    private static IllegalArgumentException error(String key) { return new IllegalArgumentException("bmpquests.error." + key); }
}
