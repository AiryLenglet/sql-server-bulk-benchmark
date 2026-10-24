package ch.lenglet.bulkbench;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.ToDoubleFunction;
import java.util.stream.Collectors;

/** Console output, plus one CSV line per measured run in results/. */
final class Report {

    private static final String CSV_HEADER = "strategy,iteration,rows,seconds,rows_per_s,read_share,"
            + "execute_batch_share,alloc_bytes_per_row,gc_ms,server_bulk_loaded_rows,checksum,ok,problem";

    private final Path csv;

    Report() throws IOException {
        Path dir = Paths.get("results");
        Files.createDirectories(dir);
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        csv = dir.resolve("results-" + stamp + ".csv");
        Files.writeString(csv, CSV_HEADER + "\n");
    }

    void printRun(String label, RunResult r) {
        if (!r.completed()) {
            System.out.printf(Locale.ROOT, "%-8s %-12s FAILED %s%n", label, r.strategy(), r.problem());
            return;
        }
        System.out.printf(Locale.ROOT,
                "%-8s %-12s %,11d rows %7.1f s %,9.0f rows/s | read %4s | executeBatch %4s | %5s B/row | GC %,6d ms"
                        + " | bulk-loaded %11s | %s%n",
                label, r.strategy(), r.rows(), r.seconds(), r.rowsPerSecond(), pct(r.readShare()),
                pct(r.executeBatchShare()), num(r.bytesPerRow()), r.gcMillis(), count(r.serverBulkLoadedRows()),
                r.ok() ? "OK" : "PROBLEM: " + r.problem());
    }

    void appendCsv(RunResult r) throws IOException {
        boolean done = r.completed();
        String line = String.join(",",
                r.strategy(),
                Integer.toString(r.iteration()),
                Long.toString(r.rows()),
                done ? fixed(r.seconds()) : "",
                done ? fixed(r.rowsPerSecond()) : "",
                fixed(r.readShare()),
                fixed(r.executeBatchShare()),
                fixed(r.bytesPerRow()),
                done ? Long.toString(r.gcMillis()) : "",
                done ? Long.toString(r.serverBulkLoadedRows()) : "",
                r.verification() == null ? "" : Integer.toString(r.verification().checksum()),
                Boolean.toString(r.ok()),
                r.problem() == null ? "" : '"' + r.problem().replace('"', '\'') + '"');
        Files.writeString(csv, line + "\n", StandardOpenOption.APPEND);
    }

    void printSummary(List<RunResult> results, Set<String> bulkLoadStrategies, long rows) {
        Map<String, List<RunResult>> byStrategy = new LinkedHashMap<>();
        for (RunResult r : results) {
            byStrategy.computeIfAbsent(r.strategy(), k -> new ArrayList<>()).add(r);
        }
        Map<String, Double> medianSeconds = new LinkedHashMap<>();
        byStrategy.forEach((name, runs) -> {
            List<RunResult> ok = successful(runs);
            if (!ok.isEmpty()) {
                medianSeconds.put(name, median(ok, RunResult::seconds));
            }
        });
        double fastest = medianSeconds.values().stream().mapToDouble(Double::doubleValue).min().orElse(Double.NaN);

        String row = "%-12s %8s %10s %8s %6s %13s %7s %14s %8s%n";
        System.out.println();
        System.out.println("Summary: medians over the measured runs");
        System.out.printf(Locale.ROOT, row,
                "strategy", "seconds", "rows/s", "vs best", "read", "executeBatch", "B/row", "bulk-loaded", "runs ok");
        byStrategy.forEach((name, runs) -> {
            List<RunResult> ok = successful(runs);
            String okRuns = ok.size() + "/" + runs.size();
            if (ok.isEmpty()) {
                System.out.printf(Locale.ROOT, row, name, "-", "-", "-", "-", "-", "-", "-", okRuns);
                return;
            }
            double seconds = medianSeconds.get(name);
            System.out.printf(Locale.ROOT, row, name,
                    String.format(Locale.ROOT, "%.1f", seconds),
                    String.format(Locale.ROOT, "%,.0f", rows / seconds),
                    String.format(Locale.ROOT, "%.2fx", seconds / fastest),
                    pct(median(ok, RunResult::readShare)),
                    pct(median(ok, RunResult::executeBatchShare)),
                    num(median(ok, RunResult::bytesPerRow)),
                    count(minBulkLoaded(ok)),
                    okRuns);
        });

        System.out.println();
        if (medianSeconds.containsKey("batch") && medianSeconds.containsKey("bulkcopy")) {
            System.out.printf(Locale.ROOT, "batch takes %.2fx as long as bulkcopy.%n",
                    medianSeconds.get("batch") / medianSeconds.get("bulkcopy"));
        }
        printBulkLoadCheck(byStrategy, bulkLoadStrategies, rows);

        Set<Integer> checksums = results.stream().filter(RunResult::ok)
                .map(r -> r.verification().checksum()).collect(Collectors.toSet());
        System.out.println(checksums.size() <= 1
                ? "Content check: all successful runs produced identical #stg_trx content."
                : "Content check: runs produced DIFFERENT content (checksums " + checksums + ").");
        System.out.println("Per-run figures: " + csv.toAbsolutePath());
    }

    /** Uses SQL Server's bulk-load counter to confirm the batch strategy really went through bulk copy. */
    private static void printBulkLoadCheck(Map<String, List<RunResult>> byStrategy, Set<String> bulkLoadStrategies,
                                           long rows) {
        boolean counterWorks = bulkLoadStrategies.stream()
                .anyMatch(name -> minBulkLoaded(successful(byStrategy.getOrDefault(name, List.of()))) >= rows);
        for (String name : bulkLoadStrategies) {
            List<RunResult> ok = successful(byStrategy.getOrDefault(name, List.of()));
            if (ok.isEmpty() || minBulkLoaded(ok) >= rows) {
                continue;
            }
            System.out.println(counterWorks
                    ? "WARNING: SQL Server didn't receive the " + name + " runs as bulk loads: "
                            + "the driver fell back to normal inserts."
                    : "Note: SQL Server's bulk-load counter didn't register any run, so it can't confirm how "
                            + name + " was loaded.");
        }
    }

    private static List<RunResult> successful(List<RunResult> runs) {
        return runs.stream().filter(RunResult::ok).toList();
    }

    private static long minBulkLoaded(List<RunResult> runs) {
        return runs.stream().mapToLong(RunResult::serverBulkLoadedRows).min().orElse(-1);
    }

    private static double median(List<RunResult> runs, ToDoubleFunction<RunResult> metric) {
        double[] values = runs.stream().mapToDouble(metric).filter(v -> !Double.isNaN(v)).sorted().toArray();
        if (values.length == 0) {
            return Double.NaN;
        }
        int mid = values.length / 2;
        return values.length % 2 == 1 ? values[mid] : (values[mid - 1] + values[mid]) / 2;
    }

    private static String pct(double share) {
        return Double.isNaN(share) ? "-" : String.format(Locale.ROOT, "%.0f%%", share * 100);
    }

    private static String num(double value) {
        return Double.isNaN(value) ? "-" : String.format(Locale.ROOT, "%,.0f", value);
    }

    private static String count(long n) {
        return n < 0 ? "n/a" : String.format(Locale.ROOT, "%,d", n);
    }

    private static String fixed(double value) {
        return Double.isNaN(value) ? "" : String.format(Locale.ROOT, "%.4f", value);
    }
}
