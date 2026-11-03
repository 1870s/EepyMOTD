package at.eepy.motd.schedule;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

class DateWindowTest {

    private static LocalDateTime at(String value) {
        return LocalDateTime.parse(value);
    }

    @Test
    void yearlyWindowIncludesTheWholeEndDay() {
        DateWindow christmas = DateWindow.parse("12-18", "12-27");
        assertFalse(christmas.contains(at("2026-12-17T23:59")));
        assertTrue(christmas.contains(at("2026-12-18T00:00")));
        assertTrue(christmas.contains(at("2026-12-27T23:59")));
        assertFalse(christmas.contains(at("2026-12-28T00:00")));
        assertTrue(christmas.contains(at("2031-12-24T12:00")), "repeats every year");
    }

    @Test
    void yearlyWindowWrapsAroundNewYear() {
        DateWindow newYear = DateWindow.parse("12-31 18:00", "01-02");
        assertFalse(newYear.contains(at("2026-12-31T17:59")));
        assertTrue(newYear.contains(at("2026-12-31T18:00")));
        assertTrue(newYear.contains(at("2027-01-01T10:00")));
        assertTrue(newYear.contains(at("2027-01-02T23:00")));
        assertFalse(newYear.contains(at("2027-01-03T00:00")));
        assertEquals(Duration.ofHours(14), newYear.remaining(at("2027-01-02T10:00")));
    }

    @Test
    void oneTimeWindowUsesExactTimes() {
        DateWindow contest = DateWindow.parse("2026-12-05 18:00", "2026-12-12 20:00");
        assertFalse(contest.contains(at("2026-12-05T17:59")));
        assertTrue(contest.contains(at("2026-12-05T18:00")));
        assertFalse(contest.contains(at("2026-12-12T20:00")));
        assertFalse(contest.contains(at("2027-12-06T12:00")), "does not repeat");
        assertEquals(Duration.ofHours(2), contest.remaining(at("2026-12-12T18:00")));
        assertNull(contest.remaining(at("2026-12-13T00:00")));
    }

    @Test
    void invalidInputIsRejectedWithAReason() {
        assertThrows(IllegalArgumentException.class, () -> DateWindow.parse("13-01", "12-02"));
        assertThrows(IllegalArgumentException.class, () -> DateWindow.parse("2026-12-01", "12-02"));
        assertThrows(IllegalArgumentException.class, () -> DateWindow.parse("2026-12-05", "2026-12-01"));
        assertThrows(IllegalArgumentException.class, () -> DateWindow.parse("tomorrow", "12-02"));
        assertThrows(IllegalArgumentException.class, () -> DateWindow.parse("2027-02-29", "2027-03-01"));
        assertThrows(IllegalArgumentException.class, () -> DateWindow.parse(null, "12-02"));
    }

    @Test
    void dailyWindowCanCrossMidnight() {
        DailyWindow fridayNight = new DailyWindow(java.util.Set.of(java.time.DayOfWeek.FRIDAY),
                java.time.LocalTime.of(18, 0), java.time.LocalTime.of(2, 0));
        assertTrue(fridayNight.contains(at("2026-11-06T19:00")), "Friday evening");
        assertTrue(fridayNight.contains(at("2026-11-07T01:30")), "Saturday early morning belongs to Friday");
        assertFalse(fridayNight.contains(at("2026-11-07T19:00")), "Saturday evening");
        assertFalse(fridayNight.contains(at("2026-11-06T01:30")), "Friday early morning belongs to Thursday");
    }
}
