package at.eepy.motd.icon;

import org.bukkit.Server;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.CachedServerIcon;
import org.jspecify.annotations.Nullable;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.*;

/**
 * Loads server icons from {@code plugins/EepyMOTD/icons/*.png} once per reload.
 * <p>
 * Every file is validated before it is decoded (size on disk, PNG header, pixel
 * dimensions) so a broken or huge file can not stall the server. Images that are
 * not 64×64 are scaled down with bicubic filtering when {@code icons.resize} is
 * on. The resulting {@link CachedServerIcon}s are immutable and handed to ping
 * events as-is; pings never touch the disk.
 */
public final class IconManager {

    public static final List<String> BUNDLED = List.of("eepy", "eepy-maintenance", "eepy-winter", "eepy-halloween");
    private static final int SIZE = 64;
    private static final long MAX_FILE_BYTES = 2L * 1024 * 1024;
    private static final int MAX_SOURCE_PIXELS = 2048;

    private final Map<String, CachedServerIcon> icons;
    private final List<String> warnings;

    private IconManager(Map<String, CachedServerIcon> icons, List<String> warnings) {
        this.icons = icons;
        this.warnings = warnings;
    }

    public static IconManager empty() {
        return new IconManager(Map.of(), List.of());
    }

    /** Copies the bundled icons on first start, then loads every PNG in the folder. */
    public static IconManager load(Plugin plugin, boolean resize) {
        File folder = new File(plugin.getDataFolder(), "icons");
        List<String> warnings = new ArrayList<>();
        if (!folder.isDirectory()) {
            if (!folder.mkdirs()) {
                warnings.add("icons: could not create " + folder);
                return new IconManager(Map.of(), List.copyOf(warnings));
            }
            for (String name : BUNDLED) {
                plugin.saveResource("icons/" + name + ".png", false);
            }
        }

        File[] files = folder.listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".png"));
        Map<String, CachedServerIcon> icons = new TreeMap<>();
        if (files != null) {
            Arrays.sort(files);
            for (File file : files) {
                String name = file.getName().substring(0, file.getName().length() - 4).toLowerCase(Locale.ROOT);
                try {
                    icons.put(name, load(plugin.getServer(), file, resize));
                } catch (IconException e) {
                    warnings.add("icons/" + file.getName() + ": " + e.getMessage());
                }
            }
        }
        return new IconManager(Collections.unmodifiableMap(icons), List.copyOf(warnings));
    }

    static CachedServerIcon load(Server server, File file, boolean resize) throws IconException {
        try {
            long bytes = Files.size(file.toPath());
            if (bytes > MAX_FILE_BYTES) {
                throw new IconException("file is larger than 2 MB, skipped");
            }
            BufferedImage image = read(file);
            if (image.getWidth() != SIZE || image.getHeight() != SIZE) {
                if (!resize) {
                    throw new IconException("is " + image.getWidth() + "×" + image.getHeight()
                            + ", must be 64×64 (or enable icons.resize)");
                }
                image = scale(image);
            }
            return server.loadServerIcon(image);
        } catch (IconException e) {
            throw e;
        } catch (Exception e) {
            throw new IconException("could not be loaded (" + e.getMessage() + ")");
        }
    }

    /** Reads the header first, so oversized images are rejected before decoding. */
    private static BufferedImage read(File file) throws IOException, IconException {
        try (InputStream raw = Files.newInputStream(file.toPath());
             ImageInputStream input = ImageIO.createImageInputStream(raw)) {
            Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName("png");
            if (input == null || !readers.hasNext()) {
                throw new IconException("PNG support is not available");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width > MAX_SOURCE_PIXELS || height > MAX_SOURCE_PIXELS) {
                    throw new IconException("is " + width + "×" + height + ", larger than " + MAX_SOURCE_PIXELS + " px");
                }
                return reader.read(0);
            } catch (IOException e) {
                throw new IconException("is not a valid PNG image");
            } finally {
                reader.dispose();
            }
        }
    }

    /** Downscales in halving steps for a smooth result, then to exactly 64×64. */
    private static BufferedImage scale(BufferedImage source) {
        BufferedImage current = source;
        int width = source.getWidth();
        int height = source.getHeight();
        do {
            width = Math.max(SIZE, width / 2);
            height = Math.max(SIZE, height / 2);
            if (current.getWidth() <= SIZE * 2 && current.getHeight() <= SIZE * 2) {
                width = SIZE;
                height = SIZE;
            }
            BufferedImage next = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            var graphics = next.createGraphics();
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.drawImage(current, 0, 0, width, height, null);
            graphics.dispose();
            current = next;
        } while (width != SIZE || height != SIZE);
        return current;
    }

    public @Nullable CachedServerIcon get(@Nullable String name) {
        return name == null ? null : icons.get(name.toLowerCase(Locale.ROOT));
    }

    public Set<String> names() {
        return icons.keySet();
    }

    public List<String> warnings() {
        return warnings;
    }

    static final class IconException extends Exception {
        IconException(String message) {
            super(message);
        }
    }
}
