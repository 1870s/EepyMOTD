package at.eepy.motd.placeholder;

import java.util.List;

/**
 * The dynamic values of one ping. Equal values produce an equal MOTD, so this
 * record doubles as the render cache key.
 */
public record PingValues(int online, int max, String time, String date, String weekday, String year,
                         String event, String eventEnds, String maintenanceEnds, List<String> external) {
}
