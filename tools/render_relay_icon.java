// Render icon.jpg into the app's launcher icons and the F-Droid fastlane icon.
// Single-file JDK tool: no third-party dependencies.
//
//   java tools/render_relay_icon.java <repo-root>
//
// Source artwork: icon.jpg (1024x1024, "5G-PROXY-COMPANION", owned by the
// project). It already has its own rounded frame and wordmark, so the foreground
// layer stays transparent and the artwork lives in the background layer.
//
// IMPORTANT: adaptive-icon launchers zoom the 108dp canvas and only show the
// central ~72dp. Putting the full-bleed artwork in the background crops the frame
// and the wordmark (verified on a real Samsung One UI launcher). The background
// therefore pads the artwork down into the 66dp safe zone on a white canvas.
//
// Outputs:
//   app/src/main/res/mipmap-{m,h,xh,xxh,xxxh}dpi/ic_launcher.png  (legacy fallback)
//   app/src/main/res/mipmap-xxxhdpi/ic_launcher_background.png     (108dp @4x, padded)
//   fastlane/metadata/android/{en-US,zh-TW}/images/icon.png        (512x512)

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import javax.imageio.ImageIO;

public class render_relay_icon {

    /** Progressive downscale keeps the small wordmark from turning to mush. */
    private static BufferedImage resize(BufferedImage src, int size) {
        BufferedImage cur = src;
        int w = Math.min(src.getWidth(), src.getHeight());
        while (w / 2 >= size) {
            w /= 2;
            cur = scale(cur, w);
        }
        return scale(cur, size);
    }

    private static BufferedImage scale(BufferedImage src, int size) {
        BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING,
                RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);
        g.drawImage(src, 0, 0, size, size, null);
        g.dispose();
        return out;
    }

    /**
     * 108dp canvas at 4x = 432px. The launcher only guarantees the central 66dp
     * (264px) is never clipped, so the artwork is centred at that size with a
     * white margin. The mask then crops white padding, not the frame/wordmark.
     */
    private static BufferedImage safeZoneBackground(BufferedImage src) {
        int canvas = 432, fit = 264;
        BufferedImage art = resize(src, fit);
        BufferedImage out = new BufferedImage(canvas, canvas, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setColor(java.awt.Color.WHITE);
        g.fillRect(0, 0, canvas, canvas);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.drawImage(art, (canvas - fit) / 2, (canvas - fit) / 2, null);
        g.dispose();
        return out;
    }

    private static void save(BufferedImage img, Path out) throws Exception {
        Files.createDirectories(out.getParent());
        if (!ImageIO.write(img, "png", out.toFile())) {
            throw new IllegalStateException("no PNG writer for " + out);
        }
        System.out.println("wrote " + out);
    }

    public static void main(String[] args) throws Exception {
        Path root = Paths.get(args.length > 0 ? args[0] : ".").toAbsolutePath();
        Path srcPath = root.resolve("icon.jpg");
        BufferedImage src = ImageIO.read(srcPath.toFile());
        if (src == null) throw new IllegalStateException("cannot decode " + srcPath);
        System.out.println("source " + src.getWidth() + "x" + src.getHeight() + " " + srcPath);

        String[] densities = {"mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi"};
        int[] sizes = {48, 72, 96, 144, 192};
        for (int i = 0; i < densities.length; i++) {
            save(resize(src, sizes[i]),
                    root.resolve("app/src/main/res/mipmap-" + densities[i] + "/ic_launcher.png"));
        }

        // Adaptive icon background layer: 108dp canvas at 4x (xxxhdpi), so the
        // bitmap's intrinsic size really is 108dp instead of being nodpi-scaled.
        save(safeZoneBackground(src),
                root.resolve("app/src/main/res/mipmap-xxxhdpi/ic_launcher_background.png"));

        // F-Droid reads <subdir>/fastlane/metadata/android/<locale>/images/icon.png.
        BufferedImage store = resize(src, 512);
        for (String locale : new String[]{"en-US", "zh-TW"}) {
            save(store, root.resolve("fastlane/metadata/android/" + locale
                    + "/images/icon.png"));
        }
    }
}
