package ch.lenglet.bulkbench;

import java.util.Locale;

/** The per-row change both Java strategies apply: clean up the currency code (' chf' becomes 'CHF'). */
final class RowChange {

    private RowChange() {
    }

    static Object[] apply(Object[] row) {
        row[Schema.CURRENCY] = ((String) row[Schema.CURRENCY]).trim().toUpperCase(Locale.ROOT);
        return row;
    }
}
