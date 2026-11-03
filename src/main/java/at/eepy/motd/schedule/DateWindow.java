package at.eepy.motd.schedule;

import org.jspecify.annotations.Nullable;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.MonthDay;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A period between two moments, either once ({@code 2026-12-05 18:00}) or every
 * year ({@code 12-24}). End dates without a time include the whole day. Yearly
 * windows may wrap around New Year ({@code 12-31} to {@code 01-02}).
 */
public final class DateWindow {

    private static final Pattern FORMAT =
            Pattern.compile("(?:(\\d{4})-)?(\\d{2})-(\\d{2})(?:\\s+(\\d{1,2}):(\\d{2}))?");

    private final Point start;
    private final Point end;
    private final String description;

    /**
     * @param year     {@code null} for yearly windows
     * @param wholeDay end point given as a date only: runs until the end of that day
     */
    private record Point(@Nullable Integer year, MonthDay day, LocalTime time, boolean wholeDay) {

        LocalDateTime at(int fallbackYear) {
            int y = year != null ? year : fallbackYear;
            LocalDateTime moment = day.atYear(y).atTime(time);
            return wholeDay ? moment.plusDays(1) : moment;
        }
    }

    private DateWindow(Point start, Point end, String description) {
        this.start = start;
        this.end = end;
        this.description = description;
    }

    /**
     * @throws IllegalArgumentException with a readable reason if the values are invalid
     */
    public static DateWindow parse(@Nullable String start, @Nullable String end) {
        if (start == null || end == null || start.isBlank() || end.isBlank()) {
            throw new IllegalArgumentException("start and end are required");
        }
        Point from = parsePoint(start.trim(), false);
        Point to = parsePoint(end.trim(), true);
        if ((from.year() == null) != (to.year() == null)) {
            throw new IllegalArgumentException("use a year for both start and end, or for neither");
        }
        boolean yearly = from.year() == null;
        if (!yearly && !to.at(0).isAfter(from.at(0))) {
            throw new IllegalArgumentException("end must be after start");
        }
        return new DateWindow(from, to, start.trim() + " → " + end.trim() + (yearly ? " (every year)" : ""));
    }

    public boolean contains(LocalDateTime now) {
        return endOfOccurrence(now) != null;
    }

    /** Time until the window closes, or {@code null} if it is not active. */
    public @Nullable Duration remaining(LocalDateTime now) {
        LocalDateTime closes = endOfOccurrence(now);
        return closes == null ? null : Duration.between(now, closes);
    }

    private @Nullable LocalDateTime endOfOccurrence(LocalDateTime now) {
        if (start.year() != null) {
            LocalDateTime opens = start.at(0);
            LocalDateTime closes = end.at(0);
            return !now.isBefore(opens) && now.isBefore(closes) ? closes : null;
        }
        // An occurrence that started last year may still be running (e.g. 12-31 → 01-02).
        for (int year = now.getYear() - 1; year <= now.getYear(); year++) {
            LocalDateTime opens = start.at(year);
            LocalDateTime closes = end.at(year);
            if (!closes.isAfter(opens)) {
                closes = end.at(year + 1);
            }
            if (!now.isBefore(opens) && now.isBefore(closes)) {
                return closes;
            }
        }
        return null;
    }

    @Override
    public String toString() {
        return description;
    }

    private static Point parsePoint(String value, boolean isEnd) {
        Matcher matcher = FORMAT.matcher(value);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("'" + value + "' is not a date (use MM-dd or yyyy-MM-dd, optionally with HH:mm)");
        }
        try {
            Integer year = matcher.group(1) == null ? null : Integer.parseInt(matcher.group(1));
            MonthDay day = MonthDay.of(Integer.parseInt(matcher.group(2)), Integer.parseInt(matcher.group(3)));
            boolean hasTime = matcher.group(4) != null;
            LocalTime time = hasTime
                    ? LocalTime.of(Integer.parseInt(matcher.group(4)), Integer.parseInt(matcher.group(5)))
                    : LocalTime.MIDNIGHT;
            if (year != null) {
                LocalDate.of(year, day.getMonth(), day.getDayOfMonth()); // rejects e.g. 2027-02-29
            }
            return new Point(year, day, time, isEnd && !hasTime);
        } catch (DateTimeException | NumberFormatException e) {
            throw new IllegalArgumentException("'" + value + "' is not a valid date: " + e.getMessage());
        }
    }
}
