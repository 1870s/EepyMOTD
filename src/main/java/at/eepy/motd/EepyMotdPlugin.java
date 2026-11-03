package at.eepy.motd;

import at.eepy.motd.command.EepyMotdCommand;
import at.eepy.motd.config.ConfigParser;
import at.eepy.motd.config.PluginConfig;
import at.eepy.motd.icon.IconManager;
import at.eepy.motd.motd.JoinGuard;
import at.eepy.motd.motd.MotdRenderer;
import at.eepy.motd.motd.MotdRuntime;
import at.eepy.motd.motd.MotdSelector;
import at.eepy.motd.motd.PingService;
import at.eepy.motd.motd.VanishTracker;
import at.eepy.motd.placeholder.ExternalPlaceholderCache;
import at.eepy.motd.placeholder.PingValues;
import at.eepy.motd.placeholder.PlaceholderApiBridge;
import at.eepy.motd.placeholder.PlaceholderBridge;
import at.eepy.motd.state.PersistentState;
import com.destroystokyo.paper.event.server.PaperServerListPingEvent;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.jspecify.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.logging.Level;

/**
 * EepyMOTD entry point. Builds a {@link MotdRuntime} from config.yml, hands it to
 * the {@link PingService} and owns the two optional main-thread tasks
 * (vanish sampling and PlaceholderAPI refresh), which only run when needed.
 */
public final class EepyMotdPlugin extends JavaPlugin {

    /** Plugins known to rewrite the server list response. */
    private static final List<String> OTHER_MOTD_PLUGINS = List.of(
            "MiniMOTD", "ServerListPlus", "AdvancedServerList", "MOTD", "ServerListMOTD", "BetterMOTD",
            "MotdManager", "Maintenance", "AdvancedMOTD");

    private PersistentState state;
    private @Nullable PlaceholderBridge placeholderApi;
    private @Nullable PingService pingService;
    private @Nullable EventPriority registeredPriority;
    private final List<BukkitTask> tasks = new ArrayList<>();
    private String minecraftVersion = "";
    private List<String> lastParseWarnings = List.of();

    /** Result of a reload, reported back to the command sender. */
    public record ReloadResult(boolean success, List<String> warnings, @Nullable String error, long millis, int icons) {
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        minecraftVersion = Bukkit.getMinecraftVersion();
        state = new PersistentState(this);
        state.load();
        if (getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            try {
                placeholderApi = new PlaceholderApiBridge();
            } catch (LinkageError e) {
                getLogger().log(Level.WARNING, "PlaceholderAPI is installed but its API could not be loaded", e);
            }
        }

        PluginConfig config;
        try {
            config = loadConfig();
        } catch (IOException | InvalidConfigurationException e) {
            getLogger().log(Level.SEVERE, "config.yml could not be read, using the bundled defaults until it is fixed", e);
            config = parse(bundledDefaults());
        }
        List<String> warnings = new ArrayList<>(lastParseWarnings);
        MotdRuntime runtime = buildRuntime(config, warnings);
        PingService service = new PingService(getLogger(), state, runtime);
        this.pingService = service;

        registerPingListener(config.listenerPriority());
        getServer().getPluginManager().registerEvents(new JoinGuard(getServer(), state, service::runtime), this);
        EepyMotdCommand command = new EepyMotdCommand(this);
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS,
                event -> event.registrar().register(command.build(), "EepyMOTD server list commands", List.of("emotd")));

        startTasks(runtime);
        logWarnings(runtime.warnings());
        getServer().getScheduler().runTask(this, this::warnAboutOtherMotdPlugins);
        getLogger().info("Enabled with " + runtime.renderer().designCount() + " designs, " + runtime.icons().names().size()
                + " icons, mode " + config.rotation().mode() + ". PlaceholderAPI: "
                + (placeholderApi != null ? "hooked" : "not found"));
    }

    @Override
    public void onDisable() {
        cancelTasks();
        HandlerList.unregisterAll(this);
        pingService = null;
    }

    /** Reloads config.yml and icons; on a YAML error the active configuration is kept. */
    public ReloadResult reload() {
        long started = System.nanoTime();
        PingService service = pingService();
        PluginConfig config;
        try {
            config = loadConfig();
        } catch (IOException | InvalidConfigurationException e) {
            getLogger().log(Level.WARNING, "Reload failed, keeping the previous configuration", e);
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            int newline = message.indexOf('\n');
            return new ReloadResult(false, List.of(), newline < 0 ? message : message.substring(0, newline),
                    (System.nanoTime() - started) / 1_000_000, 0);
        }
        List<String> warnings = new ArrayList<>(lastParseWarnings);
        MotdRuntime runtime = buildRuntime(config, warnings);
        service.apply(runtime);
        if (config.listenerPriority() != registeredPriority) {
            registerPingListener(config.listenerPriority());
        }
        cancelTasks();
        startTasks(runtime);
        logWarnings(runtime.warnings());
        return new ReloadResult(true, runtime.warnings(), null, (System.nanoTime() - started) / 1_000_000,
                runtime.icons().names().size());
    }

    public PingService pingService() {
        return Objects.requireNonNull(pingService, "plugin is not enabled");
    }

    public PersistentState state() {
        return state;
    }

    public boolean placeholderApiHooked() {
        return placeholderApi != null;
    }

    public String minecraftVersion() {
        return minecraftVersion;
    }

    public List<String> detectOtherMotdPlugins() {
        PluginManager pluginManager = getServer().getPluginManager();
        return OTHER_MOTD_PLUGINS.stream()
                .map(pluginManager::getPlugin)
                .filter(Objects::nonNull)
                .filter(Plugin::isEnabled)
                .map(Plugin::getName)
                .toList();
    }

    // ── internals ──────────────────────────────────────────────────────────

    private PluginConfig loadConfig() throws IOException, InvalidConfigurationException {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.load(new File(getDataFolder(), "config.yml"));
        // Missing keys fall back to the bundled defaults, so older configs keep working after updates.
        yaml.setDefaults(bundledDefaults());
        return parse(yaml);
    }

    private PluginConfig parse(YamlConfiguration yaml) {
        ConfigParser parser = new ConfigParser();
        PluginConfig config = parser.parse(yaml);
        lastParseWarnings = parser.warnings();
        return config;
    }

    private YamlConfiguration bundledDefaults() {
        try (InputStream in = Objects.requireNonNull(getResource("config.yml"), "config.yml missing in jar")) {
            return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("Could not read the bundled config.yml", e);
        }
    }

    private MotdRuntime buildRuntime(PluginConfig config, List<String> warnings) {
        MotdRenderer renderer = new MotdRenderer(config, minecraftVersion);
        warnings.addAll(renderer.warnings());
        IconManager icons = config.icons().enabled() ? IconManager.load(this, config.icons().resize()) : IconManager.empty();
        warnings.addAll(icons.warnings());
        if (config.icons().enabled()) {
            checkIcon(icons, config.icons().defaultIcon(), "icons.default", warnings);
            checkIcon(icons, config.maintenance().icon(), "maintenance.icon", warnings);
            config.designs().values().forEach(d -> checkIcon(icons, d.icon(), "designs." + d.id() + ".icon", warnings));
            config.events().forEach(e -> checkIcon(icons, e.icon(), "events." + e.id() + ".icon", warnings));
        }
        ExternalPlaceholderCache external = new ExternalPlaceholderCache(renderer.papiTokens(),
                config.placeholders().placeholderApi() ? placeholderApi : null,
                config.placeholders().placeholderApiMissingValue(), getLogger());
        if (!renderer.papiTokens().isEmpty() && placeholderApi == null) {
            warnings.add("PlaceholderAPI placeholders are used but PlaceholderAPI is not installed; "
                    + "they show placeholders.placeholderapi.missing-value");
        }
        external.refresh();

        String label = config.maintenance().versionLabel();
        String maintenanceVersion = label.isBlank() ? "" : LegacyComponentSerializer.legacySection()
                .serialize(renderer.renderText(label, sampleValues()));
        MotdSelector selector = new MotdSelector(config, renderer);
        return new MotdRuntime(config, renderer, selector, icons, external, maintenanceVersion, List.copyOf(warnings));
    }

    private PingValues sampleValues() {
        return new PingValues(getServer().getOnlinePlayers().size(), getServer().getMaxPlayers(), "", "", "", "",
                "", "", "soon", List.of());
    }

    private static void checkIcon(IconManager icons, @Nullable String name, String path, List<String> warnings) {
        if (name != null && !PluginConfig.Icons.SERVER_ICON.equals(name) && icons.get(name) == null) {
            warnings.add(path + ": icon '" + name + "' not found in plugins/EepyMOTD/icons, using the default icon");
        }
    }

    private void registerPingListener(EventPriority priority) {
        PingService service = pingService();
        HandlerList.unregisterAll(service);
        getServer().getPluginManager().registerEvent(PaperServerListPingEvent.class, service, priority, service, this, true);
        registeredPriority = priority;
    }

    /** Main-thread helpers, started only when the configuration needs them. */
    private void startTasks(MotdRuntime runtime) {
        PluginConfig config = runtime.config();
        PingService service = pingService();
        if (config.playerCount().excludeVanished() || (config.hover().enabled() && config.hover().showOnlinePlayers())) {
            tasks.add(getServer().getScheduler().runTaskTimer(this, new VanishTracker(getServer(), service), 1L, 40L));
        }
        if (runtime.external().active()) {
            long period = config.placeholders().placeholderApiRefreshSeconds() * 20L;
            tasks.add(getServer().getScheduler().runTaskTimer(this, runtime.external()::refresh, period, period));
        }
    }

    private void cancelTasks() {
        tasks.forEach(BukkitTask::cancel);
        tasks.clear();
    }

    private void warnAboutOtherMotdPlugins() {
        PingService service = pingService;
        if (service == null || !service.runtime().config().warnAboutOtherMotdPlugins()) {
            return;
        }
        for (String name : detectOtherMotdPlugins()) {
            getLogger().warning(name + " is installed and may also change the server list. The plugin with the "
                    + "later listener priority wins (compatibility.listener-priority).");
        }
    }

    private void logWarnings(List<String> warnings) {
        warnings.forEach(getLogger()::warning);
    }
}
