package at.eepy.motd.text;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Prepares a config text once at load time so a ping only has to run one
 * MiniMessage parse:
 * <ol>
 *   <li>classic colour codes become MiniMessage tags (optional),</li>
 *   <li>{@code {online}} style placeholders become private tags whose values are
 *       inserted as plain text (never parsed, so they can not inject formatting),</li>
 *   <li>{@code %placeholderapi%} tokens become {@code <eepy_papi:N>} tags that read
 *       from the PlaceholderAPI cache.</li>
 * </ol>
 * One compiler instance is used per config load; it collects every PlaceholderAPI
 * token so the cache knows what to refresh.
 */
public final class TemplateCompiler {

    /** Placeholder names understood in {@code {curly}} form. */
    public static final Set<String> PLACEHOLDERS = Set.of(
            "online", "max", "server", "ip", "website", "discord", "version", "time", "date", "weekday", "year",
            "event", "event_ends", "maintenance_ends");

    public static final String TAG_PREFIX = "eepy_";
    public static final String PAPI_TAG = "eepy_papi";

    private static final Pattern CURLY = Pattern.compile("\\{([a-zA-Z_]+)}");
    private static final Pattern PAPI = Pattern.compile("%([A-Za-z0-9]+_[^%\\s<>{}]+)%");

    private final boolean legacyColors;
    private final Map<String, Integer> papiTokens = new LinkedHashMap<>();
    private final List<String> warnings = new ArrayList<>();

    public TemplateCompiler(boolean legacyColors) {
        this.legacyColors = legacyColors;
    }

    public String compile(String raw, String path) {
        String text = legacyColors ? LegacyConverter.toMiniMessage(raw) : raw;

        Matcher curly = CURLY.matcher(text);
        StringBuilder out = new StringBuilder(text.length() + 16);
        while (curly.find()) {
            String name = curly.group(1).toLowerCase(Locale.ROOT);
            if (PLACEHOLDERS.contains(name)) {
                curly.appendReplacement(out, "<" + TAG_PREFIX + name + ">");
            } else {
                warnings.add(path + ": unknown placeholder {" + curly.group(1) + "}, shown as text");
                curly.appendReplacement(out, Matcher.quoteReplacement(curly.group()));
            }
        }
        curly.appendTail(out);
        text = out.toString();

        Matcher papi = PAPI.matcher(text);
        out = new StringBuilder(text.length() + 16);
        while (papi.find()) {
            int id = papiTokens.computeIfAbsent(papi.group(), token -> papiTokens.size());
            papi.appendReplacement(out, "<" + PAPI_TAG + ":" + id + ">");
        }
        papi.appendTail(out);
        return out.toString();
    }

    /** PlaceholderAPI tokens in id order, e.g. {@code [%server_tps_1%, ...]}. */
    public List<String> papiTokens() {
        return List.copyOf(papiTokens.keySet());
    }

    public List<String> warnings() {
        return List.copyOf(warnings);
    }
}
