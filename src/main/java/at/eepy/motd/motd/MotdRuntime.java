package at.eepy.motd.motd;

import at.eepy.motd.config.PluginConfig;
import at.eepy.motd.icon.IconManager;
import at.eepy.motd.placeholder.ExternalPlaceholderCache;

import java.util.List;

/**
 * Everything derived from one configuration, swapped atomically on reload so a
 * ping never mixes old and new settings.
 *
 * @param maintenanceVersion legacy-formatted {@code maintenance.version-label}, empty if unused
 * @param warnings           all warnings found while building this runtime
 */
public record MotdRuntime(PluginConfig config, MotdRenderer renderer, MotdSelector selector, IconManager icons,
                          ExternalPlaceholderCache external, String maintenanceVersion, List<String> warnings) {
}
