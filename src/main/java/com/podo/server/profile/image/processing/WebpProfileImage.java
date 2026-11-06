package com.podo.server.profile.image.processing;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static com.podo.server.profile.image.processing.ProfileImageProcessor.checkPixels;
import static com.podo.server.profile.image.processing.ProfileImageProcessor.unsupported;

record WebpProfileImage(ByteBuffer file, Frame frame, int width, int height, int x, int y, int orientation) {

    private static final int RIFF = 0x46464952;
    private static final int WEBP = 0x50424557;
    private static final int VP8 = 0x20385056;
    private static final int VP8L = 0x4c385056;
    private static final int VP8X = 0x58385056;
    private static final int EXIF = 0x46495845;
    private static final int ALPH = 0x48504c41;
    private static final int ANIM = 0x4d494e41;
    private static final int ANMF = 0x464d4e41;
    private static final int ALPHA_FLAG = 0x10;
    private static final int ANIMATION_FLAG = 0x02;

    static WebpProfileImage read(byte[] bytes) {
        ByteBuffer file = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        if (bytes.length < 20 || file.getInt(0) != RIFF || file.getInt(8) != WEBP
                || file.getInt(4) != bytes.length - 8) {
            throw unsupported();
        }
        Chunk first = readChunk(file, 12, bytes.length);
        if (first.type() == VP8 || first.type() == VP8L) {
            if (first.end() != bytes.length) {
                throw unsupported();
            }
            Frame frame = bitmap(file, first, null);
            return new WebpProfileImage(file, frame, frame.width(), frame.height(), 0, 0, 1);
        }
        if (first.type() != VP8X || first.size() != 10) {
            throw unsupported();
        }

        int flags = Byte.toUnsignedInt(file.get(first.payload()));
        int width = read24(file, first.payload() + 4) + 1;
        int height = read24(file, first.payload() + 7) + 1;
        checkPixels(width, height);
        if ((flags & ANIMATION_FLAG) != 0) {
            return readAnimation(file, first.end(), width, height);
        }
        Frame frame = readFrame(file, first.end(), bytes.length, width, height);
        return new WebpProfileImage(file, frame, width, height, 0, 0,
                readOrientation(file, first.end(), bytes.length));
    }

    private static WebpProfileImage readAnimation(ByteBuffer file, int start, int width, int height) {
        boolean hasAnimationHeader = false;
        Frame firstFrame = null;
        int x = 0;
        int y = 0;
        for (int offset = start; offset < file.limit(); ) {
            Chunk chunk = readChunk(file, offset, file.limit());
            if (chunk.type() == ANIM) {
                if (chunk.size() != 6 || hasAnimationHeader) {
                    throw unsupported();
                }
                hasAnimationHeader = true;
            } else if (chunk.type() == ANMF) {
                if (!hasAnimationHeader || chunk.size() < 16) {
                    throw unsupported();
                }
                int payload = chunk.payload();
                int frameX = read24(file, payload) * 2;
                int frameY = read24(file, payload + 3) * 2;
                int frameWidth = read24(file, payload + 6) + 1;
                int frameHeight = read24(file, payload + 9) + 1;
                checkPixels(frameWidth, frameHeight);
                if (frameX + frameWidth > width || frameY + frameHeight > height) {
                    throw unsupported();
                }
                Frame current = readFrame(file, payload + 16, payload + chunk.size(), frameWidth, frameHeight);
                if (firstFrame == null) {
                    firstFrame = current;
                    x = frameX;
                    y = frameY;
                }
            } else if (chunk.type() == VP8 || chunk.type() == VP8L || chunk.type() == ALPH || chunk.type() == VP8X) {
                throw unsupported();
            }
            offset = chunk.end();
        }
        if (firstFrame == null) {
            throw unsupported();
        }
        return new WebpProfileImage(file, firstFrame, width, height, x, y,
                readOrientation(file, start, file.limit()));
    }

    private static Frame readFrame(ByteBuffer file, int start, int end, int width, int height) {
        Chunk alpha = null;
        Frame result = null;
        for (int offset = start; offset < end; ) {
            Chunk chunk = readChunk(file, offset, end);
            switch (chunk.type()) {
                case ALPH -> {
                    if (alpha != null || result != null || chunk.size() < 2) {
                        throw unsupported();
                    }
                    alpha = chunk;
                }
                case VP8, VP8L -> {
                    if (result != null) {
                        throw unsupported();
                    }
                    result = bitmap(file, chunk, alpha);
                }
                case VP8X, ANMF, ANIM -> throw unsupported();
                default -> {
                    // Ancillary chunks are left out of the decoder input.
                }
            }
            offset = chunk.end();
        }
        if (result == null || result.width() != width || result.height() != height) {
            throw unsupported();
        }
        return result;
    }

    private static Frame bitmap(ByteBuffer file, Chunk chunk, Chunk alpha) {
        int payload = chunk.payload();
        int width;
        int height;
        if (chunk.type() == VP8L) {
            if (chunk.size() <= 5 || file.get(payload) != 0x2f || alpha != null) {
                throw unsupported();
            }
            int bits = file.getInt(payload + 1);
            if (bits >>> 29 != 0) {
                throw unsupported();
            }
            width = (bits & 0x3fff) + 1;
            height = ((bits >>> 14) & 0x3fff) + 1;
        } else {
            if (chunk.size() <= 10 || read24(file, payload + 3) != 0x2a019d) {
                throw unsupported();
            }
            int frameTag = read24(file, payload);
            if ((frameTag & 1) != 0 || (frameTag >>> 5) > chunk.size() - 10) {
                throw unsupported();
            }
            width = file.getShort(payload + 6) & 0x3fff;
            height = file.getShort(payload + 8) & 0x3fff;
        }
        checkPixels(width, height);
        if (alpha != null) {
            int compression = file.get(alpha.payload()) & 3;
            if (compression > 1 || (compression == 0 && alpha.size() != 1 + width * height)) {
                throw unsupported();
            }
        }
        return new Frame(chunk, alpha, width, height);
    }

    private static int readOrientation(ByteBuffer file, int start, int end) {
        for (int offset = start; offset < end; ) {
            Chunk chunk = readChunk(file, offset, end);
            if (chunk.type() == EXIF) {
                return ExifOrientation.read(file.array(), chunk.payload(), chunk.size());
            }
            offset = chunk.end();
        }
        return 1;
    }

    byte[] decoderInput() {
        Chunk bitmap = frame.bitmap();
        Chunk alpha = frame.alpha();
        boolean separateAlpha = alpha != null;
        int size = 12 + bitmap.totalSize() + (separateAlpha ? 18 + alpha.totalSize() : 0);
        ByteBuffer still = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
        still.putInt(RIFF).putInt(size - 8).putInt(WEBP);
        if (separateAlpha) {
            still.putInt(VP8X).putInt(10).putInt(ALPHA_FLAG);
            write24(still, frame.width() - 1);
            write24(still, frame.height() - 1);
            still.put(file.array(), alpha.payload() - 8, alpha.totalSize());
        }
        still.put(file.array(), bitmap.payload() - 8, bitmap.totalSize());
        return still.array();
    }

    private static Chunk readChunk(ByteBuffer file, int offset, int end) {
        if (end - offset < 8) {
            throw unsupported();
        }
        int size = file.getInt(offset + 4);
        int payload = offset + 8;
        if (size < 0 || size > end - payload || (size & 1) > end - payload - size) {
            throw unsupported();
        }
        return new Chunk(file.getInt(offset), payload, size, payload + size + (size & 1));
    }

    private static int read24(ByteBuffer file, int offset) {
        return Short.toUnsignedInt(file.getShort(offset)) | (Byte.toUnsignedInt(file.get(offset + 2)) << 16);
    }

    private static void write24(ByteBuffer file, int value) {
        file.putShort((short) value).put((byte) (value >>> 16));
    }

    private record Frame(Chunk bitmap, Chunk alpha, int width, int height) {}

    private record Chunk(int type, int payload, int size, int end) {

        int totalSize() {
            return end - payload + 8;
        }
    }
}
