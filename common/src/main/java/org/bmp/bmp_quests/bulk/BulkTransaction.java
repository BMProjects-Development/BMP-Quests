package org.bmp.bmp_quests.bulk;

import dev.ftb.mods.ftblibrary.util.NetworkHelper;
import dev.ftb.mods.ftbquests.net.*;
import dev.ftb.mods.ftbquests.quest.*;
import dev.ftb.mods.ftbquests.quest.history.*;
import dev.ftb.mods.ftbquests.quest.translation.*;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import org.bmp.bmp_quests.Constants;
import org.bmp.bmp_quests.mixin.TranslationManagerAccessor;
import java.util.*;

/** Server-built snapshots; packets never supply arbitrary object IDs for creation or deletion. */
public record BulkTransaction(Map<Long, State> before, Map<Long, State> after, int count) implements QuestBookEditEvent {
    public BulkTransaction {
        before = Collections.unmodifiableMap(new LinkedHashMap<>(before));
        after = Collections.unmodifiableMap(new LinkedHashMap<>(after));
    }
    public record State(CreateOrDeleteRecord object, boolean exists, Map<String, CompoundTag> translations) {
        public static State capture(QuestObjectBase object) {
            var translations = new HashMap<String, CompoundTag>();
            String prefix = prefix(object.getObjectType(), object.id);
            tables(object.getQuestFile()).forEach((locale, table) -> {
                CompoundTag selected = new CompoundTag();
                for (TranslationKey subKey : TranslationKey.values()) {
                    String key = prefix + subKey.getName();
                    if (table.contains(key)) table.getEntry(key, subKey.emptyValue())
                            .ifLeft(s -> selected.putString(key, s))
                            .ifRight(lines -> {
                                ListTag list = new ListTag();
                                lines.forEach(s -> list.add(StringTag.valueOf(s)));
                                selected.put(key, list);
                            });
                }
                if (!selected.isEmpty()) translations.put(locale, selected);
            });
            return new State(CreateOrDeleteRecord.ofQuestObject(object), true, Map.copyOf(translations));
        }
        public State withData(CompoundTag nbt) {
            return new State(new CreateOrDeleteRecord(object.id(), object.parent(), object.questObjectType(), object.title(), nbt, object.extra()), exists, translations);
        }
        public State absent() { return new State(object, false, Map.of()); }
    }

    public static Map<String, TranslationTable> tables(BaseQuestFile file) {
        return ((TranslationManagerAccessor)file.getTranslationManager()).bmpquests$getTables();
    }
    public static String prefix(QuestObjectType type, long id) { return type.getId() + "." + QuestObjectBase.getCodeString(id) + "."; }

    @Override public boolean apply(ServerQuestFile file) { return change(file, before, after); }
    @Override public boolean applyUndo(ServerQuestFile file) { return change(file, after, before); }

    private boolean change(ServerQuestFile file, Map<Long, State> expected, Map<Long, State> desired) {
        // Undo is refused if its parent or objects no longer exist in the expected state.
        for (var entry : expected.entrySet()) {
            QuestObjectBase actual = file.getBase(entry.getKey());
            State state = entry.getValue();
            if ((actual != null) != state.exists()) return false;
            if (actual != null && (actual.getObjectType() != state.object().questObjectType() || actual.getParentID() != state.object().parent())) return false;
        }
        try {
            restore(file, desired);
        } catch (RuntimeException ex) {
            try { restore(file, expected); }
            catch (RuntimeException rollback) { ex.addSuppressed(rollback); }
            Constants.LOG.error("Bulk edit rolled back", ex);
            return false;
        }
        // Publish only after every mutation succeeds. One history entry represents the whole batch.
        List<Long> deleted = new ArrayList<>();
        List<CreateOrDeleteRecord> created = new ArrayList<>();
        List<EditRecord> edited = new ArrayList<>();
        desired.forEach((id, state) -> {
            if (!state.exists()) deleted.add(id);
            else if (!expected.get(id).exists()) created.add(state.object());
            else edited.add(EditRecord.ofQuestObject(file.getBase(id)));
        });
        if (!deleted.isEmpty()) NetworkHelper.sendToAll(file.server, new DeleteObjectResponseMessage(deleted));
        if (!created.isEmpty()) NetworkHelper.sendToAll(file.server, new CreateObjectResponseMessage(created, Optional.empty()));
        if (!edited.isEmpty()) NetworkHelper.sendToAll(file.server, new EditObjectResponseMessage(edited));
        Set<String> locales = new HashSet<>();
        desired.forEach((id, state) -> {
            Set<String> candidates = new HashSet<>(expected.get(id).translations().keySet());
            candidates.addAll(state.translations().keySet());
            candidates.stream().filter(locale -> !Objects.equals(expected.get(id).translations().get(locale), state.translations().get(locale)))
                    .forEach(locales::add);
        });
        for (String locale : locales) {
            for (var player : file.server.getPlayerList().getPlayers()) file.getTranslationManager().sendTableToPlayer(player, locale);
        }
        return true;
    }

    private static void restore(ServerQuestFile file, Map<Long, State> states) {
        for (var entry : states.entrySet()) {
            State state = entry.getValue();
            QuestObjectBase object = file.getBase(entry.getKey());
            if (!state.exists()) {
                if (object != null) object.deleteSelf();
            } else {
                if (object == null) {
                    var rec = state.object();
                    object = file.create(rec.id(), rec.questObjectType(), rec.parent(), rec.extra());
                    object.readData(rec.nbt(), file.holderLookup());
                    object.onCreated();
                } else {
                    object.readData(state.object().nbt(), file.holderLookup());
                    object.editedFromGUIOnServer();
                }
            }
            String prefix = prefix(state.object().questObjectType(), entry.getKey());
            // Remove only this object's entries; its tasks and rewards retain their translations.
            tables(file).values().forEach(table -> {
                for (TranslationKey subKey : TranslationKey.values()) table.remove(prefix + subKey.getName());
            });
            state.translations().forEach((locale, tag) -> {
                TranslationTable table = tables(file).computeIfAbsent(locale, k -> new TranslationTable());
                for (String key : tag.getAllKeys()) {
                    if (tag.get(key) instanceof StringTag text) table.put(key, text.getAsString());
                    else if (tag.get(key) instanceof ListTag list) table.put(key, list.stream().map(Tag::getAsString).toList());
                }
            });
        }
        file.clearCachedData();
        file.markDirty();
    }

    @Override public List<Component> description(BaseQuestFile file, ChangeType type) {
        return List.of(Component.translatable("bmpquests.bulk.history", count));
    }
}
