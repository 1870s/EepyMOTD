package at.eepy.motd.motd;

import at.eepy.motd.config.PluginConfig;
import at.eepy.motd.placeholder.PingValues;
import at.eepy.motd.state.PersistentState;
import com.destroystokyo.paper.event.server.PaperServerListPingEvent;
import com.destroystokyo.paper.event.server.PaperServerListPingEvent.ListedPlayerInfo;
import org.bukkit.event.Event;
import org.bukkit.event.Listener;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.util.CachedServerIcon;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Answers server list pings.
 * <p>
 * Paper fires {@link PaperServerListPingEvent} on a network thread, so this
 * class never calls the Bukkit world/player API. It reads only immutable or
 * volatile snapshots: the current {@link MotdRuntime}, the persisted state and
 * the vanished-player set maintained on the main thread by {@link VanishTracker}.
 * <p>
 * Player counts stay honest: the online number is the real one (optionally
 * without vanished players), hover lines use a zero UUID so clients and tools
 * can tell them apart from real players, and real names are only added on request.
 */
public final class PingService implements Listener, EventExecutor {

    private static final long ERROR_LOG_INTERVAL_NANOS = 60_000_000_000L;

    private final Logger logger;
    private final PersistentState state;
    private volatile MotdRuntime runtime;
    private volatile Set<UUID> vanished = Set.of();
    private volatile Set<UUID> online = Set.of();

    private final AtomicLong pings = new AtomicLong();
    private final AtomicLong errors = new AtomicLong();
    private final LongAdder handlingNanos = new LongAdder();
    private volatile long lastErrorLog;
    private volatile String lastPing = "none yet";

    public PingService(Logger logger, PersistentState state, MotdRuntime runtime) {
        this.logger = logger;
        this.state = state;
        this.runtime = runtime;
    }

    public void apply(MotdRuntime newRuntime) {
        this.runtime = newRuntime;
    }

    public MotdRuntime runtime() {
        return runtime;
    }

    void setPlayers(Set<UUID> onlinePlayers, Set<UUID> vanishedPlayers) {
        this.online = onlinePlayers;
        this.vanished = vanishedPlayers;
    }

    @Override
    public void execute(Listener listener, Event event) {
        if (event instanceof PaperServerListPingEvent ping) {
            handle(ping);
        }
    }

    private void handle(PaperServerListPingEvent event) {
        long started = System.nanoTime();
        try {
            MotdRuntime current = runtime;
            PluginConfig config = current.config();
            ZonedDateTime now = ZonedDateTime.now(config.placeholders().zone());
            MotdSelector.Selection selection = current.selector()
                    .select(now.toLocalDateTime(), System.currentTimeMillis(), state.maintenance(), state.override());

            Set<UUID> hidden = config.playerCount().excludeVanished() ? vanished : Set.of();
            int online = Math.max(0, event.getNumPlayers() - hidden.size());
            int max = config.playerCount().maxPlayers() >= 0 ? config.playerCount().maxPlayers() : event.getMaxPlayers();

            MotdRenderer.HoverSource hoverSource = !config.hover().enabled() ? MotdRenderer.HoverSource.NONE
                    : selection.maintenance() ? MotdRenderer.HoverSource.MAINTENANCE : MotdRenderer.HoverSource.DESIGN;
            MotdRenderer.Rendered rendered = current.renderer()
                    .render(selection.design(), hoverSource, values(current, now, selection, online, max));

            event.motd(rendered.motd());
            event.setNumPlayers(online);
            event.setMaxPlayers(max);
            if (config.playerCount().hideCounts()) {
                event.setHidePlayers(true);
            } else if (config.hover().enabled()) {
                applyHover(event, config.hover(), rendered.hover(), this.online, this.vanished);
            }

            CachedServerIcon icon = icon(current, selection);
            if (icon != null) {
                event.setServerIcon(icon);
            }
            if (selection.maintenance() && !current.maintenanceVersion().isEmpty()) {
                event.setVersion(current.maintenanceVersion());
                event.setProtocolVersion(-1); // makes the client show the label instead of ping bars
            }

            pings.incrementAndGet();
            lastPing = selection.design() + " via " + selection.detail() + " · protocol "
                    + event.getClient().getProtocolVersion() + " · thread " + Thread.currentThread().getName();
        } catch (RuntimeException e) {
            // Leave the event untouched: the client then sees the vanilla MOTD instead of nothing.
            errors.incrementAndGet();
            long now = System.nanoTime();
            if (now - lastErrorLog > ERROR_LOG_INTERVAL_NANOS) {
                lastErrorLog = now;
                logger.log(Level.SEVERE, "Could not build the server list response", e);
            }
        } finally {
            handlingNanos.add(System.nanoTime() - started);
        }
    }

    private static void applyHover(PaperServerListPingEvent event, PluginConfig.Hover hover, List<String> lines,
                                   Set<UUID> onlineNow, Set<UUID> vanishedNow) {
        List<ListedPlayerInfo> listed = event.getListedPlayers();
        List<ListedPlayerInfo> realPlayers = new ArrayList<>();
        if (hover.showOnlinePlayers()) {
            for (ListedPlayerInfo player : listed) {
                if (realPlayers.size() >= hover.maxOnlinePlayers()) {
                    break;
                }
                // Paper caches its sample for a few seconds: only list players who are still
                // online, and never vanished ones (regardless of exclude-vanished).
                if (onlineNow.contains(player.id()) && !vanishedNow.contains(player.id())) {
                    realPlayers.add(player);
                }
            }
        }
        listed.clear();
        for (int i = 0; i < lines.size(); i++) {
            listed.add(new ListedPlayerInfo(lines.get(i), new UUID(0L, i)));
        }
        listed.addAll(realPlayers);
    }

    private static @Nullable CachedServerIcon icon(MotdRuntime runtime, MotdSelector.Selection selection) {
        PluginConfig.Icons icons = runtime.config().icons();
        if (!icons.enabled()) {
            return null;
        }
        CachedServerIcon icon = runtime.icons().get(selection.icon());
        if (icon == null && !PluginConfig.Icons.SERVER_ICON.equals(icons.defaultIcon())) {
            icon = runtime.icons().get(icons.defaultIcon());
        }
        return icon;
    }

    /** Builds the dynamic values; also used for previews from commands. */
    public static PingValues values(MotdRuntime runtime, ZonedDateTime now, MotdSelector.Selection selection,
                                    int online, int max) {
        PluginConfig.Placeholders placeholders = runtime.config().placeholders();
        return new PingValues(
                online,
                max,
                placeholders.timeFormat().format(now),
                placeholders.dateFormat().format(now),
                now.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH),
                Integer.toString(now.getYear()),
                selection.event() == null ? "" : selection.event().name(),
                formatDuration(selection.eventRemaining(), ""),
                formatDuration(selection.maintenanceEnds(), "soon"),
                runtime.external().values());
    }

    /** "2d 5h", "3h 20m", "12m" or "<1m"; minute precision keeps the render cache effective. */
    static String formatDuration(@Nullable Duration duration, String fallback) {
        if (duration == null) {
            return fallback;
        }
        long minutes = duration.toMinutes();
        if (minutes < 1) {
            return "<1m";
        }
        long days = minutes / 1440;
        long hours = minutes % 1440 / 60;
        long rest = minutes % 60;
        if (days > 0) {
            return days + "d " + hours + "h";
        }
        return hours > 0 ? hours + "h " + rest + "m" : rest + "m";
    }

    public long pings() {
        return pings.get();
    }

    public long errors() {
        return errors.get();
    }

    public double averageMicros() {
        long count = pings.get() + errors.get();
        return count == 0 ? 0 : handlingNanos.sum() / 1000.0 / count;
    }

    public String lastPing() {
        return lastPing;
    }

    public int vanishedCount() {
        return vanished.size();
    }
}
