package at.eepy.motd.text;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts classic colour codes ({@code &a}, {@code &l}, {@code &#RRGGBB},
 * {@code &x&R&R&G&G&B&B}, also with {@code §}) into MiniMessage tags, so both
 * styles can be mixed in one config.
 * <p>
 * Like in vanilla, a colour code resets earlier decorations; it is therefore
 * translated to {@code <reset><colour>}.
 * <p>
 * Single {@code &} codes must be lower case ({@code &d}), so ordinary text such
 * as "R&D" stays untouched; {@code §} codes are accepted in any case.
 */
public final class LegacyConverter {

    private static final Pattern CODE = Pattern.compile(
            "[&§](?:#([0-9a-fA-F]{6})|x[&§]([0-9a-fA-F])[&§]([0-9a-fA-F])[&§]([0-9a-fA-F])[&§]([0-9a-fA-F])"
                    + "[&§]([0-9a-fA-F])[&§]([0-9a-fA-F])|((?<=&)[0-9a-fk-or]|(?<=§)[0-9a-fA-Fk-oK-OrR]))");

    private static final Map<Character, String> CODES = Map.ofEntries(
            Map.entry('0', "black"), Map.entry('1', "dark_blue"), Map.entry('2', "dark_green"),
            Map.entry('3', "dark_aqua"), Map.entry('4', "dark_red"), Map.entry('5', "dark_purple"),
            Map.entry('6', "gold"), Map.entry('7', "gray"), Map.entry('8', "dark_gray"), Map.entry('9', "blue"),
            Map.entry('a', "green"), Map.entry('b', "aqua"), Map.entry('c', "red"), Map.entry('d', "light_purple"),
            Map.entry('e', "yellow"), Map.entry('f', "white"), Map.entry('k', "obfuscated"), Map.entry('l', "bold"),
            Map.entry('m', "strikethrough"), Map.entry('n', "underlined"), Map.entry('o', "italic"),
            Map.entry('r', "reset"));

    private LegacyConverter() {
    }

    public static boolean containsCodes(String text) {
        return CODE.matcher(text).find();
    }

    public static String toMiniMessage(String text) {
        Matcher matcher = CODE.matcher(text);
        if (!matcher.find()) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length() + 32);
        do {
            String replacement;
            if (matcher.group(1) != null) {
                replacement = "<reset><#" + matcher.group(1) + ">";
            } else if (matcher.group(2) != null) {
                StringBuilder hex = new StringBuilder(6);
                for (int i = 2; i <= 7; i++) {
                    hex.append(matcher.group(i));
                }
                replacement = "<reset><#" + hex + ">";
            } else {
                char code = Character.toLowerCase(matcher.group(8).charAt(0));
                String tag = CODES.get(code);
                boolean isColor = (code >= '0' && code <= '9') || (code >= 'a' && code <= 'f');
                replacement = isColor ? "<reset><" + tag + ">" : "<" + tag + ">";
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement.toLowerCase(Locale.ROOT)));
        } while (matcher.find());
        matcher.appendTail(out);
        return out.toString();
    }
}
