package at.eepy.motd.motd;

import at.eepy.motd.TestConfigs;
import at.eepy.motd.config.PluginConfig;
import at.eepy.motd.config.PluginConfig.Mode;
import at.eepy.motd.motd.MotdSelector.ManualChoice;
import at.eepy.motd.motd.MotdSelector.Source;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class MotdSelectorTest {

    private static final LocalDateTime NOVEMBER = LocalDateTime.parse("2026-11-03T15:00");
    private static final LocalDateTime CHRISTMAS = LocalDateTime.parse("2026-12-24T20:00");

    private static MotdSelector selector(PluginConfig config) {
        return new MotdSelector(config, new MotdRenderer(config, "26.1.2"));
    }

    private static String design(MotdSelector selector, LocalDateTime now) {
        return selector.select(now, 0, false, ManualChoice.NONE).design();
    }

    @Test
    void staticModeShowsTheDefaultDesign() {
        MotdSelector.Selection selection = selector(TestConfigs.bundled()).select(NOVEMBER, 0, false, ManualChoice.NONE);
        assertEquals("default", selection.design());
        assertEquals(Source.ROTATION, selection.source());
        assertNull(selection.icon(), "the default design uses icons.default");
    }

    @Test
    void precedenceIsMaintenanceThenEventThenCommandThenRotation() {
        MotdSelector selector = selector(TestConfigs.bundled());
        ManualChoice choice = new ManualChoice("discord", null);

        assertEquals("discord", selector.select(NOVEMBER, 0, false, choice).design());

        MotdSelector.Selection event = selector.select(CHRISTMAS, 0, false, choice);
        assertEquals("christmas", event.design());
        assertEquals(Source.EVENT, event.source());
        assertEquals("eepy-winter", event.icon());
        assertEquals("Christmas", event.event().name());

        MotdSelector.Selection maintenance = selector.select(CHRISTMAS, 0, true, choice);
        assertEquals("maintenance", maintenance.design());
        assertEquals("eepy-maintenance", maintenance.icon());
        assertTrue(maintenance.maintenance());
    }

    @Test
    void eventsEndByThemselves() {
        MotdSelector selector = selector(TestConfigs.bundled());
        assertEquals("christmas", design(selector, LocalDateTime.parse("2026-12-27T23:59")));
        assertEquals("default", design(selector, LocalDateTime.parse("2026-12-28T00:00")));
        assertEquals("newyear", design(selector, LocalDateTime.parse("2027-01-01T00:30")), "wraps into the new year");
        assertEquals("default", design(selector, LocalDateTime.parse("2027-01-03T00:00")));
    }

    @Test
    void scheduledMaintenanceStartsAndEndsByItself() {
        PluginConfig config = TestConfigs.with("maintenance.schedule",
                List.of(Map.of("start", "2026-11-10 02:00", "end", "2026-11-10 04:00")));
        MotdSelector selector = selector(config);
        assertEquals("default", design(selector, LocalDateTime.parse("2026-11-10T01:59")));
        MotdSelector.Selection during = selector.select(LocalDateTime.parse("2026-11-10T03:00"), 0, false, ManualChoice.NONE);
        assertEquals(Source.SCHEDULED_MAINTENANCE, during.source());
        assertEquals(Duration.ofHours(1), during.maintenanceEnds());
        assertEquals("default", design(selector, LocalDateTime.parse("2026-11-10T04:00")));
    }

    @Test
    void sequentialCyclesThroughThePool() {
        MotdSelector selector = selector(TestConfigs.with("rotation.mode", "SEQUENTIAL"));
        List<String> shown = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            shown.add(design(selector, NOVEMBER));
        }
        assertEquals(List.of("default", "community", "discord", "website", "default"), shown);
    }

    @Test
    void timedSequentialFollowsTheClock() {
        MotdSelector selector = selector(TestConfigs.with("rotation.mode", "SEQUENTIAL",
                "rotation.sequential-interval-seconds", 10));
        assertEquals("default", selector.select(NOVEMBER, 9_999, false, ManualChoice.NONE).design());
        assertEquals("community", selector.select(NOVEMBER, 10_000, false, ManualChoice.NONE).design());
        assertEquals("default", selector.select(NOVEMBER, 40_000, false, ManualChoice.NONE).design());
    }

    @Test
    void randomOnlyPicksFromThePoolAndShowsAllOfIt() {
        MotdSelector selector = selector(TestConfigs.bundled());
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 400; i++) {
            seen.add(selector.select(NOVEMBER, 0, false, new ManualChoice(null, Mode.RANDOM)).design());
        }
        assertEquals(Set.of("default", "community", "discord", "website"), seen);
    }

    @Test
    void scheduledModeUsesDayAndTime() {
        MotdSelector selector = selector(TestConfigs.with("rotation.mode", "SCHEDULED"));
        assertEquals("community", design(selector, LocalDateTime.parse("2026-11-06T21:00")), "Friday evening");
        assertEquals("community", design(selector, LocalDateTime.parse("2026-11-07T01:00")), "after midnight");
        assertEquals("discord", design(selector, LocalDateTime.parse("2026-11-04T12:30")), "lunch time");
        assertEquals("default", design(selector, LocalDateTime.parse("2026-11-04T09:00")), "fallback");
    }

    @Test
    void aRemovedDesignChoiceFallsBackToTheRotation() {
        MotdSelector selector = selector(TestConfigs.bundled());
        assertEquals("default", selector.select(NOVEMBER, 0, false, new ManualChoice("deleted", null)).design());
    }
}
