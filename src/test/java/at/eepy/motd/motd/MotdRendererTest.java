package at.eepy.motd.motd;

import at.eepy.motd.TestConfigs;
import at.eepy.motd.config.PluginConfig;
import at.eepy.motd.placeholder.PingValues;
import at.eepy.motd.text.FontWidth;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MotdRendererTest {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();
    private static final PingValues VALUES = new PingValues(12, 100, "21:30", "03.11.2026", "Tuesday", "2026",
            "Build Contest", "2d 5h", "soon", List.of());

    private final PluginConfig config = TestConfigs.bundled();
    private final MotdRenderer renderer = new MotdRenderer(config, "26.1.2");

    private String[] lines(String design) {
        Component motd = renderer.render(design, MotdRenderer.HoverSource.DESIGN, VALUES).motd();
        return PLAIN.serialize(motd).split("\n");
    }

    @Test
    void defaultDesignShowsBrandingInTwoCentredLines() {
        String[] lines = lines("default");
        assertEquals(2, lines.length);
        assertEquals("✦ e e p y ✦  │  cozy minecraft community", lines[0].strip());
        assertEquals("Play at mc.eepy.at  •  Discord dsc.gg/muezza", lines[1].strip());
        assertTrue(lines[0].startsWith(" "), "padded for centring");
    }

    @Test
    void everyDesignRendersAndFits() {
        for (String id : renderer.designIds()) {
            Component motd = renderer.render(id, MotdRenderer.HoverSource.DESIGN, VALUES).motd();
            String[] lines = PLAIN.serialize(motd).split("\n");
            assertEquals(2, lines.length, id);
            for (Component line : List.of(motd.children().get(0), motd.children().get(2))) {
                int width = FontWidth.of(line);
                assertTrue(width <= config.layout().lineWidth() + FontWidth.SPACE, id + " is " + width + " px");
            }
            System.out.printf("%-12s | %s%n%-12s | %s%n", id, lines[0], "", lines[1]);
        }
    }

    @Test
    void placeholdersAreFilledAndCannotInjectFormatting() {
        PluginConfig injected = TestConfigs.with("branding.discord", "<red>evil</red>");
        String line = PLAIN.serialize(new MotdRenderer(injected, "26.1.2")
                .render("discord", MotdRenderer.HoverSource.DESIGN, VALUES).motd());
        assertTrue(line.contains("<red>evil</red>"), "inserted as text, not parsed: " + line);

        String event = PLAIN.serialize(renderer.render("event", MotdRenderer.HoverSource.DESIGN, VALUES).motd());
        assertTrue(event.contains("Build Contest · ends in 2d 5h"), event);
    }

    @Test
    void hoverUsesLegacyTextWithClassicColours() {
        List<String> hover = renderer.render("default", MotdRenderer.HoverSource.DESIGN, VALUES).hover();
        assertEquals(7, hover.size());
        assertTrue(hover.getFirst().startsWith("§d§l✦ eepy"), hover.getFirst());
        assertTrue(hover.contains("§r"), "empty lines are kept");
        assertTrue(hover.getLast().contains("12 of 100 players online"), hover.getLast());
        assertFalse(String.join("", hover).contains("§x"), "no hex codes in hover lines");

        List<String> maintenance = renderer.render("maintenance", MotdRenderer.HoverSource.MAINTENANCE, VALUES).hover();
        assertTrue(maintenance.getFirst().contains("Maintenance"));
        assertEquals(List.of(), renderer.render("default", MotdRenderer.HoverSource.NONE, VALUES).hover());
    }

    @Test
    void equalInputsAreServedFromTheCache() {
        renderer.render("default", MotdRenderer.HoverSource.DESIGN, VALUES);
        long misses = renderer.cacheMisses();
        for (int i = 0; i < 100; i++) {
            renderer.render("default", MotdRenderer.HoverSource.DESIGN, VALUES);
        }
        assertEquals(misses, renderer.cacheMisses());
        assertTrue(renderer.cacheHits() >= 100);
        renderer.render("default", MotdRenderer.HoverSource.DESIGN,
                new PingValues(13, 100, "21:30", "03.11.2026", "Tuesday", "2026", "", "", "soon", List.of()));
        assertEquals(misses + 1, renderer.cacheMisses(), "a new online count renders again");
    }

    @Test
    void durationsAreShortAndReadable() {
        assertEquals("2d 5h", PingService.formatDuration(Duration.ofHours(53).plusMinutes(10), ""));
        assertEquals("3h 20m", PingService.formatDuration(Duration.ofMinutes(200), ""));
        assertEquals("12m", PingService.formatDuration(Duration.ofMinutes(12), ""));
        assertEquals("<1m", PingService.formatDuration(Duration.ofSeconds(30), ""));
        assertEquals("soon", PingService.formatDuration(null, "soon"));
    }
}
