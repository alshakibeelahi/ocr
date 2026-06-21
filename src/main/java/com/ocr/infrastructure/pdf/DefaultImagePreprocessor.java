package com.ocr.infrastructure.pdf;

import com.ocr.domain.model.PageMetadata;
import com.ocr.domain.service.ImagePreprocessor;
import org.springframework.stereotype.Component;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

@Component
public class DefaultImagePreprocessor implements ImagePreprocessor {

    @Override
    public PreprocessResult preprocess(BufferedImage source, int dpi, int maxDimension) {
        BufferedImage grayscale = toGrayscale(source);
        BufferedImage cropped = trimWhitespace(grayscale);
        BufferedImage scaled = scaleDown(cropped, maxDimension);
        PageMetadata metadata = new PageMetadata(
                dpi,
                true,
                scaled.getWidth(),
                scaled.getHeight(),
                0L
        );
        return new PreprocessResult(scaled, metadata);
    }

    private BufferedImage toGrayscale(BufferedImage source) {
        BufferedImage gray = new BufferedImage(
                source.getWidth(),
                source.getHeight(),
                BufferedImage.TYPE_BYTE_GRAY
        );
        Graphics2D graphics = gray.createGraphics();
        graphics.drawImage(source, 0, 0, null);
        graphics.dispose();
        return gray;
    }

    private BufferedImage trimWhitespace(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        int minX = width;
        int minY = height;
        int maxX = 0;
        int maxY = 0;

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int rgb = image.getRGB(x, y) & 0xFF;
                if (rgb < 250) {
                    minX = Math.min(minX, x);
                    minY = Math.min(minY, y);
                    maxX = Math.max(maxX, x);
                    maxY = Math.max(maxY, y);
                }
            }
        }

        if (maxX <= minX || maxY <= minY) {
            return image;
        }

        int padding = 10;
        minX = Math.max(0, minX - padding);
        minY = Math.max(0, minY - padding);
        maxX = Math.min(width - 1, maxX + padding);
        maxY = Math.min(height - 1, maxY + padding);

        return image.getSubimage(minX, minY, maxX - minX + 1, maxY - minY + 1);
    }

    private BufferedImage scaleDown(BufferedImage image, int maxDimension) {
        int longEdge = Math.max(image.getWidth(), image.getHeight());
        if (longEdge <= maxDimension) {
            return image;
        }
        double ratio = (double) maxDimension / longEdge;
        int newWidth = (int) Math.round(image.getWidth() * ratio);
        int newHeight = (int) Math.round(image.getHeight() * ratio);
        BufferedImage scaled = new BufferedImage(newWidth, newHeight, image.getType());
        Graphics2D graphics = scaled.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, newWidth, newHeight);
        graphics.drawImage(image, 0, 0, newWidth, newHeight, null);
        graphics.dispose();
        return scaled;
    }
}
