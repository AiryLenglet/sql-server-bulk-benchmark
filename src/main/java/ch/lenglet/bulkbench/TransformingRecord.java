package ch.lenglet.bulkbench;

import com.microsoft.sqlserver.jdbc.ISQLServerBulkRecord;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Feeds SQLServerBulkCopy from a ResultSet, changing each row on the way. Memory stays flat.
 * Same adapter as discussed, plus a timer around the reads.
 */
@SuppressWarnings("deprecation") // Javadoc deprecates ISQLServerBulkRecord; the bulk-copy docs still recommend it
final class TransformingRecord implements ISQLServerBulkRecord {

    private static final long serialVersionUID = 1L;

    private final transient ResultSet rs;
    private final transient UnaryOperator<Object[]> change;
    private final String[] names;
    private final int[] types;
    private final int[] precisions;
    private final int[] scales;
    private long readNanos;

    TransformingRecord(ResultSet rs, UnaryOperator<Object[]> change) throws SQLException {
        this.rs = rs;
        this.change = change;
        ResultSetMetaData md = rs.getMetaData(); // output columns = SELECT columns (same order/types as the table)
        int n = md.getColumnCount();
        names = new String[n];
        types = new int[n];
        precisions = new int[n];
        scales = new int[n];
        for (int i = 0; i < n; i++) {
            names[i] = md.getColumnName(i + 1);
            types[i] = md.getColumnType(i + 1);
            precisions[i] = md.getPrecision(i + 1);
            scales[i] = md.getScale(i + 1);
        }
    }

    /** Time spent pulling rows from the source (next() plus the getObject() calls). */
    long readNanos() {
        return readNanos;
    }

    @Override
    public boolean next() throws SQLException {
        long t0 = System.nanoTime();
        boolean more = rs.next();
        readNanos += System.nanoTime() - t0;
        return more;
    }

    @Override
    public Object[] getRowData() throws SQLException {
        long t0 = System.nanoTime();
        Object[] row = new Object[names.length];
        for (int i = 0; i < row.length; i++) {
            row[i] = rs.getObject(i + 1);
        }
        readNanos += System.nanoTime() - t0;
        return change.apply(row);
    }

    @Override
    public Set<Integer> getColumnOrdinals() {
        return IntStream.rangeClosed(1, names.length).boxed().collect(Collectors.toCollection(LinkedHashSet::new));
    }

    @Override public String getColumnName(int c) { return names[c - 1]; }
    @Override public int getColumnType(int c) { return types[c - 1]; }
    @Override public int getPrecision(int c) { return precisions[c - 1]; }
    @Override public int getScale(int c) { return scales[c - 1]; }

    // ISQLServerBulkRecord extras that a custom source doesn't need
    @Override public boolean isAutoIncrement(int c) { return false; }
    @Override public DateTimeFormatter getColumnDateTimeFormatter(int c) { return null; }
    @Override public void addColumnMetadata(int p, String n, int t, int pr, int s, DateTimeFormatter f) { throw new UnsupportedOperationException(); }
    @Override public void addColumnMetadata(int p, String n, int t, int pr, int s) { throw new UnsupportedOperationException(); }
    @Override public void setTimestampWithTimezoneFormat(String f) { }
    @Override public void setTimestampWithTimezoneFormat(DateTimeFormatter f) { }
    @Override public void setTimeWithTimezoneFormat(String f) { }
    @Override public void setTimeWithTimezoneFormat(DateTimeFormatter f) { }
}
