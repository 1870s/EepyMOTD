package at.eepy.motd.motd;

import at.eepy.motd.config.PluginConfig;
import at.eepy.motd.state.PersistentState;
import com.destroystokyo.paper.profile.PlayerProfile;
import io.papermc.paper.connection.PlayerConfigurationConnection;
import io.papermc.paper.connection.PlayerConnection;
import io.papermc.paper.connection.PlayerLoginConnection;
import io.papermc.paper.event.connection.PlayerConnectionValidateLoginEvent;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.jspecify.annotations.Nullable;

import java.time.ZonedDateTime;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * Optional, separately enabled join block during maintenance
 * ({@code maintenance.block-joins.enabled}). The maintenance MOTD alone never
 * stops anyone from joining.
 * <p>
 * Runs before the player exists, so permissions can not be checked yet;
 * operators and the configured names/UUIDs may always join.
 */
public final class JoinGuard implements Listener {

    private final Server server;
    private final PersistentState state;
    private final Supplier<MotdRuntime> runtime;

    public JoinGuard(Server server, PersistentState state, Supplier<MotdRuntime> runtime) {
        this.server = server;
        this.state = state;
        this.runtime = runtime;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onValidateLogin(PlayerConnectionValidateLoginEvent event) {
        MotdRuntime current = runtime.get();
        PluginConfig config = current.config();
        PluginConfig.BlockJoins blockJoins = config.maintenance().blockJoins();
        if (!blockJoins.enabled() || !event.isAllowed()) {
            return;
        }
        ZonedDateTime now = ZonedDateTime.now(config.placeholders().zone());
        boolean scheduled = config.maintenance().activeWindow(now.toLocalDateTime()) != null;
        if (!state.maintenance() && !scheduled) {
            return;
        }
        PlayerProfile profile = profile(event.getConnection());
        if (profile == null || mayBypass(profile, blockJoins)) {
            return;
        }
        MotdSelector.Selection selection = current.selector()
                .select(now.toLocalDateTime(), System.currentTimeMillis(), state.maintenance(), state.override());
        event.kickMessage(current.renderer().renderText(blockJoins.kickMessage(),
                PingService.values(current, now, selection, server.getOnlinePlayers().size(), server.getMaxPlayers())));
    }

    private boolean mayBypass(PlayerProfile profile, PluginConfig.BlockJoins blockJoins) {
        if (profile.getId() != null) {
            if (blockJoins.bypassPlayers().contains(profile.getId().toString().toLowerCase(Locale.ROOT))) {
                return true;
            }
            if (blockJoins.bypassOps()) {
                OfflinePlayer player = server.getOfflinePlayer(profile.getId());
                if (player.isOp()) {
                    return true;
                }
            }
        }
        return profile.getName() != null && blockJoins.bypassPlayers().contains(profile.getName().toLowerCase(Locale.ROOT));
    }

    private static @Nullable PlayerProfile profile(PlayerConnection connection) {
        if (connection instanceof PlayerLoginConnection login) {
            return login.getAuthenticatedProfile();
        }
        if (connection instanceof PlayerConfigurationConnection configuration) {
            return configuration.getProfile();
        }
        return null;
    }
}
