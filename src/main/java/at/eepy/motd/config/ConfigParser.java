package at.eepy.motd.config;

import at.eepy.motd.config.PluginConfig.*;
import at.eepy.motd.schedule.DailyWindow;
import at.eepy.motd.schedule.DateWindow;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.tag.standard.StandardTags;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.event.EventPriority;
import org.jspecify.annotations.Nullable;

import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Turns config.yml into a validated {@link PluginConfig}.
 * <p>
 * Every problem with a sensible fallback becomes a warning (shown on reload and
 * in the console) instead of an error, so a typo never leaves the server list
 * without a MOTD. Paths in warnings match the YAML keys, so they are easy to find.
 */
public final class ConfigParser {

    private static final Pattern TAG_NAME = Pattern.compile("[a-z0-9_-]+");
    private static final List<String> DEFAULT_POOL = List.of("default");

    private final List<String> warnings = new ArrayList<>();

    public List<String> warnings() {
        return List.copyOf(warnings);
    }

    public PluginConfig parse(ConfigurationSection root) {
        warnings.clear();
        Map<String, Design> designs = parseDesigns(root.getConfigurationSection("designs"));
        String fallbackDesign = designs.containsKey("default") ? "default" : designs.keySet().iterator().next();

        return new PluginConfig(
                new Branding(
                        string(root, "branding.server-name", "eepy"),
                        string(root, "branding.ip", "mc.eepy.at"),
                        string(root, "branding.website", "eepy.at"),
                        string(root, "branding.discord", "dsc.gg/muezza")),
                parsePalette(root.getConfigurationSection("palette")),
                new Layout(
                        root.getBoolean("layout.center", true),
                        clamp(root, "layout.line-width", 270, 100, 1000),
                        root.getBoolean("layout.legacy-colors", true)),
                designs,
                parseRotation(root, designs, fallbackDesign),
                parseEvents(root.getConfigurationSection("events"), designs),
                parseMaintenance(root, designs, fallbackDesign),
                new PlayerCount(
                        Math.max(-1, root.getInt("player-count.max-players", -1)),
                        root.getBoolean("player-count.hide-counts", false),
                        root.getBoolean("player-count.exclude-vanished", true)),
                new Hover(
                        root.getBoolean("hover.enabled", true),
                        root.getStringList("hover.lines"),
                        root.getBoolean("hover.show-online-players", false),
                        clamp(root, "hover.max-online-players", 8, 0, 50)),
                new Icons(
                        root.getBoolean("icons.enabled", true),
                        string(root, "icons.default", "eepy").toLowerCase(Locale.ROOT),
                        root.getBoolean("icons.resize", true)),
                parsePlaceholders(root),
                clamp(root, "performance.cache-size", 64, 1, 4096),
                enumValue(root, "compatibility.listener-priority", EventPriority.class, EventPriority.HIGH,
                        EnumSet.range(EventPriority.LOWEST, EventPriority.HIGHEST)),
                root.getBoolean("compatibility.warn-about-other-motd-plugins", true),
                parseMessages(root.getConfigurationSection("messages")));
    }

    private Map<String, Design> parseDesigns(@Nullable ConfigurationSection section) {
        Map<String, Design> designs = new LinkedHashMap<>();
        if (section != null) {
            for (String id : section.getKeys(false)) {
                ConfigurationSection design = section.getConfigurationSection(id);
                if (design == null) {
                    warn("designs." + id + " is not a section");
                    continue;
                }
                String line1 = design.getString("line1");
                String line2 = design.getString("line2", "");
                if (line1 == null) {
                    warn("designs." + id + ".line1 is missing, design ignored");
                    continue;
                }
                if (design.contains("line3")) {
                    warn("designs." + id + ".line3: the server list only shows two lines, line3 is ignored");
                }
                String icon = design.getString("icon");
                designs.put(id.toLowerCase(Locale.ROOT), new Design(id.toLowerCase(Locale.ROOT), line1, line2,
                        icon == null || icon.isBlank() ? null : icon.toLowerCase(Locale.ROOT),
                        design.isList("hover") ? design.getStringList("hover") : null));
            }
        }
        if (designs.isEmpty()) {
            warn("No designs configured, using a plain default design");
            designs.put("default", new Design("default", "<gradient:#C9A6FF:#8DB6FF><bold>eepy</bold></gradient>",
                    "{ip}", null, null));
        }
        return Collections.unmodifiableMap(designs);
    }

    private Rotation parseRotation(ConfigurationSection root, Map<String, Design> designs, String fallback) {
        String staticDesign = designReference(root, "rotation.static", designs, fallback);
        List<String> pool = new ArrayList<>();
        for (String id : root.getStringList("rotation.pool")) {
            String key = id.toLowerCase(Locale.ROOT);
            if (designs.containsKey(key)) {
                pool.add(key);
            } else {
                warn("rotation.pool: unknown design '" + id + "' ignored");
            }
        }
        if (pool.isEmpty()) {
            pool.addAll(designs.containsKey(staticDesign) ? List.of(staticDesign) : DEFAULT_POOL);
        }

        List<ScheduleEntry> schedule = new ArrayList<>();
        List<Map<?, ?>> rawSchedule = root.getMapList("rotation.schedule");
        for (int i = 0; i < rawSchedule.size(); i++) {
            Map<?, ?> entry = rawSchedule.get(i);
            String path = "rotation.schedule[" + i + "]";
            String design = Objects.toString(entry.get("design"), "").toLowerCase(Locale.ROOT);
            if (!designs.containsKey(design)) {
                warn(path + ": unknown design '" + design + "', entry ignored");
                continue;
            }
            try {
                schedule.add(new ScheduleEntry(design, new DailyWindow(
                        days(entry.get("days")),
                        LocalTime.parse(Objects.toString(entry.get("from"), "00:00")),
                        LocalTime.parse(Objects.toString(entry.get("to"), "23:59")))));
            } catch (DateTimeParseException | IllegalArgumentException e) {
                warn(path + ": " + e.getMessage() + ", entry ignored");
            }
        }

        double seconds = root.getDouble("rotation.sequential-interval-seconds", 0);
        return new Rotation(
                enumValue(root, "rotation.mode", Mode.class, Mode.STATIC, EnumSet.allOf(Mode.class)),
                staticDesign,
                List.copyOf(pool),
                Math.max(0, Math.round(seconds * 1000)),
                List.copyOf(schedule));
    }

    private List<EventDefinition> parseEvents(@Nullable ConfigurationSection section, Map<String, Design> designs) {
        List<EventDefinition> events = new ArrayList<>();
        if (section == null) {
            return List.of();
        }
        for (String id : section.getKeys(false)) {
            ConfigurationSection event = section.getConfigurationSection(id);
            String path = "events." + id;
            if (event == null) {
                warn(path + " is not a section");
                continue;
            }
            if (!event.getBoolean("enabled", true)) {
                continue;
            }
            DateWindow window;
            try {
                window = DateWindow.parse(event.getString("start"), event.getString("end"));
            } catch (IllegalArgumentException e) {
                warn(path + ": " + e.getMessage() + ", event ignored");
                continue;
            }
            List<String> eventDesigns = new ArrayList<>();
            List<String> raw = event.isList("designs") ? event.getStringList("designs")
                    : event.isString("design") ? List.of(event.getString("design")) : List.of();
            for (String design : raw) {
                if (designs.containsKey(design.toLowerCase(Locale.ROOT))) {
                    eventDesigns.add(design.toLowerCase(Locale.ROOT));
                } else {
                    warn(path + ": unknown design '" + design + "' ignored");
                }
            }
            if (eventDesigns.isEmpty()) {
                warn(path + " has no valid design, event ignored");
                continue;
            }
            String icon = event.getString("icon");
            events.add(new EventDefinition(id, event.getString("name", id), window, List.copyOf(eventDesigns),
                    icon == null || icon.isBlank() ? null : icon.toLowerCase(Locale.ROOT), event.getInt("priority", 0)));
        }
        events.sort(Comparator.comparingInt(EventDefinition::priority).reversed());
        return List.copyOf(events);
    }

    private Maintenance parseMaintenance(ConfigurationSection root, Map<String, Design> designs, String fallback) {
        List<DateWindow> schedule = new ArrayList<>();
        List<Map<?, ?>> raw = root.getMapList("maintenance.schedule");
        for (int i = 0; i < raw.size(); i++) {
            Map<?, ?> entry = raw.get(i);
            try {
                schedule.add(DateWindow.parse(Objects.toString(entry.get("start"), null),
                        Objects.toString(entry.get("end"), null)));
            } catch (IllegalArgumentException e) {
                warn("maintenance.schedule[" + i + "]: " + e.getMessage() + ", entry ignored");
            }
        }
        Set<String> bypass = new HashSet<>();
        root.getStringList("maintenance.block-joins.bypass-players")
                .forEach(value -> bypass.add(value.trim().toLowerCase(Locale.ROOT)));
        String icon = root.getString("maintenance.icon");
        return new Maintenance(
                designReference(root, "maintenance.design", designs, designs.containsKey("maintenance") ? "maintenance" : fallback),
                icon == null || icon.isBlank() ? null : icon.toLowerCase(Locale.ROOT),
                root.getStringList("maintenance.hover"),
                string(root, "maintenance.version-label", ""),
                List.copyOf(schedule),
                new BlockJoins(
                        root.getBoolean("maintenance.block-joins.enabled", false),
                        string(root, "maintenance.block-joins.kick-message", "Maintenance"),
                        root.getBoolean("maintenance.block-joins.bypass-ops", true),
                        Set.copyOf(bypass)));
    }

    private Placeholders parsePlaceholders(ConfigurationSection root) {
        String zoneId = string(root, "placeholders.timezone", "Europe/Vienna");
        ZoneId zone;
        try {
            zone = ZoneId.of(zoneId);
        } catch (DateTimeException e) {
            warn("placeholders.timezone: unknown zone '" + zoneId + "', using the server time zone");
            zone = ZoneId.systemDefault();
        }
        return new Placeholders(
                zone,
                formatter(root, "placeholders.time-format", "HH:mm"),
                formatter(root, "placeholders.date-format", "dd.MM.yyyy"),
                root.getBoolean("placeholders.placeholderapi.enabled", true),
                clamp(root, "placeholders.placeholderapi.refresh-seconds", 10, 1, 3600),
                string(root, "placeholders.placeholderapi.missing-value", ""));
    }

    private Map<String, TextColor> parsePalette(@Nullable ConfigurationSection section) {
        Map<String, TextColor> palette = new LinkedHashMap<>();
        palette.put("primary", TextColor.color(0xB98CFF));
        palette.put("secondary", TextColor.color(0x8DB6FF));
        palette.put("highlight", TextColor.color(0xF3EEFF));
        palette.put("text", TextColor.color(0xDCD3F5));
        palette.put("muted", TextColor.color(0x9C93BD));
        palette.put("subtle", TextColor.color(0x6F6890));
        palette.put("line", TextColor.color(0x4B4566));
        palette.put("good", TextColor.color(0x8EE8C0));
        palette.put("warn", TextColor.color(0xFFD48A));
        palette.put("bad", TextColor.color(0xFF8FA3));
        if (section == null) {
            return Map.copyOf(palette);
        }
        for (String key : section.getKeys(false)) {
            String name = key.toLowerCase(Locale.ROOT);
            if (!TAG_NAME.matcher(name).matches() || StandardTags.defaults().has(name) || name.startsWith("eepy_")) {
                warn("palette." + key + ": '" + name + "' can not be used as a colour name");
                continue;
            }
            TextColor color = color(section.getString(key));
            if (color == null) {
                warn("palette." + key + ": '" + section.getString(key) + "' is not a colour (use #RRGGBB)");
                continue;
            }
            palette.put(name, color);
        }
        return Map.copyOf(palette);
    }

    private Messages parseMessages(@Nullable ConfigurationSection section) {
        Map<String, String> entries = new HashMap<>();
        if (section != null) {
            for (String key : section.getKeys(false)) {
                if (!key.equals("prefix") && section.isString(key)) {
                    entries.put(key, section.getString(key));
                }
            }
        }
        return new Messages(section == null ? "" : section.getString("prefix", ""), Map.copyOf(entries));
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private String designReference(ConfigurationSection root, String path, Map<String, Design> designs, String fallback) {
        String id = string(root, path, fallback).toLowerCase(Locale.ROOT);
        if (designs.containsKey(id)) {
            return id;
        }
        warn(path + ": unknown design '" + id + "', using '" + fallback + "'");
        return fallback;
    }

    private static Set<DayOfWeek> days(@Nullable Object raw) {
        if (raw == null) {
            return Set.of();
        }
        Collection<?> values = raw instanceof Collection<?> collection ? collection : List.of(raw);
        EnumSet<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
        for (Object value : values) {
            try {
                days.add(DayOfWeek.valueOf(value.toString().trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("'" + value + "' is not a weekday (MONDAY ... SUNDAY)");
            }
        }
        return Set.copyOf(days);
    }

    static @Nullable TextColor color(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim();
        return value.startsWith("#") ? TextColor.fromHexString(value) : NamedTextColor.NAMES.value(value.toLowerCase(Locale.ROOT));
    }

    private String string(ConfigurationSection root, String path, String fallback) {
        String value = root.getString(path);
        return value != null ? value : fallback;
    }

    private int clamp(ConfigurationSection root, String path, int fallback, int min, int max) {
        int value = root.getInt(path, fallback);
        if (value < min || value > max) {
            warn(path + " must be between " + min + " and " + max + ", using " + fallback);
            return fallback;
        }
        return value;
    }

    private DateTimeFormatter formatter(ConfigurationSection root, String path, String fallback) {
        String pattern = string(root, path, fallback);
        try {
            return DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH);
        } catch (IllegalArgumentException e) {
            warn(path + ": invalid pattern '" + pattern + "', using '" + fallback + "'");
            return DateTimeFormatter.ofPattern(fallback, Locale.ENGLISH);
        }
    }

    private <E extends Enum<E>> E enumValue(ConfigurationSection root, String path, Class<E> type, E fallback,
                                            Set<E> allowed) {
        String raw = root.getString(path);
        if (raw == null) {
            return fallback;
        }
        try {
            E value = Enum.valueOf(type, raw.trim().toUpperCase(Locale.ROOT));
            if (allowed.contains(value)) {
                return value;
            }
        } catch (IllegalArgumentException ignored) {
            // reported below
        }
        warn(path + ": '" + raw + "' is not one of " + allowed);
        return fallback;
    }

    private void warn(String message) {
        warnings.add(message);
    }
}
