package ch.lenglet.bulkbench;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.Locale;

/** Allocation and GC figures. Each strategy runs entirely on the calling thread, so its allocations are visible here. */
final class Jvm {

    private Jvm() {
    }

    /** Bytes allocated so far by the current thread, or -1 if the JVM can't tell. */
    static long allocatedBytes() {
        if (ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean t
                && t.isThreadAllocatedMemorySupported() && t.isThreadAllocatedMemoryEnabled()) {
            return t.getCurrentThreadAllocatedBytes();
        }
        return -1;
    }

    static long gcMillis() {
        long total = 0;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            total += Math.max(0, gc.getCollectionTime());
        }
        return total;
    }

    static String describe() {
        Runtime rt = Runtime.getRuntime();
        return String.format(Locale.ROOT, "Java        : %s (%s), %d CPUs, max heap %,d MB",
                System.getProperty("java.version"), System.getProperty("java.vm.name"),
                rt.availableProcessors(), rt.maxMemory() >> 20);
    }
}
