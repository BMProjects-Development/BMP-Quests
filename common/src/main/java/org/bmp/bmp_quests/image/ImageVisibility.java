package org.bmp.bmp_quests.image;

import dev.ftb.mods.ftbquests.quest.*;
import net.minecraft.nbt.*;
import java.util.*;

/** Immutable, portable image settings. Missing quest references are retained and fail closed. */
public record ImageVisibility(Mode mode, long quest, boolean any, List<Condition> conditions) {
    public static final String KEY = "bmpquests_visibility";
    public static final int MAX_CONDITIONS = 64;
    public static final ImageVisibility ALWAYS = new ImageVisibility(Mode.ALWAYS, 0, false, List.of());

    public ImageVisibility {
        conditions = List.copyOf(conditions);
        if (conditions.size() > MAX_CONDITIONS) throw new IllegalArgumentException("Too many image conditions");
    }

    public enum Mode { ALWAYS, FOLLOW, AVAILABLE, COMPLETED, CONDITIONS }
    public record Condition(long quest, Mode mode) {
        public boolean test(BaseQuestFile file, TeamData team) {
            Quest target = file.getQuest(quest);
            if (team == null || target == null || !target.isValid()) return false;
            return switch (mode) {
                case FOLLOW -> target.isVisible(team);
                case AVAILABLE -> team.isCompleted(target) || team.canStartTasks(target);
                case COMPLETED -> team.isCompleted(target);
                default -> false;
            };
        }
    }

    public boolean test(BaseQuestFile file, TeamData team) {
        if (mode == Mode.ALWAYS) return true;
        if (team == null || team.getTeamId().equals(net.minecraft.Util.NIL_UUID)) return false;
        if (mode != Mode.CONDITIONS) return new Condition(quest, mode).test(file, team);
        if (conditions.isEmpty()) return false;
        return any ? conditions.stream().anyMatch(c -> c.test(file, team))
                : conditions.stream().allMatch(c -> c.test(file, team));
    }

    public boolean hasMissingQuest(BaseQuestFile file) {
        if (mode == Mode.ALWAYS) return false;
        return mode == Mode.CONDITIONS ? conditions.stream().anyMatch(c -> file.getQuest(c.quest) == null)
                : file.getQuest(quest) == null;
    }

    public CompoundTag toTag() {
        CompoundTag tag = new CompoundTag();
        tag.putString("mode", mode.name());
        tag.putString("quest", QuestObjectBase.getCodeString(quest));
        tag.putBoolean("any", any);
        ListTag list = new ListTag();
        for (Condition c : conditions) {
            CompoundTag entry = new CompoundTag();
            entry.putString("quest", QuestObjectBase.getCodeString(c.quest));
            entry.putString("mode", c.mode.name());
            list.add(entry);
        }
        tag.put("conditions", list);
        return tag;
    }

    public static ImageVisibility fromTag(CompoundTag tag) {
        if (tag.isEmpty()) return ALWAYS;
        ListTag list = tag.getList("conditions", Tag.TAG_COMPOUND);
        if (list.size() > MAX_CONDITIONS) throw new IllegalArgumentException("Too many image conditions");
        List<Condition> conditions = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            CompoundTag c = list.getCompound(i);
            conditions.add(new Condition(QuestObjectBase.parseCodeString(c.getString("quest")), parseMode(c.getString("mode"))));
        }
        return new ImageVisibility(parseMode(tag.getString("mode")), QuestObjectBase.parseCodeString(tag.getString("quest")), tag.getBoolean("any"), conditions);
    }

    private static Mode parseMode(String value) {
        try { return Mode.valueOf(value); }
        catch (IllegalArgumentException ignored) { return Mode.CONDITIONS; }
    }
}
