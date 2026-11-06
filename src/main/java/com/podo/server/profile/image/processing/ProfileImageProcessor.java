package com.podo.server.profile.image.processing;

import com.podo.server.global.exception.BusinessException;
import com.podo.server.global.exception.ErrorCode;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Iterator;
import java.util.List;

@Component
public class ProfileImageProcessor {

    public static final int MAX_BYTES = 5_242_880;
    public static final int MAX_PIXELS = 16_777_216;
    public static final int MAX_EDGE = 16_384;
    public static final List<String> ALLOWED_CONTENT_TYPES = List.of("image/jpeg", "image/png", "image/webp");
    private static final int FALLBACK_MAX_EDGE = 1024;

    // Each decode can hold multiple full-sized rasters. Keep them from overlapping in this bean.
    public synchronized ProcessedImage process(byte[] bytes, String declaredContentType) {
        if (bytes.length > MAX_BYTES) {
            throw new BusinessException(ErrorCode.UPLOAD_TOO_LARGE);
        }
        if (declaredContentType == null || bytes.length == 0) {
            throw unsupported();
        }

        if ("image/webp".equalsIgnoreCase(declaredContentType)) {
            WebpProfileImage webp = WebpProfileImage.read(bytes);
            BufferedImage decoded = decode(webp.decoderInput(), "webp");
            return encode(decoded, webp.width(), webp.height(), webp.x(), webp.y(), webp.orientation(), false);
        }
        if ("image/png".equalsIgnoreCase(declaredContentType)) {
            int orientation = ImageHeaders.pngOrientation(bytes);
            BufferedImage decoded = decode(bytes, "png");
            return encode(decoded, decoded.getWidth(), decoded.getHeight(), 0, 0, orientation, true);
        }
        if ("image/jpeg".equalsIgnoreCase(declaredContentType)) {
            int orientation = ImageHeaders.jpegOrientation(bytes);
            BufferedImage decoded = decode(bytes, "jpeg");
            return encode(decoded, decoded.getWidth(), decoded.getHeight(), 0, 0, orientation, false);
        }
        throw unsupported();
    }

    private static BufferedImage decode(byte[] bytes, String format) {
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw unsupported();
            }
            ImageReader reader = readers.next();
            try {
                if (!format.equalsIgnoreCase(reader.getFormatName())) {
                    throw unsupported();
                }
                boolean[] warned = {false};
                reader.addIIOReadWarningListener((source, warning) -> warned[0] = true);
                reader.setInput(input, false, true);
                checkPixels(reader.getWidth(0), reader.getHeight(0));
                BufferedImage decoded = reader.read(0);
                if (warned[0] || decoded == null) {
                    throw unsupported();
                }
                return decoded;
            } finally {
                reader.dispose();
            }
        } catch (BusinessException e) {
            throw e;
        } catch (IOException | RuntimeException | AssertionError e) {
            // Decoder plug-ins may fail with unchecked exceptions on malformed bitstreams.
            throw unsupported();
        }
    }

    private static ProcessedImage encode(BufferedImage decoded, int canvasWidth, int canvasHeight,
                                         int x, int y, int orientation, boolean forcePng) {
        boolean alpha = decoded.getColorModel().hasAlpha()
                || decoded.getWidth() != canvasWidth || decoded.getHeight() != canvasHeight;
        String format = forcePng || alpha ? "png" : "jpeg";
        BufferedImage normalized = render(decoded, canvasWidth, canvasHeight, x, y, orientation, alpha, 1);
        byte[] data = write(normalized, format);
        if (data == null) {
            // Re-encoding can expand a legal input (for example, WebP alpha to PNG).
            double scale = Math.min(1, (double) FALLBACK_MAX_EDGE / Math.max(canvasWidth, canvasHeight));
            data = write(render(decoded, canvasWidth, canvasHeight, x, y, orientation, alpha, scale), format);
            if (data == null) {
                throw new BusinessException(ErrorCode.UPLOAD_TOO_LARGE);
            }
        }
        return new ProcessedImage("image/" + format, data);
    }

    private static BufferedImage render(BufferedImage source, int width, int height,
                                         int x, int y, int orientation, boolean alpha, double scale) {
        int type = alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
        if (scale == 1 && orientation == 1 && source.getType() == type
                && source.getWidth() == width && source.getHeight() == height) {
            return source;
        }

        boolean swapAxes = orientation >= 5;
        int orientedWidth = swapAxes ? height : width;
        int orientedHeight = swapAxes ? width : height;
        int outputWidth = Math.max(1, (int) Math.round(orientedWidth * scale));
        int outputHeight = Math.max(1, (int) Math.round(orientedHeight * scale));
        var result = new BufferedImage(outputWidth, outputHeight, type);
        Graphics2D graphics = result.createGraphics();
        try {
            graphics.setComposite(AlphaComposite.Src);
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.scale((double) outputWidth / orientedWidth, (double) outputHeight / orientedHeight);
            graphics.transform(ExifOrientation.transform(orientation, width, height));
            graphics.drawImage(source, x, y, null);
        } finally {
            graphics.dispose();
        }
        return result;
    }

    private static byte[] write(BufferedImage image, String format) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        var output = new BoundedImageOutput(bytes);
        try (output) {
            if (!ImageIO.write(image, format, output)) {
                throw new IllegalStateException("Missing ImageIO writer for " + format);
            }
        } catch (IOException e) {
            if (output.limitExceeded) {
                return null;
            }
            throw new UncheckedIOException("Could not encode profile image", e);
        }
        return bytes.toByteArray();
    }

    static void checkPixels(int width, int height) {
        if (width <= 0 || height <= 0) {
            throw unsupported();
        }
        // Bound scanline buffers even when an extremely narrow image fits the pixel budget.
        if (width > MAX_EDGE || height > MAX_EDGE || (long) width * height > MAX_PIXELS) {
            throw new BusinessException(ErrorCode.UPLOAD_TOO_LARGE);
        }
    }

    static BusinessException unsupported() {
        return new BusinessException(ErrorCode.UNSUPPORTED_IMAGE_TYPE);
    }

    public record ProcessedImage(String contentType, byte[] bytes) {}

    private static final class BoundedImageOutput extends MemoryCacheImageOutputStream {

        private boolean limitExceeded;

        private BoundedImageOutput(ByteArrayOutputStream output) {
            super(output);
        }

        @Override
        public void write(int value) throws IOException {
            checkRoom(1);
            super.write(value);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            checkRoom(length);
            super.write(bytes, offset, length);
        }

        private void checkRoom(int count) throws IOException {
            if (getStreamPosition() + count > MAX_BYTES) {
                limitExceeded = true;
                throw new IOException("Profile image output exceeded " + MAX_BYTES + " bytes");
            }
        }
    }
}
