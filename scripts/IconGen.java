import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * Draws the Successor app icon (1024×1024 PNG): a macOS-style rounded square in pitch green with the centre circle
 * and a forward arrow passing through it (one player taking over from another).
 * Usage: java scripts/IconGen.java out.png
 */
public class IconGen {
    public static void main(String[] args) throws Exception {
        int s = 1024;
        // --ios: a full-bleed opaque square (iOS rounds the corners itself and rejects transparency)
        boolean ios = args.length > 1 && args[1].equals("--ios");
        BufferedImage img = new BufferedImage(s, s, ios ? BufferedImage.TYPE_INT_RGB : BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        double inset = ios ? 0 : 100;
        double body = s - 2 * inset;
        java.awt.Shape shape = ios ? new java.awt.Rectangle(0, 0, s, s)
                : new RoundRectangle2D.Double(inset, inset, body, body, 370, 370);
        for (int i = ios ? 0 : 18; i > 0; i--) {
            g.setColor(new Color(0, 0, 0, 3));
            g.fill(new RoundRectangle2D.Double(inset - i * 0.2, inset + 14 - i * 0.2 + i, body + i * 0.4,
                    body + i * 0.4, 370, 370));
        }
        g.setPaint(new GradientPaint(0, (float) inset, new Color(46, 184, 104), 0, (float) (inset + body),
                new Color(14, 110, 64)));
        g.fill(shape);
        g.setClip(shape);
        g.setColor(new Color(255, 255, 255, 22)); // mown stripes
        for (int i = 0; i < 6; i += 2) {
            g.fillRect((int) (inset + i * body / 6), (int) inset, (int) (body / 6), (int) body);
        }
        g.setClip(null);
        g.setColor(new Color(255, 255, 255, 235));
        g.setStroke(new BasicStroke(34f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        double r = 190;
        g.draw(new Ellipse2D.Double(s / 2.0 - r, s / 2.0 - r, 2 * r, 2 * r));
        g.setStroke(new BasicStroke(30f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.drawLine(s / 2, (int) inset + 40, s / 2, (int) (inset + body) - 40);
        // the successor arrow
        g.setColor(Color.WHITE);
        g.setStroke(new BasicStroke(64f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.drawLine(250, 512, 700, 512);
        Path2D head = new Path2D.Double();
        head.moveTo(640, 400);
        head.lineTo(790, 512);
        head.lineTo(640, 624);
        g.draw(head);
        g.dispose();
        File out = new File(args.length > 0 ? args[0] : "icon.png");
        ImageIO.write(img, "png", out);
        System.out.println("icon written: " + out.getAbsolutePath());
    }
}
