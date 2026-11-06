package com.podo.server.profile.image.processing;

import com.podo.server.global.exception.BusinessException;
import com.podo.server.global.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.awt.image.IndexColorModel;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Random;
import java.util.zip.CRC32;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProfileImageProcessorTest {

    // cwebp encoded 32x24 fixtures, red on the left and blue on the right.
    private static final byte[] LOSSY_WEBP = Base64.getDecoder().decode(
            "UklGRmIAAABXRUJQVlA4IFYAAABwBACdASogABgAPn0wkkekoyGhN+gAkA+JZAC1FoB+IH7AcYAB+fwGfdepgAD+7nCH/+LOHGOfr//ftEob+f+uX3qI8ftEof+BO/gTEzKk1+0l5yOAAA==");
    private static final byte[] LOSSLESS_WEBP = Base64.getDecoder().decode(
            "UklGRiYAAABXRUJQVlA4TBoAAAAvH8AFEA8Q87//B/wPBwKBJH/pBQaI6H8Ubw==");
    private static final byte[] ALPHA_WEBP = Base64.getDecoder().decode(
            "UklGRpAAAABXRUJQVlA4WAoAAAAQAAAAHwAAFwAAQUxQSBQAAAABD3DA/4iIIBBI8pdeYICI/kfxBlZQOCBWAAAAcAQAnQEqIAAYAD59MJJHpKMhoTfoAJAPiWQAtRaAfiB+wHGAAfn8Bn3XqYAA/u5wh//izhxjn6//37RKG/n/rl96iPH7RKH/gTv4ExMypNftJecjgAA=");

    private final ProfileImageProcessor processor = new ProfileImageProcessor();

    @Test
    void acceptsAndReencodesOpaqueWebpAsJpeg() throws IOException {
        var result = processor.process(LOSSY_WEBP, "image/webp");

        assertThat(result.contentType()).isEqualTo("image/jpeg");
        BufferedImage image = decode(result.bytes());
        assertThat(image.getWidth()).isEqualTo(32);
        assertThat(image.getHeight()).isEqualTo(24);
        assertThat(new Color(image.getRGB(2, 2)).getRed()).isGreaterThan(220);
        assertThat(new Color(image.getRGB(28, 2)).getBlue()).isGreaterThan(220);
    }

    @Test
    void acceptsCompressedAndUncompressedWebpAlpha() throws IOException {
        byte[] rawAlpha = new byte[32 * 24 + 1];
        for (int y = 0; y < 24; y++) {
            Arrays.fill(rawAlpha, 1 + y * 32, 1 + y * 32 + 16, (byte) 127);
            Arrays.fill(rawAlpha, 1 + y * 32 + 16, 1 + (y + 1) * 32, (byte) 255);
        }
        byte[] rawAlphaWebp = webp(webpChunk("VP8X", canvas(0x10, 32, 24)),
                webpChunk("ALPH", rawAlpha), Arrays.copyOfRange(LOSSY_WEBP, 12, LOSSY_WEBP.length));

        for (byte[] input : new byte[][] {LOSSLESS_WEBP, ALPHA_WEBP, rawAlphaWebp}) {
            var result = processor.process(input, "image/webp");

            assertThat(result.contentType()).isEqualTo("image/png");
            BufferedImage image = decode(result.bytes());
            assertThat(image.getRGB(2, 2) >>> 24).isEqualTo(127);
            assertThat(image.getRGB(28, 2) >>> 24).isEqualTo(255);
        }
    }

    @Test
    void rotatesJpegAndRemovesExif() throws IOException {
        var original = new BufferedImage(32, 24, BufferedImage.TYPE_INT_RGB);
        var graphics = original.createGraphics();
        graphics.setColor(Color.RED);
        graphics.fillRect(0, 0, 16, 24);
        graphics.setColor(Color.BLUE);
        graphics.fillRect(16, 0, 16, 24);
        graphics.dispose();
        byte[] input = jpegWithExif(encode(original, "jpeg"), 6);

        var result = processor.process(input, "image/jpeg");

        assertThat(result.contentType()).isEqualTo("image/jpeg");
        assertThat(new String(result.bytes(), StandardCharsets.ISO_8859_1)).doesNotContain("Exif");
        BufferedImage image = decode(result.bytes());
        assertThat(image.getWidth()).isEqualTo(24);
        assertThat(image.getHeight()).isEqualTo(32);
        assertThat(new Color(image.getRGB(4, 4)).getRed()).isGreaterThan(220);
        assertThat(new Color(image.getRGB(4, 28)).getBlue()).isGreaterThan(220);
    }

    @ParameterizedTest
    @CsvSource({
            "1,3,2,123456", "2,3,2,321654", "3,3,2,654321", "4,3,2,456123",
            "5,2,3,142536", "6,2,3,415263", "7,2,3,635241", "8,2,3,362514"
    })
    void normalizesEveryExifOrientationAndStripsPngMetadata(
            int orientation, int width, int height, String expected) throws IOException {
        var original = new BufferedImage(3, 2, BufferedImage.TYPE_INT_ARGB);
        original.setRGB(0, 0, 3, 2, new int[] {
                0xff110000, 0xff220000, 0xff330000, 0xff440000, 0xff550000, 0xff660000
        }, 0, 3);
        byte[] input = pngWithChunk(encode(original, "png"), "eXIf", tiff(orientation));
        input = pngWithChunk(input, "tEXt", "Comment\0private location".getBytes(StandardCharsets.US_ASCII));

        var result = processor.process(input, "image/png");

        assertThat(result.contentType()).isEqualTo("image/png");
        assertThat(new String(result.bytes(), StandardCharsets.ISO_8859_1))
                .doesNotContain("eXIf", "tEXt", "private location");
        BufferedImage image = decode(result.bytes());
        assertThat(image.getWidth()).isEqualTo(width);
        assertThat(image.getHeight()).isEqualTo(height);
        StringBuilder actual = new StringBuilder();
        for (int rgb : image.getRGB(0, 0, width, height, null, 0, width)) {
            actual.append((rgb >> 20) & 0xf);
        }
        assertThat(actual.toString()).isEqualTo(expected);
    }

    @Test
    void appliesWebpExifWithMetadataAfterTheBitmap() throws IOException {
        byte[] input = webp(webpChunk("VP8X", canvas(0x18, 32, 24)),
                Arrays.copyOfRange(ALPHA_WEBP, 30, ALPHA_WEBP.length), webpChunk("EXIF", tiff(8)));

        var result = processor.process(input, "image/webp");

        assertThat(result.contentType()).isEqualTo("image/png");
        BufferedImage image = decode(result.bytes());
        assertThat(image.getWidth()).isEqualTo(24);
        assertThat(image.getHeight()).isEqualTo(32);
        assertThat(image.getRGB(4, 4) >>> 24).isEqualTo(255);
        assertThat(image.getRGB(4, 28) >>> 24).isEqualTo(127);
    }

    @Test
    void usesFirstAnimatedWebpFrameAndPreservesCanvas() throws IOException {
        byte[] frameHeader = new byte[16];
        frameHeader[0] = 2; // x = 4
        frameHeader[6] = 31;
        frameHeader[9] = 23;
        byte[] input = webp(webpChunk("VP8X", canvas(0x02, 36, 24)), webpChunk("ANIM", new byte[6]),
                webpChunk("ANMF", concat(frameHeader, Arrays.copyOfRange(LOSSY_WEBP, 12, LOSSY_WEBP.length))));

        var result = processor.process(input, "image/webp");

        assertThat(result.contentType()).isEqualTo("image/png");
        BufferedImage image = decode(result.bytes());
        assertThat(image.getWidth()).isEqualTo(36);
        assertThat(image.getHeight()).isEqualTo(24);
        assertThat(image.getRGB(0, 4) >>> 24).isZero();
        assertThat(image.getRGB(6, 4) >>> 24).isEqualTo(255);
    }

    @ParameterizedTest
    @ValueSource(strings = {"image/jpeg", "image/png", "image/gif", "application/octet-stream"})
    void rejectsDeclaredTypeMismatch(String declaredType) {
        assertRejected(LOSSY_WEBP, declaredType, ErrorCode.UNSUPPORTED_IMAGE_TYPE);
    }

    @Test
    void rejectsEmptyTruncatedAndCorruptBitmaps() throws IOException {
        var original = new BufferedImage(24, 24, BufferedImage.TYPE_INT_RGB);
        byte[] jpeg = encode(original, "jpeg");
        byte[] png = encode(original, "png");
        byte[] badCrc = png.clone();
        badCrc[badCrc.length - 1] ^= 1;

        assertRejected(new byte[0], "image/png", ErrorCode.UNSUPPORTED_IMAGE_TYPE);
        assertRejected(Arrays.copyOf(jpeg, jpeg.length - 4), "image/jpeg", ErrorCode.UNSUPPORTED_IMAGE_TYPE);
        assertRejected(badCrc, "image/png", ErrorCode.UNSUPPORTED_IMAGE_TYPE);
        assertRejected(Arrays.copyOf(LOSSY_WEBP, LOSSY_WEBP.length - 1), "image/webp",
                ErrorCode.UNSUPPORTED_IMAGE_TYPE);
        byte[] truncatedLossless = Arrays.copyOf(LOSSLESS_WEBP, LOSSLESS_WEBP.length - 10);
        ByteBuffer.wrap(truncatedLossless).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(4, truncatedLossless.length - 8).putInt(16, truncatedLossless.length - 20);
        assertRejected(truncatedLossless, "image/webp", ErrorCode.UNSUPPORTED_IMAGE_TYPE);
        assertRejected(webp(webpChunk("VP8X", canvas(0, 32, 24))), "image/webp", ErrorCode.UNSUPPORTED_IMAGE_TYPE);
        assertRejected(webp(webpChunk("VP8X", canvas(0, 30, 24)),
                        Arrays.copyOfRange(LOSSY_WEBP, 12, LOSSY_WEBP.length)), "image/webp",
                ErrorCode.UNSUPPORTED_IMAGE_TYPE);
    }

    @Test
    void rejectsByteAndPixelLimitsBeforeDecoding() throws IOException {
        assertRejected(new byte[5_242_881], "image/png", ErrorCode.UPLOAD_TOO_LARGE);

        byte[] png = encode(new BufferedImage(24, 24, BufferedImage.TYPE_INT_RGB), "png");
        assertRejected(pngWithDimensions(png, 4097, 4096), "image/png", ErrorCode.UPLOAD_TOO_LARGE);
        assertRejected(pngWithDimensions(png, 16_385, 1), "image/png", ErrorCode.UPLOAD_TOO_LARGE);
        assertRejected(webp(webpChunk("VP8X", canvas(0, 4097, 4096))), "image/webp", ErrorCode.UPLOAD_TOO_LARGE);
    }

    private static byte[] pngWithDimensions(byte[] png, int width, int height) {
        byte[] result = png.clone();
        ByteBuffer header = ByteBuffer.wrap(result);
        header.putInt(16, width).putInt(20, height);
        CRC32 crc = new CRC32();
        crc.update(result, 12, 17);
        header.putInt(29, (int) crc.getValue());
        return result;
    }

    @Test
    void reducesResolutionOnlyWhenReencodedOutputExceedsTheLimit() throws IOException {
        Random random = new Random(97);
        byte[][] palette = new byte[4][256];
        for (byte[] channel : palette) {
            random.nextBytes(channel);
        }
        var colors = new IndexColorModel(8, 256, palette[0], palette[1], palette[2], palette[3]);
        var image = new BufferedImage(2048, 2048, BufferedImage.TYPE_BYTE_INDEXED, colors);
        random.nextBytes(((DataBufferByte) image.getRaster().getDataBuffer()).getData());
        byte[] input = encode(image, "png");
        assertThat(input.length).isLessThan(ProfileImageProcessor.MAX_BYTES);

        var result = processor.process(input, "image/png");

        assertThat(result.bytes().length).isLessThanOrEqualTo(ProfileImageProcessor.MAX_BYTES);
        BufferedImage reduced = decode(result.bytes());
        assertThat(reduced.getWidth()).isEqualTo(1024);
        assertThat(reduced.getHeight()).isEqualTo(1024);
        assertThat(reduced.getColorModel().hasAlpha()).isTrue();
    }

    private void assertRejected(byte[] bytes, String mimeType, ErrorCode expected) {
        assertThatThrownBy(() -> processor.process(bytes, mimeType))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.getErrorCode()).isEqualTo(expected));
    }

    private static byte[] encode(BufferedImage image, String format) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, format, output);
        return output.toByteArray();
    }

    private static BufferedImage decode(byte[] bytes) throws IOException {
        return ImageIO.read(new ByteArrayInputStream(bytes));
    }

    private static byte[] jpegWithExif(byte[] jpeg, int orientation) {
        byte[] exif = concat(new byte[] {'E', 'x', 'i', 'f', 0, 0}, tiff(orientation));
        byte[] app1 = ByteBuffer.allocate(exif.length + 4)
                .putShort((short) 0xffe1).putShort((short) (exif.length + 2)).put(exif).array();
        return concat(Arrays.copyOf(jpeg, 2), app1, Arrays.copyOfRange(jpeg, 2, jpeg.length));
    }

    private static byte[] tiff(int orientation) {
        ByteOrder order = orientation % 2 == 0 ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN;
        int marker = order == ByteOrder.LITTLE_ENDIAN ? 0x4949 : 0x4d4d;
        return ByteBuffer.allocate(26).order(order).putShort((short) marker)
                .putShort((short) 42).putInt(8).putShort((short) 1)
                .putShort((short) 0x0112).putShort((short) 3).putInt(1)
                .putShort((short) orientation).putShort((short) 0).putInt(0).array();
    }

    private static byte[] pngWithChunk(byte[] png, String type, byte[] data) {
        byte[] chunkType = type.getBytes(StandardCharsets.US_ASCII);
        CRC32 crc = new CRC32();
        crc.update(chunkType);
        crc.update(data);
        byte[] chunk = ByteBuffer.allocate(data.length + 12).putInt(data.length)
                .put(chunkType).put(data).putInt((int) crc.getValue()).array();
        return concat(Arrays.copyOf(png, 33), chunk, Arrays.copyOfRange(png, 33, png.length));
    }

    private static byte[] webp(byte[]... chunks) {
        byte[] body = concat(chunks);
        return ByteBuffer.allocate(12 + body.length).order(ByteOrder.LITTLE_ENDIAN)
                .put(new byte[] {'R', 'I', 'F', 'F'}).putInt(4 + body.length)
                .put(new byte[] {'W', 'E', 'B', 'P'}).put(body).array();
    }

    private static byte[] webpChunk(String type, byte[] data) {
        return ByteBuffer.allocate(8 + data.length + data.length % 2).order(ByteOrder.LITTLE_ENDIAN)
                .put(type.getBytes(StandardCharsets.US_ASCII)).putInt(data.length).put(data).array();
    }

    private static byte[] canvas(int flags, int width, int height) {
        byte[] header = new byte[10];
        header[0] = (byte) flags;
        writeUnsigned24(header, 4, width - 1);
        writeUnsigned24(header, 7, height - 1);
        return header;
    }

    private static void writeUnsigned24(byte[] bytes, int offset, int value) {
        for (int i = 0; i < 3; i++) {
            bytes[offset + i] = (byte) (value >>> (8 * i));
        }
    }

    private static byte[] concat(byte[]... arrays) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (byte[] array : arrays) {
            output.writeBytes(array);
        }
        return output.toByteArray();
    }
}
