package ch.lenglet.bulkbench;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/** Reference: the same change in T-SQL and the copy done inside SQL Server, so no rows go through Java. */
final class ServerSideStrategy implements LoadStrategy {

    @Override
    public String name() {
        return "server-side";
    }

    @Override
    public boolean usesBulkLoad() {
        return false;
    }

    @Override
    public Timings load(Connection reader, Connection owner, long maxId, int batchSize) throws SQLException {
        try (PreparedStatement insert = owner.prepareStatement(Schema.INSERT_SELECT_TEMP)) {
            insert.setLong(1, maxId);
            insert.executeUpdate();
        }
        return Timings.NONE;
    }
}
