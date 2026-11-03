package at.eepy.motd.config;

import at.eepy.motd.schedule.DailyWindow;
import at.eepy.motd.schedule.DateWindow;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.event.EventPriority;
import org.jspecify.annotations.Nullable;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Immutable, validated snapshot of config.yml. A new instance is created on
 * every reload and published as a whole, so ping threads always see one
 * consistent configuration.
 */
public record PluginConfig(
        Branding branding,
        Map<String, TextColor> palette,
        Layout layout,
        Map<String, Design> designs,
        Rotation rotation,
        List<EventDefinition> events,
        Maintenance maintenance,
        PlayerCount playerCount,
        Hover hover,
        Icons icons,
        Placeholders placeholders,
        int cacheSize,
        EventPriority listenerPriority,
        boolean warnAboutOtherMotdPlugins,
        Messages messages) {

    public record Branding(String serverName, String ip, String website, String discord) {
    }

    public record Layout(boolean center, int lineWidth, boolean legacyColors) {
    }

    /**
     * @param hover {@code null} = use the global hover lines
     */
    public record Design(String id, String line1, String line2, @Nullable String icon, @Nullable List<String> hover) {
    }

    public enum Mode { STATIC, RANDOM, SEQUENTIAL, SCHEDULED }

    public record Rotation(Mode mode, String staticDesign, List<String> pool, long sequentialIntervalMillis,
                           List<ScheduleEntry> schedule) {
    }

    public record ScheduleEntry(String design, DailyWindow window) {
    }

    /** Events are sorted by priority, highest first. */
    public record EventDefinition(String id, String name, DateWindow window, List<String> designs,
                                  @Nullable String icon, int priority) {
    }

    public record Maintenance(String design, @Nullable String icon, List<String> hover, String versionLabel,
                              List<DateWindow> schedule, BlockJoins blockJoins) {

        public @Nullable DateWindow activeWindow(LocalDateTime now) {
            for (DateWindow window : schedule) {
                if (window.contains(now)) {
                    return window;
                }
            }
            return null;
        }
    }

    /**
     * @param bypassPlayers lower-case names and UUID strings
     */
    public record BlockJoins(boolean enabled, String kickMessage, boolean bypassOps, Set<String> bypassPlayers) {
    }

    /**
     * @param maxPlayers -1 = the server's own value
     */
    public record PlayerCount(int maxPlayers, boolean hideCounts, boolean excludeVanished) {
    }

    public record Hover(boolean enabled, List<String> lines, boolean showOnlinePlayers, int maxOnlinePlayers) {
    }

    /**
     * @param defaultIcon icon name, or {@link #SERVER_ICON} to keep server-icon.png
     */
    public record Icons(boolean enabled, String defaultIcon, boolean resize) {
        public static final String SERVER_ICON = "server";
    }

    public record Placeholders(ZoneId zone, DateTimeFormatter timeFormat, DateTimeFormatter dateFormat,
                               boolean placeholderApi, int placeholderApiRefreshSeconds,
                               String placeholderApiMissingValue) {
    }

    public record Messages(String prefix, Map<String, String> entries) {

        public String get(String key) {
            String value = entries.get(key);
            return value != null ? value : "<red>Missing message: " + key + "</red>";
        }
    }
}
