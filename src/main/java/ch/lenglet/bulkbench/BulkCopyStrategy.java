package ch.lenglet.bulkbench;

import com.microsoft.sqlserver.jdbc.SQLServerBulkCopy;
import com.microsoft.sqlserver.jdbc.SQLServerBulkCopyOptions;
import com.microsoft.sqlserver.jdbc.SQLServerConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/** Solution 1: SQLServerBulkCopy fed straight from the source ResultSet, changing each row on the way. */
final class BulkCopyStrategy implements LoadStrategy {

    @Override
    public String name() {
        return "bulkcopy";
    }

    @Override
    public Timings load(Connection reader, Connection owner, long maxId, int batchSize) throws SQLException {
        try (PreparedStatement select = reader.prepareStatement(Schema.SELECT_SOURCE)) {
            select.setLong(1, maxId);
            try (ResultSet rs = select.executeQuery();
                 SQLServerBulkCopy bulk = new SQLServerBulkCopy(owner.unwrap(SQLServerConnection.class))) {
                SQLServerBulkCopyOptions options = new SQLServerBulkCopyOptions();
                options.setBatchSize(batchSize);
                options.setBulkCopyTimeout(0); // default is 60 s
                options.setTableLock(true);    // TABLOCK, like bulkCopyForBatchInsertTableLock in the batch strategy
                bulk.setBulkCopyOptions(options);
                bulk.setDestinationTableName(Schema.TEMP_FOR_BULK_COPY);

                TransformingRecord source = new TransformingRecord(rs, RowChange::apply);
                bulk.writeToServer(source);
                return new Timings(source.readNanos(), -1);
            }
        }
    }
}
