package cc.ataglace.molebutter.imaging.internal.policy;

import java.awt.image.BufferedImage;
import java.io.IOException;
import javax.imageio.ImageIO;

/** Bundled, immutable photo cutout. Alpha bounds, not PNG canvas padding, define its physical body. */
public final class SizeGuideComparisonAssets {
    private SizeGuideComparisonAssets() { }
    private static final java.util.Map<String, BufferedImage> IMAGES = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Set<String> IDS = java.util.Set.of("samdasoo-500", "iphone-16", "galaxy-s26", "ipad-air-11-m4", "ipad-air-13-m4",
            "macbook-air-13-m5", "macbook-air-15-m5", "macbook-pro-16-m5");
    public static BufferedImage image(String id) {
        if (!IDS.contains(id)) throw new IllegalArgumentException("Unknown comparison asset");
        return IMAGES.computeIfAbsent(id, SizeGuideComparisonAssets::loadImage);
    }
    public static BufferedImage bottle() { return image("samdasoo-500"); }
    public static double bottleAspectRatio() { return bottle().getWidth() / (double) bottle().getHeight(); }

    private static BufferedImage loadImage(String id) {
        try (var input = SizeGuideComparisonAssets.class.getResourceAsStream("/imaging/comparisons/" + id + "-v2.png")) {
            if (input == null) throw new IOException("Missing comparison asset");
            BufferedImage image = ImageIO.read(input);
            if (image == null || !image.getColorModel().hasAlpha()) throw new IOException("Invalid comparison alpha");
            // Ignore detached extraction specks. Preserve the full cap-to-base silhouette of the largest body.
            int width = image.getWidth(), height = image.getHeight();
            boolean[] seen = new boolean[width * height];
            int[] queue = new int[seen.length];
            int best = 0, left = 0, top = 0, right = 0, bottom = 0;
            for (int index = 0; index < seen.length; index++) {
                if (seen[index] || (image.getRGB(index % width, index / width) >>> 24) < 32) continue;
                int head = 0, tail = 1, minX = width, minY = height, maxX = 0, maxY = 0;
                queue[0] = index; seen[index] = true;
                while (head < tail) {
                    int pixel = queue[head++], x = pixel % width, y = pixel / width;
                    minX = Math.min(minX, x); maxX = Math.max(maxX, x);
                    minY = Math.min(minY, y); maxY = Math.max(maxY, y);
                    for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
                        int nx = x + dx, ny = y + dy;
                        if (nx < 0 || nx >= width || ny < 0 || ny >= height) continue;
                        int neighbor = ny * width + nx;
                        if (!seen[neighbor] && (image.getRGB(nx, ny) >>> 24) >= 32) {
                            seen[neighbor] = true; queue[tail++] = neighbor;
                        }
                    }
                }
                if (tail > best) { best = tail; left = minX; top = minY; right = maxX; bottom = maxY; }
            }
            if (best < 100) throw new IOException("Empty comparison body");
            return image.getSubimage(left, top, right - left + 1, bottom - top + 1);
        } catch (IOException invalid) { throw new IllegalStateException("비교 대상 이미지 리소스를 읽지 못했습니다.", invalid); }
    }
}
