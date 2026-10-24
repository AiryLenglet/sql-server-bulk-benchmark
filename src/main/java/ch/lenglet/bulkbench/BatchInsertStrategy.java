package ch.lenglet.bulkbench;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;

/** Solution 2: plain PreparedStatement batches; useBulkCopyForBatchInsert turns each executeBatch() into a bulk copy. */
final class BatchInsertStrategy implements LoadStrategy {

    @Override
    public String name() {
        return "batch";
    }

    @Override
    public String ownerProperties() {
        return "useBulkCopyForBatchInsert=true;bulkCopyForBatchInsertTableLock=true;cacheBulkCopyMetadata=true";
    }

    @Override
    public Timings load(Connection reader, Connection owner, long maxId, int batchSize) throws SQLException {
        long readNanos = 0;
        long executeNanos = 0;
        try (PreparedStatement select = reader.prepareStatement(Schema.SELECT_SOURCE);
             PreparedStatement insert = owner.prepareStatement(Schema.INSERT_TEMP)) {
            select.setLong(1, maxId);
            try (ResultSet rs = select.executeQuery()) {
                ResultSetMetaData md = rs.getMetaData();
                int[] types = new int[Schema.COLUMN_COUNT];
                for (int c = 0; c < types.length; c++) {
                    types[c] = md.getColumnType(c + 1);
                }

                Object[] row = new Object[Schema.COLUMN_COUNT];
                int pending = 0;
                while (true) {
                    long t0 = System.nanoTime();
                    boolean more = rs.next();
                    if (more) {
                        for (int c = 0; c < row.length; c++) {
                            row[c] = rs.getObject(c + 1);
                        }
                    }
                    readNanos += System.nanoTime() - t0;
                    if (!more) {
                        break;
                    }

                    RowChange.apply(row);
                    for (int c = 0; c < row.length; c++) {
                        insert.setObject(c + 1, row[c], types[c]); // typed, so NULLs stay typed NULLs
                    }
                    insert.addBatch();

                    if (++pending == batchSize) {
                        executeNanos += execute(insert); // the read side sits idle while this runs
                        pending = 0;
                    }
                }
                if (pending > 0) {
                    executeNanos += execute(insert);
                }
            }
        }
        return new Timings(readNanos, executeNanos);
    }

    private static long execute(PreparedStatement insert) throws SQLException {
        long t0 = System.nanoTime();
        insert.executeBatch();
        return System.nanoTime() - t0;
    }
}
