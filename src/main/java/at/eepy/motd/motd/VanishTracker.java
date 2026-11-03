package at.eepy.motd.motd;

import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.metadata.MetadataValue;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Samples, on the main thread, which players are online and which of them are
 * vanished, and hands both to the {@link PingService} as immutable sets. Pings
 * run on network threads and must not read player data themselves.
 * <p>
 * The online set also filters Paper's player sample, which the server caches
 * for a few seconds and may still contain players who just left.
 */
public final class VanishTracker implements Runnable {

    private final Server server;
    private final PingService pings;

    public VanishTracker(Server server, PingService pings) {
        this.server = server;
        this.pings = pings;
    }

    @Override
    public void run() {
        Set<UUID> online = new HashSet<>();
        Set<UUID> vanished = new HashSet<>();
        for (Player player : server.getOnlinePlayers()) {
            online.add(player.getUniqueId());
            if (isVanished(player)) {
                vanished.add(player.getUniqueId());
            }
        }
        pings.setPlayers(Set.copyOf(online), Set.copyOf(vanished));
    }

    /**
     * The {@code vanished} metadata is the de facto standard set by vanish plugins
     * (SuperVanish, PremiumVanish, EssentialsX, CMI). The metadata API is deprecated
     * in Paper but those plugins have no replacement, so it is read here on purpose.
     */
    @SuppressWarnings("deprecation")
    static boolean isVanished(Player player) {
        for (MetadataValue value : player.getMetadata("vanished")) {
            if (value.asBoolean()) {
                return true;
            }
        }
        return false;
    }
}
