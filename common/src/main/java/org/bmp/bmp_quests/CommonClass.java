package org.bmp.bmp_quests;

import dev.ftb.mods.ftblibrary.util.NetworkHelper;
import org.bmp.bmp_quests.net.BulkEditMessage;

public final class CommonClass {
    private CommonClass() {}
    public static void init() {
        NetworkHelper.registerC2S(BulkEditMessage.TYPE, BulkEditMessage.STREAM_CODEC, BulkEditMessage::handle);
        Constants.LOG.info("BMP Quests initialized");
    }
}
