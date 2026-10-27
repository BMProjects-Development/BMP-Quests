package org.bmp.bmp_quests.bulk;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TextOperationTest {
    @Test void preservesFormattingAndLineBreaks() {
        String original = "§aTitle\n\n{\"text\":\"value\"}\n";
        assertEquals("prefix" + original, TextOperation.PREFIX.apply(original, "prefix", ""));
        assertEquals(original + "suffix", TextOperation.SUFFIX.apply(original, "suffix", ""));
        assertEquals(original + "\nlast\n", TextOperation.APPEND.apply(original, "last\n", ""));
    }
    @Test void replacesLiterallyWithoutRegexOrReplacementExpansion() {
        assertEquals("$1 $1", TextOperation.FIND_REPLACE.apply("a.b a.b", "$1", "a.b"));
        assertEquals("a\nb\n", TextOperation.REPLACE.apply("old", "a\nb\n", ""));
        assertThrows(IllegalArgumentException.class, () -> TextOperation.FIND_REPLACE.apply("old", "new", ""));
    }
    @Test void appendToEmptyHasNoLeadingNewline() {
        assertEquals("first", TextOperation.APPEND.apply("", "first", ""));
    }
}
