import java.awt.image.BufferedImage;
import java.nio.file.*;
import javax.imageio.ImageIO;
import java.util.stream.Stream;

/** Read-only pixel comparison; fixture images are never rewritten. */
public class CompareGuideRenders {
  public static void main(String[] args) throws Exception {
    StringBuilder out = new StringBuilder("[");
    try (Stream<Path> files = Files.list(Paths.get(args[1]))) {
      for (Path path : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".png")).sorted()::iterator) {
        BufferedImage a = ImageIO.read(Paths.get(args[0]).resolve(path.getFileName()).toFile());
        BufferedImage b = ImageIO.read(path.toFile());
        if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) throw new IllegalStateException("Image dimensions changed");
        int w = a.getWidth(), h = a.getHeight();
        int[] old = a.getRGB(0, 0, w, h, null, 0, w), current = b.getRGB(0, 0, w, h, null, 0, w);
        long changed = 0, difference = 0;
        int minX = w, minY = h, maxX = -1, maxY = -1;
        for (int i = 0; i < old.length; i++) {
          if (old[i] != current[i]) {
            changed++; int x = i % w, y = i / w;
            minX = Math.min(minX, x); maxX = Math.max(maxX, x); minY = Math.min(minY, y); maxY = Math.max(maxY, y);
          }
          for (int shift = 0; shift <= 16; shift += 8) difference += Math.abs(((old[i] >>> shift) & 255) - ((current[i] >>> shift) & 255));
        }
        if (out.length() > 1) out.append(',');
        out.append("{\"file\":\"").append(path.getFileName()).append("\",\"changed_pixels\":").append(changed)
          .append(",\"mean_absolute_rgb\":").append((double) difference / (w * h * 3L))
          .append(",\"bounds\":[").append(minX).append(',').append(minY).append(',').append(maxX).append(',').append(maxY).append("]}");
      }
    }
    out.append(']');
    Files.writeString(Paths.get(args[2]), out.toString());
    System.out.println(out);
  }
}
