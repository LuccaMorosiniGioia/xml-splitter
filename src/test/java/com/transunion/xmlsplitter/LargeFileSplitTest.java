package com.transunion.xmlsplitter;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.util.FileSystemUtils;

import com.transunion.xmlsplitter.core.PartSummary;
import com.transunion.xmlsplitter.core.SplitRequest;
import com.transunion.xmlsplitter.core.SplitResult;
import com.transunion.xmlsplitter.core.SplitSettings;
import com.transunion.xmlsplitter.core.XmlSplitService;
import com.transunion.xmlsplitter.support.LargeXmlGenerator;

/**
 * Opt-in load test ({@code mvnw test -Plarge-file}): generates a 300 MB+ input and splits it with a 128 MB heap,
 * sampling the heap to show that memory stays flat. Writes {@code target/large-file-report.md}.
 * Options: {@code -Dlarge.sizeMb=320}, {@code -Dlarge.dir=<folder>}, {@code -Dlarge.keep=true}.
 */
@Tag("large")
class LargeFileSplitTest {

    private static final long MB = 1024 * 1024;

    @Test
    void splitsA300MbFileWithFlatMemory() throws Exception {
        // Big files go to the temp folder (kept out of synced folders such as OneDrive) and are deleted afterwards.
        Path dir = Files.createDirectories(Path.of(System.getProperty("large.dir",
                Path.of(System.getProperty("java.io.tmpdir"), "xml-splitter-large").toString())));
        Path input = dir.resolve("large-input.xml");
        long targetMb = Long.getLong("large.sizeMb", 320);

        long generationStart = System.nanoTime();
        LargeXmlGenerator.Stats stats = LargeXmlGenerator.generate(input, targetMb * MB);
        long generationMs = (System.nanoTime() - generationStart) / 1_000_000;

        HeapSampler sampler = new HeapSampler();
        sampler.start();
        SplitResult result = new XmlSplitService().split(new SplitRequest(input, dir.resolve("parts"),
                SplitSettings.defaults(5000), true, null));
        sampler.stop();

        assertThat(result.totalPolicies()).isEqualTo(stats.policies());
        assertThat(result.totalTransactions()).isEqualTo(stats.transactions());
        assertThat(result.parts()).allSatisfy(part ->
                assertThat(part.transactions() <= 5000 || part.policies() == 1).isTrue());
        assertThat(Runtime.getRuntime().maxMemory()).as("run with -Xmx128m (profile large-file)")
                .isLessThanOrEqualTo(136 * MB);

        String report = report(stats, generationMs, result, sampler);
        Files.writeString(Files.createDirectories(Path.of("target")).resolve("large-file-report.md"), report);
        System.out.println(report);
        if (!Boolean.getBoolean("large.keep")) {
            FileSystemUtils.deleteRecursively(dir);
        }
    }

    private static String report(LargeXmlGenerator.Stats stats, long generationMs, SplitResult result,
            HeapSampler sampler) {
        double seconds = result.elapsed().toMillis() / 1000.0;
        List<PartSummary> allButLast = result.parts().subList(0, result.parts().size() - 1);
        long minPart = allButLast.stream().mapToLong(PartSummary::transactions).min().orElse(0);
        long maxPart = result.parts().stream().mapToLong(PartSummary::transactions).max().orElse(0);
        double averageFill = allButLast.stream().mapToLong(PartSummary::transactions).average().orElse(0) / 5000 * 100;
        StringBuilder text = new StringBuilder();
        text.append("# Large-file run\n\n");
        text.append(String.format(Locale.ROOT, "- JVM: %s %s, max heap %d MB%n", System.getProperty("java.vm.name"),
                System.getProperty("java.version"), Runtime.getRuntime().maxMemory() / MB));
        text.append(String.format(Locale.ROOT, "- Input: %.1f MB, %,d policies, %,d transactions (generated in %.1f s)%n",
                stats.bytes() / (double) MB, stats.policies(), stats.transactions(), generationMs / 1000.0));
        text.append(String.format(Locale.ROOT, "- Output: %d parts; all but the last hold %,d to %,d transactions "
                + "(X = 5000), average fill %.1f%%; last part %,d%n", result.parts().size(), minPart, maxPart,
                averageFill, result.parts().get(result.parts().size() - 1).transactions()));
        text.append(String.format(Locale.ROOT, "- Split + verify + publish: %.1f s (%.1f MB/s)%n", seconds,
                stats.bytes() / (double) MB / seconds));
        text.append(String.format(Locale.ROOT, "- Heap used, peak sampled (includes garbage not yet collected): %d MB%n",
                sampler.peakUsed() / MB));
        text.append(String.format(Locale.ROOT, "- Old generation (retained objects), peak: %d MB%n%n",
                sampler.peakOld() / MB));
        text.append("| elapsed (s) | heap used (MB) | old generation (MB) |\n|---|---|---|\n");
        for (long[] sample : sampler.timeline()) {
            text.append(String.format(Locale.ROOT, "| %.1f | %d | %d |%n", sample[0] / 1000.0, sample[1] / MB,
                    sample[2] / MB));
        }
        return text.toString();
    }

    /**
     * Samples heap usage every 20 ms and keeps a timeline row every 500 ms. Old-generation occupancy is what
     * would grow if the splitter retained data; young-generation usage just saws up and down with garbage.
     */
    private static final class HeapSampler implements Runnable {
        private final List<long[]> timeline = new ArrayList<>();
        private final List<MemoryPoolMXBean> oldPools = ManagementFactory.getMemoryPoolMXBeans().stream()
                .filter(pool -> pool.getType() == MemoryType.HEAP)
                .filter(pool -> pool.getName().contains("Old") || pool.getName().contains("Tenured"))
                .toList();
        private volatile boolean running = true;
        private Thread thread;
        private long peakUsed;
        private long peakOld;

        void start() {
            thread = new Thread(this, "heap-sampler");
            thread.setDaemon(true);
            thread.start();
        }

        void stop() throws InterruptedException {
            running = false;
            thread.join();
        }

        @Override
        public void run() {
            long start = System.currentTimeMillis();
            long lastRow = -1000;
            while (running) {
                long used = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
                long old = oldPools.stream().map(MemoryPoolMXBean::getUsage).mapToLong(MemoryUsage::getUsed).sum();
                synchronized (this) {
                    peakUsed = Math.max(peakUsed, used);
                    peakOld = Math.max(peakOld, old);
                    long elapsed = System.currentTimeMillis() - start;
                    if (elapsed - lastRow >= 500) {
                        timeline.add(new long[] {elapsed, used, old});
                        lastRow = elapsed;
                    }
                }
                try {
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }

        synchronized long peakUsed() {
            return peakUsed;
        }

        synchronized long peakOld() {
            return peakOld;
        }

        synchronized List<long[]> timeline() {
            return List.copyOf(timeline);
        }
    }
}
