package at.eepy.motd.command;

import at.eepy.motd.EepyMotdPlugin;
import at.eepy.motd.config.PluginConfig;
import at.eepy.motd.config.PluginConfig.Mode;
import at.eepy.motd.motd.MotdRenderer;
import at.eepy.motd.motd.MotdRuntime;
import at.eepy.motd.motd.MotdSelector;
import at.eepy.motd.motd.PingService;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * {@code /eepymotd} (alias {@code /emotd}) built with Paper's Brigadier API.
 * Subcommands are only suggested to senders that may use them.
 */
public final class EepyMotdCommand {

    private static final String ADMIN = "eepymotd.admin";
    private static final String RELOAD = "eepymotd.reload";
    private static final String SET = "eepymotd.set";
    private static final String MAINTENANCE = "eepymotd.maintenance";
    private static final String DEBUG = "eepymotd.debug";

    private final EepyMotdPlugin plugin;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    public EepyMotdCommand(EepyMotdPlugin plugin) {
        this.plugin = plugin;
    }

    public LiteralCommandNode<CommandSourceStack> build() {
        SuggestionProvider<CommandSourceStack> designs = (ctx, builder) -> {
            String typed = builder.getRemainingLowerCase();
            runtime().renderer().designIds().stream().filter(id -> id.startsWith(typed)).forEach(builder::suggest);
            return builder.buildFuture();
        };
        SuggestionProvider<CommandSourceStack> modes = (ctx, builder) -> {
            String typed = builder.getRemainingLowerCase();
            Arrays.stream(Mode.values()).map(mode -> mode.name().toLowerCase(Locale.ROOT))
                    .filter(mode -> mode.startsWith(typed)).forEach(builder::suggest);
            return builder.buildFuture();
        };
        return Commands.literal("eepymotd")
                .executes(this::info)
                .then(Commands.literal("help").executes(this::help))
                .then(Commands.literal("reload").requires(permission(RELOAD)).executes(this::reload))
                .then(Commands.literal("set").requires(permission(SET))
                        .then(Commands.argument("design", StringArgumentType.word()).suggests(designs)
                                .executes(ctx -> set(ctx, StringArgumentType.getString(ctx, "design")))))
                .then(Commands.literal("random").requires(permission(SET)).executes(ctx -> mode(ctx, "random")))
                .then(Commands.literal("mode").requires(permission(SET))
                        .then(Commands.argument("mode", StringArgumentType.word()).suggests(modes)
                                .executes(ctx -> mode(ctx, StringArgumentType.getString(ctx, "mode")))))
                .then(Commands.literal("reset").requires(permission(SET)).executes(this::reset))
                .then(Commands.literal("preview").requires(permission(SET))
                        .executes(ctx -> preview(ctx, null))
                        .then(Commands.argument("design", StringArgumentType.word()).suggests(designs)
                                .executes(ctx -> preview(ctx, StringArgumentType.getString(ctx, "design")))))
                .then(Commands.literal("maintenance").requires(permission(MAINTENANCE))
                        .executes(ctx -> maintenance(ctx, null))
                        .then(Commands.literal("on").executes(ctx -> maintenance(ctx, true)))
                        .then(Commands.literal("off").executes(ctx -> maintenance(ctx, false))))
                .then(Commands.literal("debug").requires(permission(DEBUG)).executes(this::debug))
                .build();
    }

    // ── subcommands ────────────────────────────────────────────────────────

    private int info(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        PluginConfig config = runtime().config();
        sender.sendMessage(prefixed("<text>EepyMOTD <primary><version></primary> <subtle>· server list design for <server></subtle></text>",
                Placeholder.unparsed("version", plugin.getPluginMeta().getVersion()),
                Placeholder.unparsed("server", config.branding().serverName())));
        sender.sendMessage(plain("<muted>Showing</muted> <text><design></text> <subtle>(<reason>)</subtle>",
                Placeholder.unparsed("design", currentSelection().design()),
                Placeholder.unparsed("reason", currentSelection().detail())));
        sender.sendMessage(plain("<subtle>Type</subtle> <text>/eepymotd help</text> <subtle>for all commands.</subtle>"));
        return Command.SINGLE_SUCCESS;
    }

    private int help(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        sender.sendMessage(prefixed("<highlight>Commands</highlight>"));
        helpLine(sender, null, "/eepymotd", "Show plugin information");
        helpLine(sender, null, "/eepymotd help", "Show this list");
        helpLine(sender, RELOAD, "/eepymotd reload", "Reload config.yml and icons");
        helpLine(sender, SET, "/eepymotd set <design>", "Always show one design");
        helpLine(sender, SET, "/eepymotd random", "Show a random design from the pool");
        helpLine(sender, SET, "/eepymotd mode <mode>", "Use static, random, sequential or scheduled");
        helpLine(sender, SET, "/eepymotd reset", "Go back to the rotation from config.yml");
        helpLine(sender, SET, "/eepymotd preview [design]", "Show a design in chat");
        helpLine(sender, MAINTENANCE, "/eepymotd maintenance [on|off]", "Toggle the maintenance MOTD");
        helpLine(sender, DEBUG, "/eepymotd debug", "Show technical information");
        return Command.SINGLE_SUCCESS;
    }

    private int reload(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        EepyMotdPlugin.ReloadResult result = plugin.reload();
        if (!result.success()) {
            sender.sendMessage(message("reload-failed",
                    Placeholder.unparsed("error", result.error() == null ? "unknown error" : result.error())));
            return 0;
        }
        sender.sendMessage(message("reload-success",
                Placeholder.unparsed("ms", Long.toString(result.millis())),
                Placeholder.unparsed("icons", Integer.toString(result.icons()))));
        if (!result.warnings().isEmpty()) {
            sender.sendMessage(message("reload-warnings",
                    Placeholder.unparsed("count", Integer.toString(result.warnings().size()))));
            if (sender instanceof Player) {
                result.warnings().forEach(warning -> sender.sendMessage(plain("<subtle>• <warning></subtle>",
                        Placeholder.unparsed("warning", warning))));
            }
        }
        return Command.SINGLE_SUCCESS;
    }

    private int set(CommandContext<CommandSourceStack> ctx, String design) {
        String id = design.toLowerCase(Locale.ROOT);
        if (!runtime().renderer().hasDesign(id)) {
            unknownDesign(ctx.getSource().getSender(), design);
            return 0;
        }
        plugin.state().setOverride(new MotdSelector.ManualChoice(id, null));
        ctx.getSource().getSender().sendMessage(message("design-set", Placeholder.unparsed("design", id)));
        return Command.SINGLE_SUCCESS;
    }

    private int mode(CommandContext<CommandSourceStack> ctx, String raw) {
        Mode mode;
        try {
            mode = Mode.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            ctx.getSource().getSender().sendMessage(prefixed("<bad>Unknown mode</bad> <text><mode></text><subtle>. Use static, random, sequential or scheduled.</subtle>",
                    Placeholder.unparsed("mode", raw)));
            return 0;
        }
        plugin.state().setOverride(new MotdSelector.ManualChoice(null, mode));
        ctx.getSource().getSender().sendMessage(message("mode-set",
                Placeholder.unparsed("mode", mode.name().toLowerCase(Locale.ROOT))));
        return Command.SINGLE_SUCCESS;
    }

    private int reset(CommandContext<CommandSourceStack> ctx) {
        plugin.state().setOverride(MotdSelector.ManualChoice.NONE);
        ctx.getSource().getSender().sendMessage(message("reset",
                Placeholder.unparsed("mode", runtime().config().rotation().mode().name().toLowerCase(Locale.ROOT))));
        return Command.SINGLE_SUCCESS;
    }

    private int preview(CommandContext<CommandSourceStack> ctx, @Nullable String design) {
        CommandSender sender = ctx.getSource().getSender();
        MotdRuntime runtime = runtime();
        MotdSelector.Selection selection = currentSelection();
        String id = design == null ? selection.design() : design.toLowerCase(Locale.ROOT);
        if (!runtime.renderer().hasDesign(id)) {
            unknownDesign(sender, design);
            return 0;
        }
        ZonedDateTime now = ZonedDateTime.now(runtime.config().placeholders().zone());
        int max = runtime.config().playerCount().maxPlayers() >= 0
                ? runtime.config().playerCount().maxPlayers() : Bukkit.getMaxPlayers();
        MotdRenderer.Rendered rendered = runtime.renderer().render(id,
                id.equals(runtime.config().maintenance().design()) ? MotdRenderer.HoverSource.MAINTENANCE
                        : MotdRenderer.HoverSource.DESIGN,
                PingService.values(runtime, now, selection, Bukkit.getOnlinePlayers().size(), max));
        sender.sendMessage(prefixed("<text>Preview of <primary><design></primary></text> <subtle>(centring uses spaces, so chat may look slightly off)</subtle>",
                Placeholder.unparsed("design", id)));
        sender.sendMessage(rendered.motd());
        if (!rendered.hover().isEmpty()) {
            sender.sendMessage(plain("<subtle>── hover ──</subtle>"));
            rendered.hover().forEach(line -> sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize(line)));
        }
        return Command.SINGLE_SUCCESS;
    }

    private int maintenance(CommandContext<CommandSourceStack> ctx, @Nullable Boolean enable) {
        CommandSender sender = ctx.getSource().getSender();
        if (enable == null) {
            boolean scheduled = currentSelection().source() == MotdSelector.Source.SCHEDULED_MAINTENANCE;
            sender.sendMessage(message("maintenance-status", Placeholder.unparsed("state",
                    plugin.state().maintenance() ? "on" : scheduled ? "on (scheduled window)" : "off")));
            return Command.SINGLE_SUCCESS;
        }
        plugin.state().setMaintenance(enable);
        if (enable) {
            boolean blocking = runtime().config().maintenance().blockJoins().enabled();
            sender.sendMessage(message("maintenance-enabled", Placeholder.unparsed("joins", blocking
                    ? "Joins are blocked (except bypass)." : "Players can still join (block-joins is off).")));
        } else {
            sender.sendMessage(message("maintenance-disabled"));
        }
        return Command.SINGLE_SUCCESS;
    }

    private int debug(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        MotdRuntime runtime = runtime();
        PluginConfig config = runtime.config();
        PingService pings = plugin.pingService();
        MotdSelector.Selection selection = currentSelection();
        MotdSelector.ManualChoice choice = plugin.state().override();

        sender.sendMessage(prefixed("<highlight>Debug</highlight>"));
        row(sender, "Plugin", "EepyMOTD " + plugin.getPluginMeta().getVersion() + " · " + Bukkit.getName() + " "
                + Bukkit.getVersion() + " · Java " + Runtime.version());
        row(sender, "Now", selection.design() + " via " + selection.detail()
                + (selection.icon() != null ? " · icon " + selection.icon() : ""));
        row(sender, "Rotation", config.rotation().mode() + " · static " + config.rotation().staticDesign() + " · pool "
                + String.join(", ", config.rotation().pool()) + " · " + config.rotation().schedule().size() + " schedule entries");
        row(sender, "Override", choice.design() != null ? "design " + choice.design()
                : choice.mode() != null ? "mode " + choice.mode() : "none");
        row(sender, "Events", config.events().isEmpty() ? "none enabled" : config.events().stream()
                .map(e -> e.id() + " [" + e.window() + "]").reduce((a, b) -> a + ", " + b).orElse(""));
        row(sender, "Maintenance", (plugin.state().maintenance() ? "on" : "off") + " · "
                + config.maintenance().schedule().size() + " windows · join block "
                + (config.maintenance().blockJoins().enabled() ? "enabled" : "disabled"));
        row(sender, "Icons", config.icons().enabled() ? runtime.icons().names().size() + " loaded ("
                + String.join(", ", runtime.icons().names()) + ") · default " + config.icons().defaultIcon() : "disabled");
        row(sender, "Players", "max " + (config.playerCount().maxPlayers() >= 0 ? config.playerCount().maxPlayers()
                : "server") + " · vanished excluded " + config.playerCount().excludeVanished() + " ("
                + pings.vanishedCount() + " now) · hide counts " + config.playerCount().hideCounts());
        row(sender, "PAPI", plugin.placeholderApiHooked() ? runtime.external().tokens().size()
                + " placeholder(s), refreshed every " + config.placeholders().placeholderApiRefreshSeconds() + "s" : "not installed");
        row(sender, "Pings", pings.pings() + " handled · " + pings.errors() + " errors · avg "
                + String.format(Locale.ROOT, "%.1f", pings.averageMicros()) + "µs · cache "
                + runtime.renderer().cacheHits() + " hits / " + runtime.renderer().cacheMisses() + " misses");
        row(sender, "Last ping", pings.lastPing());
        row(sender, "Listener", "priority " + config.listenerPriority());
        List<String> others = plugin.detectOtherMotdPlugins();
        row(sender, "Conflicts", others.isEmpty() ? "none detected" : String.join(", ", others));
        row(sender, "Config", runtime.warnings().size() + " warning(s)");
        return Command.SINGLE_SUCCESS;
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private MotdRuntime runtime() {
        return plugin.pingService().runtime();
    }

    private MotdSelector.Selection currentSelection() {
        MotdRuntime runtime = runtime();
        ZonedDateTime now = ZonedDateTime.now(runtime.config().placeholders().zone());
        return runtime.selector().select(now.toLocalDateTime(), System.currentTimeMillis(),
                plugin.state().maintenance(), plugin.state().override());
    }

    private void unknownDesign(CommandSender sender, @Nullable String design) {
        sender.sendMessage(message("unknown-design",
                Placeholder.unparsed("design", String.valueOf(design)),
                Placeholder.unparsed("designs", String.join(", ", runtime().renderer().designIds()))));
    }

    private void helpLine(CommandSender sender, @Nullable String permission, String usage, String description) {
        if (permission == null || allowed(sender, permission)) {
            sender.sendMessage(plain("<primary><usage></primary> <line>›</line> <text><description></text>",
                    Placeholder.unparsed("usage", usage), Placeholder.unparsed("description", description)));
        }
    }

    private void row(CommandSender sender, String label, String value) {
        sender.sendMessage(plain("<muted><label></muted> <line>›</line> <text><value></text>",
                Placeholder.unparsed("label", String.format(Locale.ROOT, "%-11s", label)),
                Placeholder.unparsed("value", value)));
    }

    private static Predicate<CommandSourceStack> permission(String node) {
        return source -> allowed(source.getSender(), node);
    }

    private static boolean allowed(CommandSender sender, String node) {
        return sender.hasPermission(node) || sender.hasPermission(ADMIN);
    }

    private Component message(String key, TagResolver... resolvers) {
        return prefixed(runtime().config().messages().get(key), resolvers);
    }

    private Component prefixed(String text, TagResolver... resolvers) {
        return plain(runtime().config().messages().prefix() + text, resolvers);
    }

    private Component plain(String text, TagResolver... resolvers) {
        List<TagResolver> all = new ArrayList<>(Arrays.asList(resolvers));
        runtime().config().palette().forEach((name, color) -> all.add(TagResolver.resolver(name, Tag.styling(color))));
        return miniMessage.deserialize(text, TagResolver.resolver(all));
    }
}
