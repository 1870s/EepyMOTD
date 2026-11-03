package at.eepy.motd.config;

import at.eepy.motd.TestConfigs;
import at.eepy.motd.motd.MotdRenderer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.EventPriority;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ConfigParserTest {

    @Test
    void bundledConfigLoadsWithoutWarnings() {
        ConfigParser parser = new ConfigParser();
        PluginConfig config = parser.parse(TestConfigs.bundledYaml());
        assertEquals(List.of(), parser.warnings());
        assertEquals(List.of(), new MotdRenderer(config, "26.1.2").warnings(), "every design fits and resolves");

        assertEquals("mc.eepy.at", config.branding().ip());
        assertEquals("eepy.at", config.branding().website());
        assertEquals("dsc.gg/muezza", config.branding().discord());
        assertTrue(config.designs().keySet().containsAll(List.of(
                "default", "community", "discord", "website", "event", "maintenance", "christmas")));
        assertEquals(PluginConfig.Mode.STATIC, config.rotation().mode());
        assertEquals("default", config.rotation().staticDesign());
        assertEquals(List.of("newyear", "christmas", "halloween"),
                config.events().stream().map(PluginConfig.EventDefinition::id).toList(), "enabled, by priority");
        assertFalse(config.maintenance().blockJoins().enabled(), "joins are never blocked by default");
        assertEquals(EventPriority.HIGH, config.listenerPriority());
    }

    @Test
    void mistakesBecomeWarningsWithFallbacks() {
        YamlConfiguration yaml = TestConfigs.bundledYaml();
        yaml.set("rotation.mode", "SOMETIMES");
        yaml.set("rotation.static", "missing");
        yaml.set("rotation.pool", List.of("default", "ghost"));
        yaml.set("rotation.schedule", List.of(Map.of("design", "discord", "from", "25:00", "to", "26:00")));
        yaml.set("events.christmas.start", "13-40");
        yaml.set("events.halloween.designs", List.of("nope"));
        yaml.set("maintenance.schedule", List.of(Map.of("start", "2026-11-10 04:00", "end", "2026-11-10 02:00")));
        yaml.set("palette.bold", "#FFFFFF");
        yaml.set("compatibility.listener-priority", "MONITOR");
        yaml.set("designs.default.line3", "too much");

        ConfigParser parser = new ConfigParser();
        PluginConfig config = parser.parse(yaml);
        String warnings = String.join("\n", parser.warnings());

        for (String expected : List.of("rotation.mode", "rotation.static", "ghost", "rotation.schedule[0]",
                "events.christmas", "events.halloween", "maintenance.schedule[0]", "palette.bold",
                "listener-priority", "line3")) {
            assertTrue(warnings.contains(expected), "missing warning for " + expected + ":\n" + warnings);
        }
        assertEquals(PluginConfig.Mode.STATIC, config.rotation().mode());
        assertEquals("default", config.rotation().staticDesign());
        assertEquals(List.of("default"), config.rotation().pool());
        assertEquals(List.of("newyear"), config.events().stream().map(PluginConfig.EventDefinition::id).toList());
        assertEquals(EventPriority.HIGH, config.listenerPriority());
    }

    @Test
    void tooWideLinesAndUnknownTagsAreReported() {
        PluginConfig config = TestConfigs.with(
                "designs.default.line1", "<bold>This line is definitely far too long for the server list</bold>",
                "designs.default.line2", "<sparkle>hi</sparkle> {online}");
        List<String> warnings = new MotdRenderer(config, "26.1.2").warnings();
        assertTrue(warnings.stream().anyMatch(w -> w.contains("designs.default.line1 is about")), warnings.toString());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("unknown tag <sparkle>")), warnings.toString());
    }
}
