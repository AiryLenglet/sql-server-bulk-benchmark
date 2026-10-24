package ch.lenglet.bulkbench;

import java.sql.Connection;
import java.sql.SQLException;

/** One way of getting the source rows into #stg_trx. */
interface LoadStrategy {

    String name();

    /** Extra JDBC properties for the connection that owns #stg_trx. */
    default String ownerProperties() {
        return "";
    }

    /** Whether the rows should reach SQL Server as a bulk load (checked against the server's counter). */
    default boolean usesBulkLoad() {
        return true;
    }

    /**
     * Loads every source row with trx_id <= maxId into #stg_trx, which already exists on {@code owner}.
     * {@code reader} is a second session, as in the real job: the driver can't stream a result set and
     * bulk load on the same connection.
     */
    Timings load(Connection reader, Connection owner, long maxId, int batchSize) throws SQLException;

    /** Time spent reading source rows, and waiting in executeBatch() for the batch strategy. -1 = not measured. */
    record Timings(long readNanos, long executeBatchNanos) {
        static final Timings NONE = new Timings(-1, -1);
    }

    static LoadStrategy byName(String name) {
        return switch (name) {
            case "bulkcopy" -> new BulkCopyStrategy();
            case "batch" -> new BatchInsertStrategy();
            case "server-side" -> new ServerSideStrategy();
            default -> throw new IllegalArgumentException(
                    "Unknown strategy '" + name + "' (expected bulkcopy, batch or server-side)");
        };
    }
}
