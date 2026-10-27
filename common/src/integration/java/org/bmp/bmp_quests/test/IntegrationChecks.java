package org.bmp.bmp_quests.test;

import dev.ftb.mods.ftbquests.quest.*;
import dev.ftb.mods.ftbquests.quest.reward.*;
import dev.ftb.mods.ftbquests.quest.translation.TranslationKey;
import io.netty.buffer.Unpooled;
import net.minecraft.nbt.*;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.bmp.bmp_quests.Constants;
import org.bmp.bmp_quests.bulk.*;
import org.bmp.bmp_quests.image.*;
import java.nio.file.*;
import java.util.*;

public final class IntegrationChecks {
    private static int assertions;
    private IntegrationChecks() {}
    private static void check(boolean value, String message) {
        assertions++;
        if (!value) throw new AssertionError(message);
    }
    private static CompoundTag data(QuestObjectBase object) {
        CompoundTag tag = new CompoundTag(); object.writeData(tag, object.getQuestFile().holderLookup()); return tag;
    }
    private static CompoundTag plan(List<Quest> quests) {
        CompoundTag tag = new CompoundTag();
        tag.putLongArray("targets", quests.stream().mapToLong(q -> q.id).toArray());
        tag.putString("locale", "ru_ru"); return tag;
    }
    private static void reject(ServerQuestFile file, CompoundTag plan, String message) {
        boolean rejected = false;
        try { BulkEditor.prepare(file, plan); } catch (IllegalArgumentException expected) { rejected = true; }
        check(rejected, message);
    }

    public static void run(ServerQuestFile file) {
        try {
            Chapter chapter = new Chapter(file.newID(), file, file.getDefaultChapterGroup()); chapter.onCreated();
            file.getTranslationManager().addTranslation(chapter, "en_us", TranslationKey.TITLE, "BMP integration checks");
            List<Quest> quests = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                Quest quest = new Quest(file.newID(), chapter); quest.onCreated(); quests.add(quest);
                quest.setPosition(i % 10 * 2, i / 10 * 2);
                file.getTranslationManager().addTranslation(quest, "en_us", TranslationKey.TITLE, "English " + i);
                file.getTranslationManager().addTranslation(quest, "ru_ru", TranslationKey.TITLE, "Русский " + i);
                file.getTranslationManager().addTranslation(quest, "ru_ru", TranslationKey.QUEST_DESC, List.of("§aСтрока " + i, "", "End"));
            }
            Quest a = quests.get(0), b = quests.get(1);
            TeamData team = new TeamData(UUID.randomUUID(), file);
            file.addData(team, true);
            ChapterImage image = new ChapterImage(file.newID(), chapter); image.onCreated();
            DynamicImage dynamic = (DynamicImage)(Object)image;
            check(image.shouldShowImage(team), "legacy image visible");
            CompoundTag hidden = data(b); hidden.putBoolean("invisible", true); b.readData(hidden, file.holderLookup());
            dynamic.bmpquests$setVisibility(new ImageVisibility(ImageVisibility.Mode.FOLLOW, b.id, false, List.of()));
            check(!image.shouldShowImage(team), "follows invisible quest");
            team.setCompleted(b.id, new Date());
            check(image.shouldShowImage(team), "progress invalidates image cache");
            team.setCompleted(b.id, null);
            check(!image.shouldShowImage(team), "progress reset hides image");
            dynamic.bmpquests$setVisibility(new ImageVisibility(ImageVisibility.Mode.COMPLETED, a.id, false, List.of()));
            check(!image.shouldShowImage(team), "incomplete quest hidden");
            team.setCompleted(a.id, new Date());
            check(image.shouldShowImage(team), "completed quest shows image");
            List<ImageVisibility.Condition> conditions = List.of(new ImageVisibility.Condition(a.id, ImageVisibility.Mode.COMPLETED), new ImageVisibility.Condition(b.id, ImageVisibility.Mode.COMPLETED));
            dynamic.bmpquests$setVisibility(new ImageVisibility(ImageVisibility.Mode.CONDITIONS, 0, false, conditions));
            check(!image.shouldShowImage(team), "ALL conditions");
            dynamic.bmpquests$setVisibility(new ImageVisibility(ImageVisibility.Mode.CONDITIONS, 0, true, conditions));
            check(image.shouldShowImage(team), "ANY conditions");
            TeamData other = new TeamData(UUID.randomUUID(), file);
            check(!image.shouldShowImage(other), "switching team invalidates image cache");
            check(!dynamic.bmpquests$getVisibility().test(file, null), "missing progress fails closed");
            check(!dynamic.bmpquests$getVisibility().test(file, new TeamData(net.minecraft.Util.NIL_UUID, file)), "loading team fails closed");
            ChapterImage roundtrip = new ChapterImage(file.newID(), chapter);
            roundtrip.readData(data(image), file.holderLookup());
            check(((DynamicImage)(Object)roundtrip).bmpquests$getVisibility().equals(dynamic.bmpquests$getVisibility()), "image NBT round trip");
            RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), file.server.registryAccess());
            image.writeNetData(buffer); roundtrip.readNetData(buffer); buffer.release();
            check(((DynamicImage)(Object)roundtrip).bmpquests$getVisibility().equals(dynamic.bmpquests$getVisibility()), "image network round trip");
            dynamic.bmpquests$setVisibility(new ImageVisibility(ImageVisibility.Mode.COMPLETED, Long.MAX_VALUE, false, List.of()));
            check(!image.shouldShowImage(team), "missing quest fails closed");
            check(dynamic.bmpquests$getVisibility().hasMissingQuest(file), "missing quest warning");
            dynamic.bmpquests$setVisibility(new ImageVisibility(ImageVisibility.Mode.AVAILABLE, quests.get(2).id, false, List.of()));
            quests.get(2).addDependency(b); team.clearCachedProgress();
            check(!image.shouldShowImage(team), "availability uses dependencies");
            team.setCompleted(b.id, new Date());
            check(image.shouldShowImage(team), "availability refreshes on dependency completion");

            for (int count : new int[]{2,10,50,100}) {
                CompoundTag batch = plan(quests.subList(0, count));
                CompoundTag properties = new CompoundTag(); properties.putDouble("size", 2.5); batch.put("properties", properties);
                CompoundTag text = new CompoundTag(), operation = new CompoundTag();
                operation.putString("mode", "PREFIX"); operation.putString("text", "[Test] "); text.put("title", operation); batch.put("text", text);
                var transaction = BulkEditor.prepare(file, batch);
                file.getHistoryStack().addAndApply(file, transaction);
                for (int i = 0; i < count; i++) {
                    Quest q = quests.get(i);
                    check(data(q).getDouble("size") == 2.5, "batch size " + count);
                    check(file.getTranslationManager().getStringTranslation(q, "ru_ru", TranslationKey.TITLE).orElseThrow().equals("[Test] Русский " + i), "localized prefix " + count);
                    check(file.getTranslationManager().getStringTranslation(q, "en_us", TranslationKey.TITLE).orElseThrow().equals("English " + i), "other locale preserved " + count);
                }
                check(file.getHistoryStack().tryUndo(file), "one-action undo " + count);
                check(file.getTranslationManager().getStringTranslation(a, "ru_ru", TranslationKey.TITLE).orElseThrow().equals("Русский 0"), "undo text " + count);
                check(file.getHistoryStack().tryRedo(file), "one-action redo " + count);
                check(file.getHistoryStack().tryUndo(file), "restore fixture " + count);
            }
            CompoundTag batch = plan(List.of(a,b)), text = new CompoundTag(), op = new CompoundTag();
            op.putString("mode", "APPEND"); op.putString("text", "Последняя\n"); text.put("description", op); batch.put("text", text);
            var multiline = BulkEditor.prepare(file, batch); check(multiline.apply(file), "multiline append");
            check(file.getTranslationManager().getStringListTranslation(a, "ru_ru", TranslationKey.QUEST_DESC).orElseThrow().equals(List.of("§aСтрока 0", "", "End", "Последняя", "")), "formatting and trailing newline preserved");
            check(multiline.applyUndo(file), "multiline undo");

            batch = plan(List.of(a,b)); CompoundTag deps = new CompoundTag(); deps.putString("action", "ADD"); deps.putLongArray("quests", new long[]{a.id}); batch.put("dependencies", deps);
            reject(file, batch, "self dependency rejected");
            a.addDependency(b); b.addDependency(quests.get(2)); team.clearCachedProgress();
            batch = plan(List.of(quests.get(2))); deps = new CompoundTag(); deps.putString("action", "ADD"); deps.putLongArray("quests", new long[]{a.id}); batch.put("dependencies", deps);
            reject(file, batch, "indirect cycle rejected");
            a.clearDependencies(); b.clearDependencies(); quests.get(2).clearDependencies();
            batch = plan(List.of(a,b)); deps = new CompoundTag(); deps.putString("action", "ADD"); deps.putLongArray("quests", new long[]{quests.get(3).id}); batch.put("dependencies", deps);
            var dependencyEdit = BulkEditor.prepare(file, batch); check(dependencyEdit.apply(file), "dependency add");
            check(a.hasDependency(quests.get(3)) && b.hasDependency(quests.get(3)), "dependencies applied");
            check(dependencyEdit.applyUndo(file), "dependency undo");

            Reward sourceReward = RewardType.requireCreateReward(file.newID(), a, "ftbquests:xp"); sourceReward.onCreated();
            file.getTranslationManager().addTranslation(sourceReward, "en_us", TranslationKey.TITLE, "English XP");
            file.getTranslationManager().addTranslation(sourceReward, "ru_ru", TranslationKey.TITLE, "Опыт");
            batch = plan(List.of(a,b)); CompoundTag rewards = new CompoundTag(); rewards.putString("action", "COPY"); rewards.putLong("source", a.id); batch.put("rewards", rewards);
            var rewardCopy = BulkEditor.prepare(file, batch); check(rewardCopy.apply(file), "reward copy");
            Reward copy = b.getRewards().iterator().next();
            check(copy.id != sourceReward.id && copy.getQuest() == b, "independent reward IDs and parents");
            check(file.getTranslationManager().getStringTranslation(copy, "ru_ru", TranslationKey.TITLE).orElseThrow().equals("Опыт"), "reward translations copied");
            check(rewardCopy.applyUndo(file) && b.getRewards().isEmpty(), "reward copy undo");
            batch = plan(List.of(a)); rewards = new CompoundTag(); rewards.putString("action", "CLEAR"); batch.put("rewards", rewards);
            var clearRewards = BulkEditor.prepare(file, batch); check(clearRewards.apply(file) && a.getRewards().isEmpty(), "reward clear");
            check(clearRewards.applyUndo(file), "reward clear undo");
            check(file.getTranslationManager().getStringTranslation(file.getBase(sourceReward.id), "ru_ru", TranslationKey.TITLE).orElseThrow().equals("Опыт"), "deleted reward translations restored");

            batch = plan(List.of(a,b)); rewards = new CompoundTag(); rewards.putString("action", "ADD");
            CompoundTag template = new CompoundTag(); template.putString("type", "ftbquests:xp");
            CompoundTag xpData = new CompoundTag(); xpData.putInt("xp", 17); template.put("data", xpData);
            template.putString("title", "Добавлено"); rewards.put("template", template); batch.put("rewards", rewards);
            var addReward = BulkEditor.prepare(file, batch); check(addReward.apply(file), "add configured reward");
            check(a.getRewards().size() == 2 && b.getRewards().size() == 1, "reward appended without replacing existing rewards");
            batch = plan(List.of(a,b)); rewards = new CompoundTag(); rewards.putString("action", "AUTO"); rewards.putString("auto", "enabled"); batch.put("rewards", rewards);
            var auto = BulkEditor.prepare(file, batch); check(auto.apply(file), "reward auto claim");
            check(b.getRewards().stream().allMatch(r -> data(r).getString("auto").equals("enabled")), "auto claim written");
            check(auto.applyUndo(file), "auto claim undo");
            batch = plan(List.of(a,b)); rewards = new CompoundTag(); rewards.putString("action", "REMOVE_TYPE"); rewards.putString("type", "ftbquests:xp"); batch.put("rewards", rewards);
            var removeType = BulkEditor.prepare(file, batch); check(removeType.apply(file), "remove reward type");
            check(a.getRewards().isEmpty() && b.getRewards().isEmpty(), "reward type removed");
            check(removeType.applyUndo(file), "remove type undo"); check(addReward.applyUndo(file), "add reward undo");
            batch = plan(List.of(a,b)); deps = new CompoundTag(); deps.putString("action", "ADD"); deps.putLongArray("quests", new long[]{quests.get(3).id}); batch.put("dependencies", deps);
            var addDep = BulkEditor.prepare(file, batch); check(addDep.apply(file), "prepare dependency removal");
            deps.putString("action", "REMOVE"); var removeDep = BulkEditor.prepare(file, batch);
            check(removeDep.apply(file) && !a.hasDependency(quests.get(3)), "specific dependency removed");
            check(removeDep.applyUndo(file), "remove dependency undo");
            deps.putString("action", "CLEAR"); var clearDeps = BulkEditor.prepare(file, batch);
            check(clearDeps.apply(file) && !a.hasDependencies() && !b.hasDependencies(), "all dependencies cleared");
            check(clearDeps.applyUndo(file), "clear dependency undo"); check(addDep.applyUndo(file), "dependency fixture restored");
            batch = plan(List.of(a,b)); batch.putLongArray("targets", new long[]{a.id, Long.MAX_VALUE});
            CompoundTag invalidProps = new CompoundTag(); invalidProps.putDouble("size", 8); batch.put("properties", invalidProps);
            CompoundTag original = data(a); reject(file, batch, "stale selection rejected"); check(data(a).equals(original), "stale batch leaves first quest untouched");
            batch = plan(List.of(a)); invalidProps = new CompoundTag(); invalidProps.putString("command", "unsupported"); batch.put("properties", invalidProps);
            reject(file, batch, "unknown property rejected");
            var unauthorized = new net.minecraft.server.level.ServerPlayer(file.server, file.server.overworld(),
                    new com.mojang.authlib.GameProfile(UUID.randomUUID(), "BMPNonEditor"), net.minecraft.server.level.ClientInformation.createDefault());
            check(!dev.ftb.mods.ftbquests.integration.PermissionsHelper.hasEditorPermission(unauthorized), "test player has no editor permissions");
            dev.architectury.networking.NetworkManager.PacketContext context = (dev.architectury.networking.NetworkManager.PacketContext)java.lang.reflect.Proxy.newProxyInstance(
                    IntegrationChecks.class.getClassLoader(), new Class<?>[]{dev.architectury.networking.NetworkManager.PacketContext.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("getPlayer")) return unauthorized;
                        if (method.getName().equals("queue")) { ((Runnable)args[0]).run(); return null; }
                        return null;
                    });
            batch = plan(List.of(a,b)); invalidProps = new CompoundTag(); invalidProps.putDouble("size", 8); batch.put("properties", invalidProps);
            org.bmp.bmp_quests.net.BulkEditMessage.handle(new org.bmp.bmp_quests.net.BulkEditMessage(batch), context);
            check(data(a).equals(original), "forged non-editor request rejected");

            CompoundTag imageBatch = new CompoundTag(); imageBatch.putLongArray("targets", new long[]{image.id}); imageBatch.putString("locale", "en_us"); imageBatch.putBoolean("images", true);
            imageBatch.put("visibility", ImageVisibility.ALWAYS.toTag());
            var imageEdit = BulkEditor.prepare(file, imageBatch); check(imageEdit.apply(file), "bulk image edit");
            check(((DynamicImage)(Object)image).bmpquests$getVisibility().equals(ImageVisibility.ALWAYS), "image conditions cleared");
            check(imageEdit.applyUndo(file), "image batch undo");

            long imageId = image.id, questId = a.id;
            ImageVisibility saved = dynamic.bmpquests$getVisibility();
            file.saveNow(); file.load(true, false);
            check(((DynamicImage)(Object)file.getBase(imageId)).bmpquests$getVisibility().equals(saved), "image survives disk reload");
            check(file.getTranslationManager().getStringTranslation(file.getBase(questId), "ru_ru", TranslationKey.TITLE).orElseThrow().equals("Русский 0"), "translations survive disk reload");
            String result = "BMPQUESTS_INTEGRATION_PASS assertions=" + assertions;
            Constants.LOG.info(result); Files.writeString(Path.of("integration-result.txt"), result);
        } catch (Exception | AssertionError ex) {
            Constants.LOG.error("BMPQUESTS_INTEGRATION_FAIL", ex);
            throw new IllegalStateException("BMP Quests integration checks failed", ex);
        } finally {
            file.server.halt(false);
        }
    }
}
