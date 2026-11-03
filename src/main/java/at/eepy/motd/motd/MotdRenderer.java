package at.eepy.motd.motd;

import at.eepy.motd.config.PluginConfig;
import at.eepy.motd.placeholder.PingValues;
import at.eepy.motd.text.FontWidth;
import at.eepy.motd.text.LegacyConverter;
import at.eepy.motd.text.TemplateCompiler;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns designs into the components sent in the ping response.
 * <p>
 * All templates are compiled when the config is loaded. Rendering is cached by
 * its inputs (design, hover lines and {@link PingValues}), so a flood of pings
 * with the same online count costs one map lookup each. The renderer is
 * immutable apart from its synchronized cache and safe to use from the
 * network threads that fire ping events.
 */
public final class MotdRenderer {

    /** Hover entries are plain legacy strings; only the 16 classic colours survive. */
    private static final LegacyComponentSerializer HOVER = LegacyComponentSerializer.legacySection();
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();
    private static final Pattern LEFTOVER_TAG = Pattern.compile("<[a-zA-Z_#/!][^<>]*>");
    private static final PingValues SAMPLE = new PingValues(128, 128, "23:59", "30.12.2026", "Wednesday", "2026",
            "Build Contest", "6d 23h", "1h 59m", List.of());

    /** A design with compiled templates. */
    public record CompiledDesign(String id, String line1, String line2, @Nullable String icon,
                                 @Nullable List<String> hover) {
    }

    /** What a ping response shows. */
    public record Rendered(Component motd, List<String> hover) {
    }

    private record CacheKey(String design, List<String> hover, PingValues values) {
    }

    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final PluginConfig.Layout layout;
    private final Map<String, CompiledDesign> designs;
    private final List<String> hoverLines;
    private final List<String> maintenanceHover;
    private final TagResolver staticTags;
    private final Map<CacheKey, Rendered> cache;
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();
    private final List<String> warnings = new ArrayList<>();
    private final List<String> papiTokens;

    public MotdRenderer(PluginConfig config, String minecraftVersion) {
        this.layout = config.layout();
        TemplateCompiler compiler = new TemplateCompiler(layout.legacyColors());

        Map<String, CompiledDesign> compiled = new LinkedHashMap<>();
        config.designs().forEach((id, design) -> compiled.put(id, new CompiledDesign(
                id,
                compiler.compile(design.line1(), "designs." + id + ".line1"),
                compiler.compile(design.line2(), "designs." + id + ".line2"),
                design.icon(),
                design.hover() == null ? null : compileAll(compiler, design.hover(), "designs." + id + ".hover"))));
        this.designs = Collections.unmodifiableMap(compiled); // keeps config order
        this.hoverLines = compileAll(compiler, config.hover().lines(), "hover.lines");
        this.maintenanceHover = compileAll(compiler, config.maintenance().hover(), "maintenance.hover");
        this.papiTokens = compiler.papiTokens();
        warnings.addAll(compiler.warnings());

        List<TagResolver> resolvers = new ArrayList<>();
        config.palette().forEach((name, color) -> resolvers.add(TagResolver.resolver(name, Tag.styling(color))));
        PluginConfig.Branding branding = config.branding();
        resolvers.add(Placeholder.unparsed("eepy_server", branding.serverName()));
        resolvers.add(Placeholder.unparsed("eepy_ip", branding.ip()));
        resolvers.add(Placeholder.unparsed("eepy_website", branding.website()));
        resolvers.add(Placeholder.unparsed("eepy_discord", branding.discord()));
        resolvers.add(Placeholder.unparsed("eepy_version", minecraftVersion));
        this.staticTags = TagResolver.resolver(resolvers);

        int capacity = config.cacheSize();
        this.cache = Collections.synchronizedMap(new LinkedHashMap<>(capacity * 2, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<CacheKey, Rendered> eldest) {
                return size() > capacity;
            }
        });
        validate();
    }

    public Rendered render(String designId, HoverSource hoverSource, PingValues values) {
        CompiledDesign design = designs.get(designId);
        if (design == null) {
            design = designs.values().iterator().next();
        }
        List<String> hover = switch (hoverSource) {
            case NONE -> List.of();
            case MAINTENANCE -> maintenanceHover;
            case DESIGN -> design.hover() != null ? design.hover() : hoverLines;
        };
        CacheKey key = new CacheKey(design.id(), hover, values);
        Rendered cached = cache.get(key);
        if (cached != null) {
            hits.incrementAndGet();
            return cached;
        }
        misses.incrementAndGet();
        TagResolver tags = tags(values);
        Component motd = Component.text()
                .append(line(design.line1(), tags))
                .append(Component.newline())
                .append(line(design.line2(), tags))
                .build();
        List<String> hoverText = new ArrayList<>(hover.size());
        for (String line : hover) {
            String legacy = HOVER.serialize(miniMessage.deserialize(line, tags));
            hoverText.add(legacy.isEmpty() ? "§r" : legacy); // an empty name would collapse the line
        }
        Rendered rendered = new Rendered(motd, List.copyOf(hoverText));
        cache.put(key, rendered);
        return rendered;
    }

    /** Which hover lines a ping shows. */
    public enum HoverSource { DESIGN, MAINTENANCE, NONE }

    /** Renders a text with the same tags as the MOTD, e.g. the maintenance kick message. */
    public Component renderText(String raw, PingValues values) {
        TemplateCompiler compiler = new TemplateCompiler(layout.legacyColors());
        return miniMessage.deserialize(compiler.compile(raw, "text"), tags(values));
    }

    private Component line(String template, TagResolver tags) {
        Component line = miniMessage.deserialize(template, tags);
        if (!layout.center()) {
            return line;
        }
        int padding = FontWidth.centerPadding(FontWidth.of(line), layout.lineWidth());
        return padding == 0 ? line : Component.text(" ".repeat(padding)).append(line);
    }

    private TagResolver tags(PingValues values) {
        List<String> external = values.external();
        return TagResolver.resolver(
                staticTags,
                Placeholder.unparsed("eepy_online", Integer.toString(values.online())),
                Placeholder.unparsed("eepy_max", Integer.toString(values.max())),
                Placeholder.unparsed("eepy_time", values.time()),
                Placeholder.unparsed("eepy_date", values.date()),
                Placeholder.unparsed("eepy_weekday", values.weekday()),
                Placeholder.unparsed("eepy_year", values.year()),
                Placeholder.unparsed("eepy_event", values.event()),
                Placeholder.unparsed("eepy_event_ends", values.eventEnds()),
                Placeholder.unparsed("eepy_maintenance_ends", values.maintenanceEnds()),
                TagResolver.resolver(TemplateCompiler.PAPI_TAG, (arguments, context) -> {
                    int id = arguments.popOr("missing id").asInt().orElse(-1);
                    String value = id >= 0 && id < external.size() ? external.get(id) : "";
                    return Tag.selfClosingInserting(LegacyConverter.containsCodes(value)
                            ? LegacyComponentSerializer.legacyAmpersand().deserialize(value.replace('§', '&'))
                            : Component.text(value));
                }));
    }

    /** Renders every design with long sample values to find unknown tags and over-long lines. */
    private void validate() {
        TagResolver tags = tags(SAMPLE);
        for (CompiledDesign design : designs.values()) {
            checkLine(design.id(), "line1", design.line1(), tags);
            checkLine(design.id(), "line2", design.line2(), tags);
        }
    }

    private void checkLine(String design, String name, String template, TagResolver tags) {
        Component line = miniMessage.deserialize(template, tags);
        String plain = PLAIN.serialize(line);
        Matcher leftover = LEFTOVER_TAG.matcher(plain);
        if (leftover.find()) {
            warnings.add("designs." + design + "." + name + ": unknown tag " + leftover.group() + " is shown as text");
        }
        int width = FontWidth.of(line);
        if (width > layout.lineWidth()) {
            warnings.add("designs." + design + "." + name + " is about " + width + " px wide (limit "
                    + layout.lineWidth() + " px); the server list may wrap or cut it");
        }
    }

    private static List<String> compileAll(TemplateCompiler compiler, List<String> lines, String path) {
        List<String> compiled = new ArrayList<>(lines.size());
        for (int i = 0; i < lines.size(); i++) {
            compiled.add(compiler.compile(lines.get(i), path + "[" + i + "]"));
        }
        return List.copyOf(compiled);
    }

    public int designCount() {
        return designs.size();
    }

    public Set<String> designIds() {
        return designs.keySet();
    }

    public boolean hasDesign(String id) {
        return designs.containsKey(id);
    }

    public @Nullable CompiledDesign design(String id) {
        return designs.get(id);
    }

    public List<String> papiTokens() {
        return papiTokens;
    }

    public List<String> warnings() {
        return List.copyOf(warnings);
    }

    public long cacheHits() {
        return hits.get();
    }

    public long cacheMisses() {
        return misses.get();
    }
}
