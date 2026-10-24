# SQL Server bulk insert benchmark

Compares two ways of loading rows into a `#temp` table from Java while changing each row on the way:

| strategy      | how                                                                                          |
|---------------|----------------------------------------------------------------------------------------------|
| `bulkcopy`    | `SQLServerBulkCopy` fed straight from the source `ResultSet` through `TransformingRecord`     |
| `batch`       | `PreparedStatement.addBatch()` / `executeBatch()` with `useBulkCopyForBatchInsert=true`       |
| `server-side` | reference only: `INSERT … SELECT` with the change written in T-SQL, no rows go through Java   |

The setup mirrors the real job. Rows are streamed from `dbo.transactions` on one connection and written into the
heap `#stg_trx` on a second connection, with `TABLOCK` and 50,000 rows per batch. The per-row change cleans up the
currency code (`' chf'` becomes `'CHF'`). Source table and `#temp` live in the same database.

## Run

```bash
docker compose up -d                                   # SQL Server 2022 on localhost:14330
./gradlew run --args="--rows=1000000 --iterations=1"   # quick check (a few minutes, mostly data generation)
./gradlew run                                          # the real thing: 10M rows, 3 measured runs per strategy
docker compose down                                    # removes the container and its data
```

Or run `./bench.sh`, which does the first three steps and keeps the console output in `results/`.

The first run generates the source table (10M rows take a few minutes under emulation). Later runs reuse it as
long as the container exists. Each run starts with one 200,000-row warm-up pass per strategy, and the order of the
strategies rotates between iterations.

## Options

```
--rows=10000000          rows in the source table, and rows loaded per run
--iterations=3           measured runs per strategy
--batch-size=50000       rows per executeBatch() and per bulk-copy batch
--strategies=bulkcopy,batch,server-side
--warmup-rows=200000     rows per warm-up run (0 = no warm-up)
--pause-ms=2000          pause between runs
--extra=...              extra JDBC properties for every connection
--url=jdbc:sqlserver://localhost:14330;encrypt=false
--user=sa --password=... (default: $MSSQL_SA_PASSWORD, else the docker-compose password)
--reseed                 drop and regenerate the source table
```

Things worth trying: `--batch-size=100000`, `--extra=sendTemporalDataTypesAsStringForBulkCopy=false`,
`--extra=packetSize=32767` (fine here because the connection isn't encrypted), and TLS like production with
`--url="jdbc:sqlserver://localhost:14330;encrypt=true;trustServerCertificate=true"`.

## Reading the output

- **rows/s, vs best**: throughput, and time relative to the fastest strategy.
- **read**: share of the run spent pulling rows from the source (`next()` plus the `getObject()` calls).
- **executeBatch**: batch strategy only. Share of the run spent inside `executeBatch()`, while nothing is read.
- **B/row**: bytes the Java thread allocated per row, a proxy for GC pressure.
- **bulk-loaded**: rows SQL Server received as bulk loads, from its `Bulk Copy Rows/sec` counter. It confirms the
  batch strategy really went through bulk copy. A silent fallback to normal inserts is reported as a `WARNING`.
- **Content check**: each run is verified (row count, NULLs, cleaned currencies, `SUM(amount)`), and all runs must
  produce the same content checksum.
- `results/results-*.csv`: one line per measured run.

## Notes

- **Apple Silicon**: the SQL Server image is amd64 only, so Docker has to emulate it with Rosetta (QEMU emulation
  tends to crash SQL Server), and SQL Server needs at least 4 GB of memory in the Docker VM.
  - Colima: `colima start --vm-type=vz --vz-rosetta --cpu 4 --memory 8` (its default 2 GB VM is too small).
  - Docker Desktop: keep "Use Rosetta for x86_64/amd64 emulation" on in Settings › General.
  - OrbStack: works as is.

  Absolute numbers will be lower than on a real server, so compare the strategies with each other.
- Disk: about 6 GB inside the container (source table, plus tempdb pre-sized to 3.5 GB).
- Driver: mssql-jdbc 13.6.0, set in `build.gradle.kts`. The batch strategy needs 13.2 or later for DATETIME/MONEY
  columns in bulk-copy batch mode.
- Java 17 or later. The Gradle wrapper (9.2.0) is the one from `sql-server-queue`.
