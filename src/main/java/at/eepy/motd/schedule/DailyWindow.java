package at.eepy.motd.schedule;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Set;

/**
 * A recurring time of day, optionally limited to some weekdays. If {@code to}
 * is before {@code from} the window runs past midnight; the weekday then refers
 * to the day the window started.
 *
 * @param days empty = every day
 */
public record DailyWindow(Set<DayOfWeek> days, LocalTime from, LocalTime to) {

    public boolean contains(LocalDateTime now) {
        LocalTime time = now.toLocalTime();
        if (!to.isBefore(from)) {
            return dayMatches(now.getDayOfWeek()) && !time.isBefore(from) && time.isBefore(to);
        }
        if (!time.isBefore(from)) {
            return dayMatches(now.getDayOfWeek());
        }
        return time.isBefore(to) && dayMatches(now.getDayOfWeek().minus(1));
    }

    private boolean dayMatches(DayOfWeek day) {
        return days.isEmpty() || days.contains(day);
    }
}
