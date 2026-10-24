package ch.lenglet.bulkbench;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Loads the same source rows into a #temp table with each strategy and compares them.
 * Source table and #temp share one database; reading and writing still use two connections, as in the real job.
 */
public final class BulkBench {

    private final Config cfg;
    private final Database db;

    private BulkBench(Config cfg, Database db) {
        this.cfg = cfg;
        this.db = db;
    }

    public static void main(String[] args) throws Exception {
        if (Arrays.asList(args).contains("--help")) {
            System.out.println(Config.USAGE);
            return;
        }
        Config cfg = Config.parse(args);
        List<LoadStrategy> strategies = cfg.strategies().stream().map(LoadStrategy::byName).toList();
        Database db = new Database(cfg);

        db.awaitServer();
        db.setUp();
        db.ensureSourceTable(cfg.rows(), cfg.reseed());
        Database.Expected expected = db.expected(cfg.rows()); // also pulls the source into the buffer pool

        System.out.println(Jvm.describe());
        System.out.println(db.describeServer());
        System.out.printf(Locale.ROOT, "Run         : %,d rows into #stg_trx, batch size %,d, %d iteration(s), %s%n",
                cfg.rows(), cfg.batchSize(), cfg.iterations(), String.join(", ", cfg.strategies()));
        if (!cfg.extraProperties().isEmpty()) {
            System.out.println("Extra JDBC  : " + cfg.extraProperties());
        }
        System.out.println();

        BulkBench bench = new BulkBench(cfg, db);
        Report report = new Report();

        long warmupRows = Math.min(cfg.warmupRows(), cfg.rows());
        if (warmupRows > 0) {
            Database.Expected warmupExpected = db.expected(warmupRows);
            for (LoadStrategy s : strategies) {
                report.printRun("warm-up", bench.runSafely(s, 0, warmupRows, warmupExpected));
            }
        }

        List<RunResult> results = new ArrayList<>();
        for (int i = 1; i <= cfg.iterations(); i++) {
            List<LoadStrategy> order = new ArrayList<>(strategies);
            Collections.rotate(order, -(i - 1)); // new order each iteration, so drift doesn't favour one strategy
            for (LoadStrategy s : order) {
                RunResult r = bench.runSafely(s, i, cfg.rows(), expected);
                results.add(r);
                report.printRun(i + "/" + cfg.iterations(), r);
                report.appendCsv(r);
            }
        }

        Set<String> bulkLoadStrategies = strategies.stream()
                .filter(LoadStrategy::usesBulkLoad).map(LoadStrategy::name).collect(Collectors.toSet());
        report.printSummary(results, bulkLoadStrategies, cfg.rows());
    }

    private RunResult runSafely(LoadStrategy s, int iteration, long rows, Database.Expected expected)
            throws InterruptedException {
        try {
            return run(s, iteration, rows, expected);
        } catch (SQLException | RuntimeException e) {
            return RunResult.failed(s.name(), iteration, rows, e);
        } finally {
            Thread.sleep(cfg.pauseMillis()); // lets SQL Server finish dropping the previous #stg_trx
        }
    }

    private RunResult run(LoadStrategy s, int iteration, long rows, Database.Expected expected) throws SQLException {
        try (Connection owner = db.connect(s.ownerProperties());
             Connection reader = db.connect("");
             Statement sql = owner.createStatement()) {
            sql.execute("DROP TABLE IF EXISTS #stg_trx");
            sql.execute(Schema.CREATE_TEMP);

            long bulkBefore = Database.bulkLoadedRows(sql);
            long allocBefore = Jvm.allocatedBytes();
            long gcBefore = Jvm.gcMillis();
            long t0 = System.nanoTime();

            LoadStrategy.Timings timings = s.load(reader, owner, rows, cfg.batchSize());

            long elapsed = System.nanoTime() - t0;
            long allocated = allocBefore < 0 ? -1 : Jvm.allocatedBytes() - allocBefore;
            long gc = Jvm.gcMillis() - gcBefore;
            long bulkAfter = Database.bulkLoadedRows(sql);
            long bulkLoaded = bulkBefore < 0 || bulkAfter < 0 ? -1 : bulkAfter - bulkBefore;

            Database.Verification verification = Database.verifyTemp(sql);
            sql.execute("DROP TABLE #stg_trx");

            return new RunResult(s.name(), iteration, rows, elapsed, timings, allocated, gc, bulkLoaded,
                    verification, verification.problemVersus(expected));
        }
    }
}
