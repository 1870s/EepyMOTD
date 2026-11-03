package at.eepy.motd.placeholder;

import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.OfflinePlayer;

/** PlaceholderAPI hook. Only instantiated when PlaceholderAPI is enabled. */
public final class PlaceholderApiBridge implements PlaceholderBridge {

    @Override
    public String resolve(String token) {
        // A server list ping has no player, so only player-independent placeholders work.
        return PlaceholderAPI.setPlaceholders((OfflinePlayer) null, token);
    }
}
