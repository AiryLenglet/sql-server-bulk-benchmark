package ch.lenglet.bulkbench;

/** One run of one strategy. */
record RunResult(
        String strategy,
        int iteration,
        long rows,
        long elapsedNanos,
        LoadStrategy.Timings timings,
        long allocatedBytes,
        long gcMillis,
        long serverBulkLoadedRows,
        Database.Verification verification,
        String problem) {

    static RunResult failed(String strategy, int iteration, long rows, Exception e) {
        return new RunResult(strategy, iteration, rows, -1, LoadStrategy.Timings.NONE, -1, -1, -1, null,
                "failed: " + e);
    }

    boolean ok() {
        return problem == null;
    }

    boolean completed() {
        return elapsedNanos >= 0;
    }

    double seconds() {
        return elapsedNanos / 1e9;
    }

    double rowsPerSecond() {
        return rows / seconds();
    }

    /** Share of the run spent pulling rows from the source. */
    double readShare() {
        return share(timings.readNanos());
    }

    /** Share of the run spent inside executeBatch(), with the read side idle (batch strategy only). */
    double executeBatchShare() {
        return share(timings.executeBatchNanos());
    }

    double bytesPerRow() {
        return allocatedBytes < 0 ? Double.NaN : (double) allocatedBytes / rows;
    }

    private double share(long nanos) {
        return nanos < 0 || elapsedNanos <= 0 ? Double.NaN : (double) nanos / elapsedNanos;
    }
}
