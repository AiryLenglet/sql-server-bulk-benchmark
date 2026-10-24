package ch.lenglet.bulkbench;

/** Table definitions and the SQL the benchmark runs. */
final class Schema {

    private Schema() {
    }

    static final String COLUMNS =
            "trx_id, booking_date, value_date, counterparty_id, account_iban, amount, currency, trx_type, created_at";
    static final int COLUMN_COUNT = 9;
    /** 0-based position of currency in {@link #COLUMNS}: the column the per-row change touches. */
    static final int CURRENCY = 6;

    /** Source table, standing in for upstream's transactions. */
    static final String CREATE_SOURCE = """
            CREATE TABLE dbo.transactions (
                trx_id          BIGINT        NOT NULL PRIMARY KEY CLUSTERED,
                booking_date    DATE          NOT NULL,
                value_date      DATE          NULL,
                counterparty_id VARCHAR(20)   NOT NULL,
                account_iban    VARCHAR(34)   NOT NULL,
                amount          DECIMAL(19,4) NOT NULL,
                currency        VARCHAR(5)    NOT NULL, -- messy on purpose (' chf', 'eur '...): the per-row change cleans it
                trx_type        VARCHAR(10)   NOT NULL,
                created_at      DATETIME2(3)  NOT NULL
            )""";

    /** Generates the source rows server-side. Parameter 1: number of rows. */
    static final String SEED_SOURCE = """
            INSERT INTO dbo.transactions WITH (TABLOCK)
                   (trx_id, booking_date, value_date, counterparty_id, account_iban, amount, currency, trx_type, created_at)
            SELECT s.value,
                   DATEADD(DAY, CAST(s.value % 3650 AS INT), CAST('2016-01-01' AS DATE)),
                   CASE WHEN s.value % 20 = 0 THEN NULL
                        ELSE DATEADD(DAY, CAST(s.value % 3650 AS INT) + 2, CAST('2016-01-01' AS DATE)) END,
                   CONCAT('CP', RIGHT(CONCAT('000000', s.value % 50000), 6)),
                   CONCAT('CH', RIGHT(CONCAT(REPLICATE('0', 19), s.value), 19)),
                   CAST(((s.value * 7919) % 100000000) / 100.0 AS DECIMAL(19,4)),
                   CHOOSE(CAST(s.value % 5 AS INT) + 1, ' chf', 'eur ', 'Usd', ' gbp ', 'JPY'),
                   CHOOSE(CAST(s.value % 3 AS INT) + 1, 'PAYMENT', 'TRANSFER', 'FEE'),
                   DATEADD(SECOND, CAST(s.value % 86400 AS INT), CAST('2026-01-01T00:00:00' AS DATETIME2(3)))
            FROM GENERATE_SERIES(CAST(1 AS BIGINT), CAST(? AS BIGINT)) AS s""";

    /** What both Java strategies stream from the source. Parameter 1: highest trx_id to read. */
    static final String SELECT_SOURCE = "SELECT " + COLUMNS + " FROM dbo.transactions WHERE trx_id <= ?";

    /** The target: a heap #temp with the same columns. Created with a plain Statement so it stays session-scoped. */
    static final String CREATE_TEMP = """
            CREATE TABLE #stg_trx (
                trx_id          BIGINT        NOT NULL,
                booking_date    DATE          NOT NULL,
                value_date      DATE          NULL,
                counterparty_id VARCHAR(20)   COLLATE DATABASE_DEFAULT NOT NULL,
                account_iban    VARCHAR(34)   COLLATE DATABASE_DEFAULT NOT NULL,
                amount          DECIMAL(19,4) NOT NULL,
                currency        VARCHAR(5)    COLLATE DATABASE_DEFAULT NOT NULL,
                trx_type        VARCHAR(10)   COLLATE DATABASE_DEFAULT NOT NULL,
                created_at      DATETIME2(3)  NOT NULL
            )""";

    /** Destination name for SQLServerBulkCopy: the driver's documented form for temp tables. */
    static final String TEMP_FOR_BULK_COPY = "tempdb..#stg_trx";

    /** Fully parameterized, every column in table order: what useBulkCopyForBatchInsert needs. */
    static final String INSERT_TEMP =
            "INSERT INTO #stg_trx (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";

    /** Reference strategy: same change in T-SQL, copy done inside SQL Server. Parameter 1: highest trx_id. */
    static final String INSERT_SELECT_TEMP = "INSERT INTO #stg_trx WITH (TABLOCK) (" + COLUMNS + ")\n" + """
            SELECT trx_id, booking_date, value_date, counterparty_id, account_iban, amount,
                   UPPER(TRIM(currency)), trx_type, created_at
            FROM dbo.transactions WHERE trx_id <= ?""";

    /** Row count, NULL count, uncleaned currencies, amount total and a content checksum of #stg_trx. */
    static final String VERIFY_TEMP = """
            SELECT COUNT_BIG(*),
                   COUNT_BIG(*) - COUNT_BIG(value_date),
                   COUNT_BIG(CASE WHEN DATALENGTH(currency) <> 3
                                    OR currency COLLATE Latin1_General_BIN2 NOT IN ('CHF', 'EUR', 'USD', 'GBP', 'JPY')
                                  THEN 1 END),
                   SUM(amount),
                   CHECKSUM_AGG(BINARY_CHECKSUM(*))
            FROM #stg_trx""";

    /** The same figures for the source, for rows with trx_id <= parameter 1. */
    static final String EXPECTED_FROM_SOURCE = """
            SELECT COUNT_BIG(*), COUNT_BIG(*) - COUNT_BIG(value_date), SUM(amount)
            FROM dbo.transactions WHERE trx_id <= ?""";

    /** Cumulative count of rows SQL Server received through bulk load (INSERT BULK), all databases. */
    static final String BULK_COPY_ROWS_COUNTER = """
            SELECT cntr_value FROM sys.dm_os_performance_counters
            WHERE object_name LIKE N'%:Databases%'
              AND counter_name = N'Bulk Copy Rows/sec'
              AND instance_name = N'_Total'""";

    static final String SOURCE_ROW_COUNT = """
            SELECT COALESCE(SUM(row_count), -1) FROM sys.dm_db_partition_stats
            WHERE object_id = OBJECT_ID('dbo.transactions') AND index_id IN (0, 1)""";

    /** Grows tempdb (about 3 GB of data, 512 MB of log) once, so file growth doesn't land in one run's timings. */
    static final String PRESIZE_TEMPDB = """
            DECLARE @files int = (SELECT COUNT(*) FROM tempdb.sys.database_files WHERE type_desc = 'ROWS');
            DECLARE @mb int = 3072 / @files;
            DECLARE @sql nvarchar(max) = N'';
            SELECT @sql += N'ALTER DATABASE tempdb MODIFY FILE (NAME = N''' + REPLACE(name, '''', '''''')
                         + N''', SIZE = ' + CAST(CASE WHEN type_desc = 'LOG' THEN 512 ELSE @mb END AS nvarchar(10)) + N'MB);'
            FROM tempdb.sys.database_files
            WHERE size < CASE WHEN type_desc = 'LOG' THEN 512 ELSE @mb END * 128;
            EXEC sys.sp_executesql @sql;""";
}
