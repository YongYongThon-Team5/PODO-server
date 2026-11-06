package com.podo.server.profile.image.processing;

import java.nio.ByteBuffer;
import java.util.zip.CRC32;

import static com.podo.server.profile.image.processing.ProfileImageProcessor.checkPixels;
import static com.podo.server.profile.image.processing.ProfileImageProcessor.unsupported;

final class ImageHeaders {

    private static final long PNG_SIGNATURE = 0x89504e470d0a1a0aL;
    private static final int IHDR = 0x49484452;
    private static final int IDAT = 0x49444154;
    private static final int IEND = 0x49454e44;
    private static final int PLTE = 0x504c5445;
    private static final int EXIF = 0x65584966;

    private ImageHeaders() {}

    static int pngOrientation(byte[] bytes) {
        ByteBuffer png = ByteBuffer.wrap(bytes);
        if (bytes.length < 33 || png.getLong(0) != PNG_SIGNATURE
                || png.getInt(8) != 13 || png.getInt(12) != IHDR) {
            throw unsupported();
        }
        checkPixels(png.getInt(16), png.getInt(20));
        CRC32 crc = new CRC32();
        int orientation = 1;
        boolean sawExif = false;
        boolean sawBitmap = false;
        boolean finishedBitmap = false;
        for (int position = 8; position <= bytes.length - 12; ) {
            int size = png.getInt(position);
            int type = png.getInt(position + 4);
            if (size < 0 || size > bytes.length - position - 12) {
                throw unsupported();
            }
            int payload = position + 8;
            int next = payload + size + 4;
            crc.reset();
            crc.update(bytes, position + 4, size + 4);
            if ((int) crc.getValue() != png.getInt(payload + size)) {
                throw unsupported();
            }
            switch (type) {
                case IHDR -> {
                    if (position != 8) {
                        throw unsupported();
                    }
                }
                case IDAT -> {
                    if (finishedBitmap) {
                        throw unsupported();
                    }
                    sawBitmap = true;
                }
                case IEND -> {
                    if (size != 0 || !sawBitmap || next != bytes.length) {
                        throw unsupported();
                    }
                    return orientation;
                }
                case EXIF -> {
                    if (!sawExif) {
                        orientation = ExifOrientation.read(bytes, payload, size);
                        sawExif = true;
                    }
                }
                case PLTE -> {
                    if (sawBitmap) {
                        throw unsupported();
                    }
                }
                default -> {
                    if ((type & 0x20000000) == 0) {
                        throw unsupported();
                    }
                }
            }
            finishedBitmap |= sawBitmap && type != IDAT;
            position = next;
        }
        throw unsupported();
    }

    static int jpegOrientation(byte[] bytes) {
        ByteBuffer jpeg = ByteBuffer.wrap(bytes);
        if (bytes.length < 4 || jpeg.getShort(0) != (short) 0xffd8
                || jpeg.getShort(bytes.length - 2) != (short) 0xffd9) {
            throw unsupported();
        }

        for (int position = 2; position < bytes.length - 2; ) {
            if (bytes[position++] != (byte) 0xff) {
                throw unsupported();
            }
            while (position < bytes.length && bytes[position] == (byte) 0xff) {
                position++;
            }
            if (position >= bytes.length - 2) {
                throw unsupported();
            }
            int marker = Byte.toUnsignedInt(bytes[position++]);
            if (marker == 0xda) {
                return 1;
            }
            int size = Short.toUnsignedInt(jpeg.getShort(position));
            if (size < 2 || size > bytes.length - position) {
                throw unsupported();
            }
            int payload = position + 2;
            if (marker == 0xe1 && size >= 8 && jpeg.getInt(payload) == 0x45786966
                    && jpeg.getShort(payload + 4) == 0) {
                return ExifOrientation.read(bytes, payload, size - 2);
            }
            position += size;
        }
        throw unsupported();
    }
}
