package com.successor.app;

import javafx.scene.Node;
import javafx.scene.SnapshotParameters;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.transform.Transform;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Saves a node as a Retina (2×) PNG: used for README screenshots and visual checks ({@code --snapshot}). */
final class Snapshots {
    private Snapshots() {
    }

    static void save(Node node, Path out) {
        SnapshotParameters params = new SnapshotParameters();
        params.setTransform(Transform.scale(2, 2));
        WritableImage img = node.snapshot(params, null);
        int w = (int) img.getWidth();
        int h = (int) img.getHeight();
        BufferedImage bi = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        PixelReader pr = img.getPixelReader();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                bi.setRGB(x, y, pr.getArgb(x, y));
            }
        }
        try {
            Path abs = out.toAbsolutePath();
            Files.createDirectories(abs.getParent());
            Files.deleteIfExists(abs);
            ImageIO.write(bi, "png", abs.toFile());
            System.out.println("snapshot written: " + abs);
        } catch (IOException e) {
            System.err.println("snapshot failed: " + e);
        }
    }
}
