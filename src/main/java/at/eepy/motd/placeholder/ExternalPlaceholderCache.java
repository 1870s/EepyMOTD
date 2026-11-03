package at.eepy.motd.placeholder;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Holds PlaceholderAPI results for the tokens used in the config.
 * <p>
 * Pings run on network threads, while most PlaceholderAPI expansions are only
 * safe on the main thread. So values are refreshed on the main thread at a
 * fixed interval and pings read the latest immutable snapshot.
 */
public final class ExternalPlaceholderCache {

    private final List<String> tokens;
    private final @Nullable PlaceholderBridge bridge;
    private final String missingValue;
    private final Logger logger;
    private volatile List<String> values;
    private boolean failureReported;

    public ExternalPlaceholderCache(List<String> tokens, @Nullable PlaceholderBridge bridge, String missingValue,
                                    Logger logger) {
        this.tokens = List.copyOf(tokens);
        this.bridge = bridge;
        this.missingValue = missingValue;
        this.logger = logger;
        this.values = tokens.stream().map(token -> missingValue).toList();
    }

    /** Main thread only. */
    public void refresh() {
        if (bridge == null || tokens.isEmpty()) {
            return;
        }
        List<String> fresh = new ArrayList<>(tokens.size());
        for (String token : tokens) {
            String value;
            try {
                value = bridge.resolve(token);
            } catch (RuntimeException e) {
                if (!failureReported) {
                    failureReported = true;
                    logger.log(Level.WARNING, "PlaceholderAPI failed for " + token, e);
                }
                value = null;
            }
            // Unknown placeholders come back unchanged; show the fallback instead of raw %text%.
            fresh.add(value == null || value.isEmpty() || value.equals(token) ? missingValue : value);
        }
        values = List.copyOf(fresh);
    }

    /** Thread-safe snapshot, indexed like the compiler's token ids. */
    public List<String> values() {
        return values;
    }

    public boolean active() {
        return bridge != null && !tokens.isEmpty();
    }

    public List<String> tokens() {
        return tokens;
    }
}
