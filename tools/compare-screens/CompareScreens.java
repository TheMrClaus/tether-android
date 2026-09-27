// tools/compare-screens (PLAN §5.3): a small image diff + side-by-side montage tool for the parity
// evidence. Zero dependencies — JDK 17 single-file source launch, headless AWT/ImageIO:
//
//   java tools/compare-screens/CompareScreens.java diff    <web.png[@crop]> <android.png[@crop]> [out-diff.png]
//   java tools/compare-screens/CompareScreens.java montage <out.png> <title> <web.png[@crop]> <android.png[@crop]> [<web2@crop> <android2@crop> ...]
//   java tools/compare-screens/CompareScreens.java align   <web.png@search-window> <android.png@crop>
//
// `align` finds where the android crop sits inside the web search window (least mean absolute
// difference, coarse-to-fine) and prints the matching web crop `x,y,w,h`, so a montage pairs the
// same part pixel for pixel without hand-measuring each skin.
//
// A crop is `@x,y,w,h` in the source image's pixels. Both captures are at 2.625 px/dp on a phone
// (web: 412×915 CSS px at DPR 2.625; Android: 412dp at 420dpi), so crops compare 1:1 without
// scaling. When sizes differ the Android crop is scaled to the web crop's size for the diff.
//
// The montage is: [web | android | diff heatmap] per pair, with labels and the pair's statistics.
// The perceptual diff is a REVIEW AID, not a gate (fonts rasterize differently: Chromium/Skia with
// subpixel positioning and its own hinting vs Android's text stack); PLAN §5.3.
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

public class CompareScreens {
    /** Channel difference (0-255) below which a pixel counts as matching. */
    static final int TOLERANCE = 24;

    record Stats(double meanAbs, double changedPct, int width, int height) {
        @Override public String toString() {
            return String.format("%dx%d  mean|Δ|=%.2f/255  pixels over tolerance %d: %.2f%%", width, height, meanAbs, TOLERANCE, changedPct);
        }
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        if (args.length < 1) usage();
        switch (args[0]) {
            case "diff" -> {
                if (args.length < 3) usage();
                BufferedImage a = load(args[1]);
                BufferedImage b = fit(load(args[2]), a.getWidth(), a.getHeight());
                BufferedImage heat = new BufferedImage(a.getWidth(), a.getHeight(), BufferedImage.TYPE_INT_RGB);
                System.out.println(diff(a, b, heat));
                if (args.length > 3) ImageIO.write(heat, "png", new File(args[3]));
            }
            case "montage" -> {
                if (args.length < 5 || (args.length - 3) % 2 != 0) usage();
                List<BufferedImage[]> rows = new ArrayList<>();
                List<String> captions = new ArrayList<>();
                for (int i = 3; i < args.length; i += 2) {
                    BufferedImage web = load(args[i]);
                    BufferedImage android = fit(load(args[i + 1]), web.getWidth(), web.getHeight());
                    BufferedImage heat = new BufferedImage(web.getWidth(), web.getHeight(), BufferedImage.TYPE_INT_RGB);
                    Stats s = diff(web, android, heat);
                    rows.add(new BufferedImage[] {web, android, heat});
                    captions.add(label(args[i]) + "  vs  " + label(args[i + 1]) + "   " + s);
                    System.out.println(captions.get(captions.size() - 1));
                }
                ImageIO.write(montage(args[2], rows, captions), "png", new File(args[1]));
                System.out.println("wrote " + args[1]);
            }
            case "align" -> {
                if (args.length < 3) usage();
                int[] win = cropOf(args[1]);
                BufferedImage web = load(args[1]);
                BufferedImage android = load(args[2]);
                int[] best = align(web, android);
                System.out.println((win[0] + best[0]) + "," + (win[1] + best[1]) + "," + android.getWidth() + "," + android.getHeight()
                    + "  (mean|Δ| " + String.format("%.2f", best[2] / 100.0) + ")");
            }
            default -> usage();
        }
    }

    static int[] cropOf(String spec) {
        int at = spec.lastIndexOf('@');
        if (at < 0) return new int[] {0, 0};
        String[] p = spec.substring(at + 1).split(",");
        return new int[] {Integer.parseInt(p[0]), Integer.parseInt(p[1])};
    }

    /** Best offset of [needle] inside [hay]: {dx, dy, mean|Δ|×100}. Coarse step 4, then refine ±4. */
    static int[] align(BufferedImage hay, BufferedImage needle) {
        int[][] h = grey(hay), n = grey(needle);
        int maxX = hay.getWidth() - needle.getWidth(), maxY = hay.getHeight() - needle.getHeight();
        if (maxX < 0 || maxY < 0) throw new IllegalArgumentException("search window smaller than the crop");
        long bestScore = Long.MAX_VALUE;
        int bx = 0, by = 0;
        for (int y = 0; y <= maxY; y += 4) for (int x = 0; x <= maxX; x += 4) {
            long sc = sad(h, n, x, y, 3, bestScore);
            if (sc < bestScore) { bestScore = sc; bx = x; by = y; }
        }
        bestScore = Long.MAX_VALUE;
        int fx = bx, fy = by;
        for (int y = Math.max(0, by - 4); y <= Math.min(maxY, by + 4); y++) for (int x = Math.max(0, bx - 4); x <= Math.min(maxX, bx + 4); x++) {
            long sc = sad(h, n, x, y, 1, bestScore);
            if (sc < bestScore) { bestScore = sc; fx = x; fy = y; }
        }
        long px = (long) needle.getWidth() * needle.getHeight();
        return new int[] {fx, fy, (int) (bestScore * 100 / px)};
    }

    static long sad(int[][] h, int[][] n, int ox, int oy, int step, long cap) {
        long sum = 0;
        for (int y = 0; y < n.length; y += step) {
            for (int x = 0; x < n[0].length; x += step) sum += Math.abs(h[oy + y][ox + x] - n[y][x]);
            if (sum * step * step > cap) return Long.MAX_VALUE;
        }
        return sum * step * step;
    }

    static int[][] grey(BufferedImage img) {
        int[][] g = new int[img.getHeight()][img.getWidth()];
        for (int y = 0; y < img.getHeight(); y++) for (int x = 0; x < img.getWidth(); x++) {
            int p = img.getRGB(x, y);
            g[y][x] = (((p >> 16) & 255) * 299 + ((p >> 8) & 255) * 587 + (p & 255) * 114) / 1000;
        }
        return g;
    }

    static void usage() {
        System.err.println("usage: diff <a[@x,y,w,h]> <b[@x,y,w,h]> [out.png] | montage <out.png> <title> <web[@crop]> <android[@crop]> ... | align <web@window> <android@crop>");
        System.exit(2);
    }

    static String label(String spec) {
        String path = spec.contains("@") ? spec.substring(0, spec.indexOf('@')) : spec;
        String[] parts = path.split("/");
        return parts.length >= 2 ? parts[parts.length - 2] + "/" + parts[parts.length - 1] : path;
    }

    /** `path` or `path@x,y,w,h`. */
    static BufferedImage load(String spec) throws Exception {
        String path = spec;
        int[] crop = null;
        int at = spec.lastIndexOf('@');
        if (at > 0) {
            path = spec.substring(0, at);
            String[] p = spec.substring(at + 1).split(",");
            crop = new int[] {Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3])};
        }
        BufferedImage img = ImageIO.read(new File(path));
        if (img == null) throw new IllegalArgumentException("not an image: " + path);
        BufferedImage rgb = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_RGB);
        rgb.createGraphics().drawImage(img, 0, 0, null);
        if (crop == null) return rgb;
        int x = Math.max(0, crop[0]), y = Math.max(0, crop[1]);
        int w = Math.min(crop[2], rgb.getWidth() - x), h = Math.min(crop[3], rgb.getHeight() - y);
        return rgb.getSubimage(x, y, w, h);
    }

    static BufferedImage fit(BufferedImage img, int w, int h) {
        if (img.getWidth() == w && img.getHeight() == h) return img;
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.drawImage(img, 0, 0, w, h, null);
        g.dispose();
        return out;
    }

    /** Per-pixel max-channel difference; the heatmap is the web image dimmed, with red over changed pixels. */
    static Stats diff(BufferedImage a, BufferedImage b, BufferedImage heat) {
        long sum = 0;
        long changed = 0;
        int w = a.getWidth(), h = a.getHeight();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int p = a.getRGB(x, y), q = b.getRGB(x, y);
                int d = Math.max(Math.abs(((p >> 16) & 255) - ((q >> 16) & 255)),
                    Math.max(Math.abs(((p >> 8) & 255) - ((q >> 8) & 255)), Math.abs((p & 255) - (q & 255))));
                sum += d;
                int grey = (((p >> 16) & 255) + ((p >> 8) & 255) + (p & 255)) / 3 / 3;
                if (d > TOLERANCE) {
                    changed++;
                    int r = Math.min(255, 96 + d);
                    heat.setRGB(x, y, (r << 16) | (grey << 8) | grey);
                } else {
                    heat.setRGB(x, y, (grey << 16) | (grey << 8) | grey);
                }
            }
        }
        double n = (double) w * h;
        return new Stats(sum / n, 100.0 * changed / n, w, h);
    }

    /** A column is at least wide enough for its heading. */
    static int column(BufferedImage img) {
        return Math.max(img.getWidth(), 230);
    }

    static BufferedImage montage(String title, List<BufferedImage[]> rows, List<String> captions) {
        int pad = 16, head = 44, cap = 28, colHead = 24;
        Font captionFont = new Font(Font.MONOSPACED, Font.PLAIN, 14);
        Graphics2D probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB).createGraphics();
        int width = 0, height = head;
        for (String c : captions) width = Math.max(width, pad * 2 + probe.getFontMetrics(captionFont).stringWidth(c));
        width = Math.max(width, pad * 2 + probe.getFontMetrics(new Font(Font.SANS_SERIF, Font.BOLD, 20)).stringWidth(title));
        for (BufferedImage[] r : rows) {
            width = Math.max(width, pad + (column(r[0]) + pad) * 3);
            height += colHead + r[0].getHeight() + cap + pad;
        }
        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(new Color(0xF4F4F2));
        g.fillRect(0, 0, out.getWidth(), out.getHeight());
        g.setColor(new Color(0x1B1D1C));
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 20));
        g.drawString(title, pad, 30);
        int y = head;
        g.setFont(captionFont);
        for (int i = 0; i < rows.size(); i++) {
            BufferedImage[] r = rows.get(i);
            String[] names = {"web (reference)", "android (Roborazzi)", "diff (red = over tolerance)"};
            for (int c = 0; c < 3; c++) {
                int x = pad + c * (column(r[0]) + pad);
                g.setColor(new Color(0x55585A));
                g.drawString(names[c], x, y + 17);
                g.drawImage(r[c], x, y + colHead, null);
                g.setColor(new Color(0xB0B3B1));
                g.drawRect(x - 1, y + colHead - 1, r[c].getWidth() + 1, r[c].getHeight() + 1);
            }
            y += colHead + r[0].getHeight();
            g.setColor(new Color(0x1B1D1C));
            g.drawString(captions.get(i), pad, y + 20);
            y += cap + pad;
        }
        g.dispose();
        return out;
    }
}
