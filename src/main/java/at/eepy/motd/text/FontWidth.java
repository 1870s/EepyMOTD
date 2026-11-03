package at.eepy.motd.text;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.TextDecoration;

/**
 * Estimates how many pixels text takes in the vanilla font, so MOTD lines can
 * be centred and over-long lines reported.
 * <p>
 * Widths are glyph advances of the default font at GUI scale 1 (glyph width
 * plus one pixel spacing); bold adds one pixel per character. Characters
 * outside ASCII come from the vanilla fallback fonts and are approximated.
 * Resource packs or "force Unicode font" change the result, so treat it as a
 * close estimate rather than an exact measurement.
 */
public final class FontWidth {

    /** Width of a space; used to turn pixel gaps into padding. */
    public static final int SPACE = 4;

    private FontWidth() {
    }

    public static int of(int codePoint) {
        return switch (codePoint) {
            case ' ' -> 4;
            case '!', '\'', ',', '.', ':', ';', 'i', '|' -> 2;
            case '`', 'l' -> 3;
            case '"', '(', ')', '*', 'I', '[', ']', 't', '{', '}' -> 4;
            case '<', '>', 'f', 'k' -> 5;
            case '@', '~' -> 7;
            case '·' -> 2;
            case '•', '›', '‹' -> 4;
            case '│', '┃' -> 2;
            case '✦', '✧', '❄', '☀', '☾', '☽', '♥', '★', '☆' -> 8;
            default -> codePoint < 128 ? 6 : codePoint <= 0x17F ? 6 : 8;
        };
    }

    /** Pixel width of a component tree, respecting inherited bold. */
    public static int of(Component component) {
        return width(component, false);
    }

    private static int width(Component component, boolean inheritedBold) {
        TextDecoration.State state = component.decoration(TextDecoration.BOLD);
        boolean bold = state == TextDecoration.State.NOT_SET ? inheritedBold : state == TextDecoration.State.TRUE;
        int total = 0;
        if (component instanceof TextComponent text) {
            String content = text.content();
            for (int i = 0; i < content.length(); ) {
                int codePoint = content.codePointAt(i);
                total += of(codePoint) + (bold ? 1 : 0);
                i += Character.charCount(codePoint);
            }
        }
        for (Component child : component.children()) {
            total += width(child, bold);
        }
        return total;
    }

    /** Spaces needed in front of a line of {@code width} pixels to centre it in {@code available}. */
    public static int centerPadding(int width, int available) {
        return Math.max(0, Math.round((available - width) / 2f / SPACE));
    }
}
