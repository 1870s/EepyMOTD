package at.eepy.motd.motd;

import at.eepy.motd.config.PluginConfig;
import at.eepy.motd.config.PluginConfig.EventDefinition;
import at.eepy.motd.config.PluginConfig.Mode;
import at.eepy.motd.schedule.DateWindow;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Decides which design a ping shows. Precedence, highest first:
 * <ol>
 *   <li>maintenance (toggled by command or inside a scheduled window),</li>
 *   <li>the highest-priority active event,</li>
 *   <li>a design or mode chosen with {@code /eepymotd set|random|mode},</li>
 *   <li>the rotation mode from config.yml.</li>
 * </ol>
 * Pure computation on immutable config plus an atomic counter, so it is safe on
 * any thread and costs only a few comparisons per ping.
 */
public final class MotdSelector {

    public enum Source { MAINTENANCE, SCHEDULED_MAINTENANCE, EVENT, OVERRIDE, ROTATION }

    /**
     * @param icon             icon requested by maintenance, event or design; {@code null} = default icon
     * @param eventRemaining   time left of {@code event}
     * @param maintenanceEnds  time left of a scheduled maintenance window
     */
    public record Selection(String design, Source source, @Nullable String icon, @Nullable EventDefinition event,
                            @Nullable Duration eventRemaining, @Nullable Duration maintenanceEnds, String detail) {

        public boolean maintenance() {
            return source == Source.MAINTENANCE || source == Source.SCHEDULED_MAINTENANCE;
        }
    }

    /** Manual choices from commands, persisted in data.yml. */
    public record ManualChoice(@Nullable String design, @Nullable Mode mode) {
        public static final ManualChoice NONE = new ManualChoice(null, null);
    }

    private final PluginConfig config;
    private final MotdRenderer renderer;
    private final AtomicLong sequence = new AtomicLong();

    public MotdSelector(PluginConfig config, MotdRenderer renderer) {
        this.config = config;
        this.renderer = renderer;
    }

    public Selection select(LocalDateTime now, long nowMillis, boolean manualMaintenance, ManualChoice override) {
        PluginConfig.Maintenance maintenance = config.maintenance();
        DateWindow window = maintenance.activeWindow(now);
        if (manualMaintenance || window != null) {
            String design = maintenance.design();
            return new Selection(design, manualMaintenance ? Source.MAINTENANCE : Source.SCHEDULED_MAINTENANCE,
                    maintenance.icon() != null ? maintenance.icon() : designIcon(design), null, null,
                    window == null ? null : window.remaining(now),
                    manualMaintenance ? "maintenance (command)" : "maintenance window " + window);
        }

        for (EventDefinition event : config.events()) {
            Duration remaining = event.window().remaining(now);
            if (remaining != null) {
                String design = pick(event.designs(), nowMillis, 0);
                return new Selection(design, Source.EVENT,
                        event.icon() != null ? event.icon() : designIcon(design), event, remaining, null,
                        "event " + event.id() + " (" + event.window() + ")");
            }
        }

        if (override.design() != null && renderer.hasDesign(override.design())) {
            return new Selection(override.design(), Source.OVERRIDE, designIcon(override.design()), null, null, null,
                    "set by command");
        }

        PluginConfig.Rotation rotation = config.rotation();
        Mode mode = override.mode() != null ? override.mode() : rotation.mode();
        String design = switch (mode) {
            case STATIC -> rotation.staticDesign();
            case RANDOM -> rotation.pool().get(ThreadLocalRandom.current().nextInt(rotation.pool().size()));
            case SEQUENTIAL -> pick(rotation.pool(), nowMillis, rotation.sequentialIntervalMillis());
            case SCHEDULED -> scheduled(now);
        };
        return new Selection(design, override.mode() != null ? Source.OVERRIDE : Source.ROTATION, designIcon(design),
                null, null, null, "mode " + mode + (override.mode() != null ? " (set by command)" : ""));
    }

    private String scheduled(LocalDateTime now) {
        for (PluginConfig.ScheduleEntry entry : config.rotation().schedule()) {
            if (entry.window().contains(now)) {
                return entry.design();
            }
        }
        return config.rotation().staticDesign();
    }

    /** Sequential pick: per ping when {@code intervalMillis} is 0, otherwise by wall clock. */
    private String pick(List<String> designs, long nowMillis, long intervalMillis) {
        if (designs.size() == 1) {
            return designs.getFirst();
        }
        long index = intervalMillis > 0 ? nowMillis / intervalMillis : sequence.getAndIncrement();
        return designs.get((int) Math.floorMod(index, (long) designs.size()));
    }

    private @Nullable String designIcon(String design) {
        MotdRenderer.CompiledDesign compiled = renderer.design(design);
        return compiled == null ? null : compiled.icon();
    }
}
