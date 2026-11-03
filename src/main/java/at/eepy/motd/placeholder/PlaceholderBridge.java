package at.eepy.motd.placeholder;

/**
 * Resolves server-wide PlaceholderAPI placeholders. Implemented by the
 * PlaceholderAPI hook; kept as an interface so nothing else references
 * PlaceholderAPI classes when it is not installed.
 */
public interface PlaceholderBridge {

    /** Called on the main thread only. {@code token} includes the percent signs. */
    String resolve(String token);
}
