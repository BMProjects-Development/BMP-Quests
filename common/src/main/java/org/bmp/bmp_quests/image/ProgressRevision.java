package org.bmp.bmp_quests.image;

/** Accessed on logical game threads. Team identity is checked separately by each image. */
public final class ProgressRevision {
    private static final java.util.concurrent.atomic.AtomicLong REVISION = new java.util.concurrent.atomic.AtomicLong();
    private ProgressRevision() {}
    public static long current() { return REVISION.get(); }
    public static void invalidate() { REVISION.incrementAndGet(); }
}
