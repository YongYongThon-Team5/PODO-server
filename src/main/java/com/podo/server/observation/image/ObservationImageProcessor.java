package com.podo.server.observation.image;

import com.podo.server.global.exception.BusinessException;
import com.podo.server.global.exception.ErrorCode;
import com.podo.server.profile.image.processing.ProfileImageProcessor;
import java.awt.Color;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;
import org.springframework.stereotype.Component;

@Component
public class ObservationImageProcessor {

    private static final int MAX_EDGE = 1536;

    private final ProfileImageProcessor processor;

    public ObservationImageProcessor(
            ProfileImageProcessor processor
    ) {
        this.processor = processor;
    }

    public synchronized byte[] process(
            byte[] bytes,
            String contentType
    ) {
        var normalized = processor.process(bytes, contentType);

        try {
            BufferedImage original = ImageIO.read(
                    new ByteArrayInputStream(normalized.bytes())
            );

            if (original == null) {
                throw new BusinessException(
                        ErrorCode.UNSUPPORTED_IMAGE_TYPE
                );
            }

            double scale = Math.min(
                    1d,
                    (double) MAX_EDGE / Math.max(
                            original.getWidth(),
                            original.getHeight()
                    )
            );

            int width = Math.max(
                    1,
                    (int) Math.round(original.getWidth() * scale)
            );

            int height = Math.max(
                    1,
                    (int) Math.round(original.getHeight() * scale)
            );

            var result = new BufferedImage(
                    width,
                    height,
                    BufferedImage.TYPE_INT_RGB
            );

            var graphics = result.createGraphics();

            try {
                graphics.setColor(Color.WHITE);
                graphics.fillRect(0, 0, width, height);

                graphics.setRenderingHint(
                        RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_BILINEAR
                );

                graphics.drawImage(
                        original,
                        0,
                        0,
                        width,
                        height,
                        null
                );
            } finally {
                graphics.dispose();
            }

            var output = new ByteArrayOutputStream();

            if (!ImageIO.write(result, "jpeg", output)) {
                throw new IOException("JPEG writer missing");
            }

            if (output.size() > ProfileImageProcessor.MAX_BYTES) {
                throw new BusinessException(
                        ErrorCode.UPLOAD_TOO_LARGE
                );
            }

            return output.toByteArray();
        } catch (IOException ex) {
            throw new BusinessException(
                    ErrorCode.SERVICE_UNAVAILABLE
            );
        }
    }
}