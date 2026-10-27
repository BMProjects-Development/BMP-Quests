package org.bmp.bmp_quests.bulk;

/** Literal replacement preserves Minecraft formatting, JSON text and line boundaries. */
public enum TextOperation {
    REPLACE, PREFIX, SUFFIX, APPEND, FIND_REPLACE;

    public String apply(String original, String text, String find) {
        return switch (this) {
            case REPLACE -> text;
            case PREFIX -> text + original;
            case SUFFIX -> original + text;
            case APPEND -> original.isEmpty() ? text : original + "\n" + text;
            case FIND_REPLACE -> {
                if (find.isEmpty()) throw new IllegalArgumentException("bmpquests.error.empty_find");
                yield original.replace(find, text);
            }
        };
    }
}
