package org.granitesecurity.shop.dto;

import com.fasterxml.jackson.annotation.JsonValue;
import org.granitesecurity.shop.service.ShopException;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.Locale;

/**
 * How the revenue report cuts time (docs/finance/accounting.md D15).
 *
 * <p>This is an enum rather than a string because {@code granularity} reaches SQL as the
 * first argument of {@code date_trunc} — it is the one part of the query that is not a
 * value. Binding it is safe, but only because nothing outside these three constants can
 * ever get there: a raw query-string value passed through would make the report an
 * injection point the day someone switches to concatenation.
 *
 * <p>Weeks are ISO (Monday), and every bucket is labelled by its start date.
 */
public enum RevenueGranularity {
    YEAR("year"),
    MONTH("month"),
    WEEK("week");

    private static final DateTimeFormatter MONTH_LABEL =
            DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter WEEK_LABEL =
            DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    /** The {@code date_trunc} unit. Never built from user input. */
    private final String sqlUnit;

    RevenueGranularity(String sqlUnit) {
        this.sqlUnit = sqlUnit;
    }

    public String sqlUnit() {
        return sqlUnit;
    }

    @JsonValue
    public String jsonValue() {
        return sqlUnit;
    }

    public static RevenueGranularity from(String raw) {
        if (raw == null || raw.isBlank()) {
            return MONTH;
        }
        for (RevenueGranularity g : values()) {
            if (g.sqlUnit.equalsIgnoreCase(raw)) {
                return g;
            }
        }
        throw new ShopException("Unknown granularity: " + raw + " (expected year, month or week)");
    }

    /** The start of the bucket {@code date} falls in — the same cut {@code date_trunc} makes. */
    public LocalDate truncate(LocalDate date) {
        return switch (this) {
            case YEAR -> date.withDayOfYear(1);
            case MONTH -> date.withDayOfMonth(1);
            case WEEK -> date.with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));
        };
    }

    public LocalDate next(LocalDate bucketStart) {
        return switch (this) {
            case YEAR -> bucketStart.plusYears(1);
            case MONTH -> bucketStart.plusMonths(1);
            case WEEK -> bucketStart.plusWeeks(1);
        };
    }

    public String label(LocalDate bucketStart) {
        return switch (this) {
            case YEAR -> String.valueOf(bucketStart.getYear());
            case MONTH -> MONTH_LABEL.format(bucketStart);
            case WEEK -> WEEK_LABEL.format(bucketStart);
        };
    }

    /** Guard against a range so wide the response is pointless to render. */
    public long maxBuckets() {
        return switch (this) {
            case YEAR -> 100;
            case MONTH -> 600;
            case WEEK -> 1200;
        };
    }
}
