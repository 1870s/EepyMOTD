package at.eepy.motd.text;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TextTest {

    @Test
    void legacyCodesBecomeMiniMessage() {
        assertEquals("<reset><light_purple><bold>eepy", LegacyConverter.toMiniMessage("&d&leepy"));
        assertEquals("<reset><#b98cff>violet", LegacyConverter.toMiniMessage("&#B98CFFviolet"));
        assertEquals("<reset><#b98cff>x", LegacyConverter.toMiniMessage("§x§B§9§8§C§F§Fx"));
        assertEquals("R&D stays", LegacyConverter.toMiniMessage("R&D stays"));
        assertEquals("<gradient:#fff:#000>kept</gradient>", LegacyConverter.toMiniMessage("<gradient:#fff:#000>kept</gradient>"));
    }

    @Test
    void placeholdersAreCompiledToPrivateTags() {
        TemplateCompiler compiler = new TemplateCompiler(true);
        assertEquals("<eepy_online>/<eepy_max> {nope}", compiler.compile("{online}/{MAX} {nope}", "test"));
        assertEquals("<eepy_papi:0> <eepy_papi:1> <eepy_papi:0> 50% off",
                compiler.compile("%server_tps_1% %server_uptime% %server_tps_1% 50% off", "test"));
        assertEquals(List.of("%server_tps_1%", "%server_uptime%"), compiler.papiTokens());
        assertEquals(1, compiler.warnings().size());
        assertTrue(compiler.warnings().getFirst().contains("{nope}"));
    }

    @Test
    void fontWidthFollowsTheVanillaFont() {
        assertEquals(6 * 4, FontWidth.of(Component.text("eepy")));
        assertEquals(7 * 4, FontWidth.of(Component.text("eepy").decorate(TextDecoration.BOLD)), "bold adds a pixel");
        assertEquals(2 + 3 + 4 + 4, FontWidth.of(Component.text("il t")));
        assertEquals(7, FontWidth.of(Component.text().decorate(TextDecoration.BOLD).append(Component.text("e")).build()),
                "bold is inherited");
        assertEquals(10, FontWidth.centerPadding(190, 270));
        assertEquals(0, FontWidth.centerPadding(300, 270));
    }
}
