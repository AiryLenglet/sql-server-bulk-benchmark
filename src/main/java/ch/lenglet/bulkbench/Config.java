package ch.lenglet.bulkbench;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Command-line options, all optional and written as --name=value. */
record Config(
        String serverUrl,
        String user,
        String password,
        String extraProperties,
        long rows,
        int iterations,
        int batchSize,
        long warmupRows,
        long pauseMillis,
        List<String> strategies,
        boolean reseed) {

    /** The SA password set in docker-compose.yml. Local benchmark container only. */
    static final String DEFAULT_PASSWORD = "Bench_Passw0rd!";

    static final String USAGE = """
            Usage: ./gradlew run --args="[options]"
              --rows=10000000          rows in the source table, and rows loaded per run
              --iterations=3           measured runs per strategy
              --batch-size=50000       rows per executeBatch() and per bulk-copy batch
              --strategies=bulkcopy,batch,server-side
              --warmup-rows=200000     rows per warm-up run (0 = no warm-up)
              --pause-ms=2000          pause between runs
              --extra=...              extra JDBC properties for every connection, e.g. packetSize=32767
              --url=jdbc:sqlserver://localhost:14330;encrypt=false
              --user=sa --password=... (default: $MSSQL_SA_PASSWORD, else the docker-compose password)
              --reseed                 drop and regenerate the source table
            """;

    private static final Set<String> KEYS = Set.of("rows", "iterations", "batch-size", "strategies",
            "warmup-rows", "pause-ms", "extra", "url", "user", "password", "reseed");

    static Config parse(String[] args) {
        Map<String, String> o = new HashMap<>();
        for (String arg : args) {
            int eq = arg.indexOf('=');
            String key = !arg.startsWith("--") ? null : eq < 0 ? arg.substring(2) : arg.substring(2, eq);
            if (key == null || !KEYS.contains(key)) {
                throw new IllegalArgumentException("Unknown argument: " + arg + "\n\n" + USAGE);
            }
            o.put(key, eq < 0 ? "true" : arg.substring(eq + 1));
        }
        Config cfg = new Config(
                trimSemicolons(o.getOrDefault("url", "jdbc:sqlserver://localhost:14330;encrypt=false")),
                o.getOrDefault("user", "sa"),
                o.getOrDefault("password", System.getenv().getOrDefault("MSSQL_SA_PASSWORD", DEFAULT_PASSWORD)),
                trimSemicolons(o.getOrDefault("extra", "")),
                number(o.getOrDefault("rows", "10000000")),
                (int) number(o.getOrDefault("iterations", "3")),
                (int) number(o.getOrDefault("batch-size", "50000")),
                number(o.getOrDefault("warmup-rows", "200000")),
                number(o.getOrDefault("pause-ms", "2000")),
                Arrays.stream(o.getOrDefault("strategies", "bulkcopy,batch,server-side").split(","))
                        .map(String::trim).filter(s -> !s.isEmpty()).toList(),
                Boolean.parseBoolean(o.getOrDefault("reseed", "false")));
        if (cfg.rows < 1 || cfg.iterations < 1 || cfg.batchSize < 1 || cfg.warmupRows < 0 || cfg.strategies.isEmpty()) {
            throw new IllegalArgumentException("rows, iterations and batch-size must be positive, "
                    + "and at least one strategy is needed\n\n" + USAGE);
        }
        return cfg;
    }

    /** Accepts 10000000 as well as 10_000_000. */
    private static long number(String value) {
        return Long.parseLong(value.replace("_", ""));
    }

    private static String trimSemicolons(String value) {
        String s = value.trim();
        while (s.startsWith(";")) {
            s = s.substring(1);
        }
        while (s.endsWith(";")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }
}
