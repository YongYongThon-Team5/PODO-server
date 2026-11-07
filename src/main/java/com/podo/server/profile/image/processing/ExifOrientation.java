package com.podo.server.profile.image.processing;

import java.awt.geom.AffineTransform;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

final class ExifOrientation {

    private ExifOrientation() {}

    static int read(byte[] bytes, int offset, int length) {
        ByteBuffer tiff = ByteBuffer.wrap(bytes, offset, length).slice();
        // JPEG APP1 and some WebP encoders prefix the TIFF header with "Exif\0\0".
        if (tiff.remaining() >= 6 && tiff.getInt(0) == 0x45786966 && tiff.getShort(4) == 0) {
            tiff.position(6);
            tiff = tiff.slice();
        }
        if (tiff.remaining() < 8) {
            return 1;
        }
        int byteOrder = tiff.getShort(0);
        if (byteOrder == 0x4949) {
            tiff.order(ByteOrder.LITTLE_ENDIAN);
        } else if (byteOrder != 0x4d4d) {
            return 1;
        }
        int ifdOffset = tiff.getInt(4);
        if (tiff.getShort(2) != 42 || ifdOffset < 8 || ifdOffset > tiff.limit() - 2) {
            return 1;
        }

        int entries = Short.toUnsignedInt(tiff.getShort(ifdOffset));
        int start = ifdOffset + 2;
        if (entries > (tiff.limit() - start) / 12) {
            return 1;
        }
        for (int position = start; position < start + 12 * entries; position += 12) {
            if (tiff.getShort(position) == 0x0112
                    && tiff.getShort(position + 2) == 3 && tiff.getInt(position + 4) == 1) {
                int orientation = tiff.getShort(position + 8);
                return orientation >= 1 && orientation <= 8 ? orientation : 1;
            }
        }
        return 1;
    }

    static AffineTransform transform(int orientation, int width, int height) {
        return switch (orientation) {
            case 2 -> new AffineTransform(-1, 0, 0, 1, width, 0);
            case 3 -> new AffineTransform(-1, 0, 0, -1, width, height);
            case 4 -> new AffineTransform(1, 0, 0, -1, 0, height);
            case 5 -> new AffineTransform(0, 1, 1, 0, 0, 0);
            case 6 -> new AffineTransform(0, 1, -1, 0, height, 0);
            case 7 -> new AffineTransform(0, -1, -1, 0, height, width);
            case 8 -> new AffineTransform(0, -1, 1, 0, 0, width);
            default -> new AffineTransform();
        };
    }
}
