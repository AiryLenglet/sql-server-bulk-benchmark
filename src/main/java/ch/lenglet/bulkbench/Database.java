package ch.lenglet.bulkbench;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** Connections, one-time setup (database, seed data) and the checks run against the server. */
final class Database {

    static final String NAME = "bulkbench";

    private final Config cfg;

    Database(Config cfg) {
        this.cfg = cfg;
    }

    /** A new connection to the benchmark database, with extra JDBC properties appended. */
    Connection connect(String properties) throws SQLException {
        return connect(NAME, properties);
    }

    private Connection connect(String database, String properties) throws SQLException {
        StringBuilder url = new StringBuilder(cfg.serverUrl())
                .append(";databaseName=").append(database)
                .append(";applicationName=sql-server-bulk-benchmark");
        if (!cfg.extraProperties().isEmpty()) {
            url.append(';').append(cfg.extraProperties());
        }
        if (!properties.isEmpty()) {
            url.append(';').append(properties);
        }
        return DriverManager.getConnection(url.toString(), cfg.user(), cfg.password());
    }

    /** Waits for the container: SQL Server takes a while to start, longer under emulation. */
    void awaitServer() throws SQLException, InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(3);
        while (true) {
            try {
                connect("master", "loginTimeout=5").close();
                return;
            } catch (SQLException e) {
                if (System.nanoTime() > deadline) {
                    throw e;
                }
                System.out.println("Waiting for SQL Server at " + cfg.serverUrl() + " (" + e.getMessage() + ")");
                Thread.sleep(3_000);
            }
        }
    }

    /** Creates the benchmark database and pre-sizes tempdb. Safe to run every time. */
    void setUp() throws SQLException {
        try (Connection c = connect("master", ""); Statement s = c.createStatement()) {
            s.execute("IF DB_ID('" + NAME + "') IS NULL CREATE DATABASE " + NAME);
            s.execute("ALTER DATABASE " + NAME + " SET RECOVERY SIMPLE");
            s.execute("ALTER DATABASE " + NAME + " SET COMPATIBILITY_LEVEL = 160"); // for GENERATE_SERIES
            try {
                s.execute(Schema.PRESIZE_TEMPDB);
            } catch (SQLException e) {
                System.out.println("Could not pre-size tempdb, continuing: " + e.getMessage());
            }
        }
    }

    /** Fills dbo.transactions with generated rows, unless it already holds at least {@code rows}. */
    void ensureSourceTable(long rows, boolean reseed) throws SQLException {
        try (Connection c = connect(""); Statement s = c.createStatement()) {
            long existing;
            try (ResultSet rs = s.executeQuery(Schema.SOURCE_ROW_COUNT)) {
                rs.next();
                existing = rs.getLong(1);
            }
            if (existing >= rows && !reseed) {
                System.out.printf(Locale.ROOT, "Source table dbo.transactions: %,d rows (reused)%n", existing);
                return;
            }
            System.out.printf(Locale.ROOT, "Generating %,d rows in dbo.transactions (one-time)...%n", rows);
            long t0 = System.nanoTime();
            s.execute("DROP TABLE IF EXISTS dbo.transactions");
            s.execute(Schema.CREATE_SOURCE);
            try (PreparedStatement ps = c.prepareStatement(Schema.SEED_SOURCE)) {
                ps.setLong(1, rows);
                ps.executeUpdate();
            }
            s.execute("CHECKPOINT");
            System.out.printf(Locale.ROOT, "Generated in %.0f s%n", (System.nanoTime() - t0) / 1e9);
        }
    }

    /** Figures every run must reproduce in #stg_trx. Reading them also pulls the source into the buffer pool. */
    Expected expected(long maxId) throws SQLException {
        try (Connection c = connect(""); PreparedStatement ps = c.prepareStatement(Schema.EXPECTED_FROM_SOURCE)) {
            ps.setLong(1, maxId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return new Expected(rs.getLong(1), rs.getLong(2), rs.getBigDecimal(3));
            }
        }
    }

    String describeServer() throws SQLException {
        try (Connection c = connect("");
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT @@VERSION")) {
            rs.next();
            String version = rs.getString(1).lines().findFirst().orElse("?").trim();
            return "SQL Server  : " + version + System.lineSeparator()
                    + "JDBC driver : " + c.getMetaData().getDriverVersion();
        }
    }

    /** Rows SQL Server has received through bulk load (INSERT BULK) since it started, or -1 if unreadable. */
    static long bulkLoadedRows(Statement s) {
        try (ResultSet rs = s.executeQuery(Schema.BULK_COPY_ROWS_COUNTER)) {
            return rs.next() ? rs.getLong(1) : -1;
        } catch (SQLException e) {
            return -1;
        }
    }

    static Verification verifyTemp(Statement s) throws SQLException {
        try (ResultSet rs = s.executeQuery(Schema.VERIFY_TEMP)) {
            rs.next();
            return new Verification(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getBigDecimal(4), rs.getInt(5));
        }
    }

    record Expected(long rows, long nullValueDates, BigDecimal amountSum) {
    }

    record Verification(long rows, long nullValueDates, long uncleanedCurrencies, BigDecimal amountSum, int checksum) {

        /** Null when #stg_trx matches the source, otherwise what's wrong. */
        String problemVersus(Expected e) {
            if (rows != e.rows()) {
                return String.format(Locale.ROOT, "%,d rows instead of %,d", rows, e.rows());
            }
            if (nullValueDates != e.nullValueDates()) {
                return String.format(Locale.ROOT, "%,d NULL value_dates instead of %,d", nullValueDates, e.nullValueDates());
            }
            if (uncleanedCurrencies != 0) {
                return String.format(Locale.ROOT, "%,d currencies not cleaned", uncleanedCurrencies);
            }
            if (amountSum == null || amountSum.compareTo(e.amountSum()) != 0) {
                return "SUM(amount) " + amountSum + " instead of " + e.amountSum();
            }
            return null;
        }
    }
}
