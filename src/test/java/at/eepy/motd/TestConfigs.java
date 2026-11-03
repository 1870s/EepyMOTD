package at.eepy.motd;

import at.eepy.motd.config.ConfigParser;
import at.eepy.motd.config.PluginConfig;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Loads the bundled config.yml the same way the plugin does. */
public final class TestConfigs {

    private TestConfigs() {
    }

    public static YamlConfiguration bundledYaml() {
        try (InputStream in = Objects.requireNonNull(TestConfigs.class.getResourceAsStream("/config.yml"))) {
            return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static PluginConfig bundled() {
        return new ConfigParser().parse(bundledYaml());
    }

    /** Bundled config with some keys changed, e.g. {@code with("rotation.mode", "RANDOM")}. */
    public static PluginConfig with(Object... pathsAndValues) {
        YamlConfiguration yaml = bundledYaml();
        for (int i = 0; i < pathsAndValues.length; i += 2) {
            yaml.set((String) pathsAndValues[i], pathsAndValues[i + 1]);
        }
        return new ConfigParser().parse(yaml);
    }
}
