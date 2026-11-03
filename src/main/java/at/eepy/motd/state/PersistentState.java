package at.eepy.motd.state;

import at.eepy.motd.config.PluginConfig.Mode;
import at.eepy.motd.motd.MotdSelector;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;

/**
 * Command-controlled state that survives restarts (data.yml): the maintenance
 * toggle and a design or mode chosen with /eepymotd. Kept apart from config.yml
 * so commands never rewrite the user's configuration.
 */
public final class PersistentState {

    private final Plugin plugin;
    private final File file;
    private final AtomicReference<String> pendingWrite = new AtomicReference<>();
    private volatile boolean maintenance;
    private volatile MotdSelector.ManualChoice override = MotdSelector.ManualChoice.NONE;

    public PersistentState(Plugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "data.yml");
    }

    public void load() {
        if (!file.isFile()) {
            return;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file);
        } catch (IOException | InvalidConfigurationException e) {
            plugin.getLogger().log(Level.WARNING, "Could not read data.yml, using defaults", e);
            return;
        }
        maintenance = yaml.getBoolean("maintenance", false);
        String design = yaml.getString("override.design");
        String mode = yaml.getString("override.mode");
        Mode parsedMode = null;
        if (mode != null) {
            try {
                parsedMode = Mode.valueOf(mode.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("data.yml: unknown mode '" + mode + "' ignored");
            }
        }
        override = new MotdSelector.ManualChoice(design, parsedMode);
    }

    public boolean maintenance() {
        return maintenance;
    }

    public MotdSelector.ManualChoice override() {
        return override;
    }

    public void setMaintenance(boolean enabled) {
        maintenance = enabled;
        save();
    }

    public void setOverride(MotdSelector.ManualChoice value) {
        override = value;
        save();
    }

    /** Serialises on the calling thread, writes asynchronously. */
    private void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().setHeader(List.of("EepyMOTD runtime data. Managed by /eepymotd, edit config.yml instead."));
        yaml.set("maintenance", maintenance);
        MotdSelector.ManualChoice current = override;
        yaml.set("override.design", current.design());
        yaml.set("override.mode", current.mode() == null ? null : current.mode().name());
        pendingWrite.set(yaml.saveToString());
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, this::writePending);
    }

    /** Always writes the newest content, so out-of-order tasks can not restore an old value. */
    private synchronized void writePending() {
        String content = pendingWrite.getAndSet(null);
        if (content == null) {
            return;
        }
        try {
            Files.createDirectories(file.toPath().getParent());
            File temp = new File(file.getParentFile(), "data.yml.tmp");
            Files.writeString(temp.toPath(), content, StandardCharsets.UTF_8);
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Could not save data.yml", e);
        }
    }
}
