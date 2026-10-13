package com.nexora.hotel.storage;

import com.nexora.hotel.exception.BusinessException;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Iterator;

/**
 * Valida y limpia imágenes subidas. Nunca confía en el Content-Type ni en la extensión del cliente:
 * <ol>
 *   <li>Detecta el tipo REAL por la firma binaria (patrón de DemoPV {@code ImageContentValidator}).</li>
 *   <li>Lee las dimensiones SIN decodificar y rechaza imágenes gigantes (bombas de descompresión).</li>
 *   <li>JPEG/PNG: decodifica y vuelve a codificar → elimina EXIF (GPS, cámara), perfiles y cualquier dato
 *       ajeno a los píxeles (incluido contenido "políglota" escondido tras la imagen).</li>
 *   <li>WebP: se quitan los bloques EXIF y XMP del contenedor RIFF (ImageIO no codifica WebP).</li>
 * </ol>
 */
public final class ImageSanitizer {

    public static final int MAX_DIMENSION = 8000;

    public record CleanImage(byte[] bytes, String mimeType, String extension, Integer width, Integer height) {
    }

    private ImageSanitizer() {
    }

    public static CleanImage sanitize(byte[] data) {
        String mime = detect(data);
        if (mime == null) {
            throw new BusinessException("La imagen debe ser JPG, PNG o WEBP");
        }
        return switch (mime) {
            case "image/jpeg" -> reencode(data, "jpg", mime);
            case "image/png" -> reencode(data, "png", mime);
            default -> new CleanImage(stripWebpMetadata(data), mime, "webp", null, null);
        };
    }

    static String detect(byte[] h) {
        if (h.length >= 3 && (h[0] & 0xFF) == 0xFF && (h[1] & 0xFF) == 0xD8 && (h[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        if (h.length >= 8 && (h[0] & 0xFF) == 0x89 && h[1] == 'P' && h[2] == 'N' && h[3] == 'G'
                && h[4] == 0x0D && h[5] == 0x0A && h[6] == 0x1A && h[7] == 0x0A) {
            return "image/png";
        }
        if (h.length >= 12 && h[0] == 'R' && h[1] == 'I' && h[2] == 'F' && h[3] == 'F'
                && h[8] == 'W' && h[9] == 'E' && h[10] == 'B' && h[11] == 'P') {
            return "image/webp";
        }
        return null;
    }

    private static CleanImage reencode(byte[] data, String format, String mime) {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                throw new BusinessException("La imagen está dañada o no se puede leer");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width > MAX_DIMENSION || height > MAX_DIMENSION) {
                    throw new BusinessException("La imagen no debe medir más de " + MAX_DIMENSION + " px por lado");
                }
                BufferedImage image = reader.read(0);
                if ("jpg".equals(format) && image.getColorModel().hasAlpha()) {
                    image = dropAlpha(image);
                }
                return new CleanImage(write(image, format), mime, format, width, height);
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            if (e instanceof BusinessException be) {
                throw be;
            }
            throw new BusinessException("La imagen está dañada o no se puede leer");
        }
    }

    private static BufferedImage dropAlpha(BufferedImage src) {
        BufferedImage rgb = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        rgb.createGraphics().drawImage(src, 0, 0, java.awt.Color.WHITE, null);
        return rgb;
    }

    private static byte[] write(BufferedImage image, String format) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageWriter writer = ImageIO.getImageWritersByFormatName(format).next();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(ios);
            ImageWriteParam param = writer.getDefaultWriteParam();
            if ("jpg".equals(format)) {
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(0.88f);
            }
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }

    /** Reconstruye el RIFF sin los bloques EXIF/XMP y apaga sus banderas en VP8X. */
    static byte[] stripWebpMetadata(byte[] data) {
        if (data.length < 20) {
            throw new BusinessException("La imagen está dañada o no se puede leer");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(data.length);
        out.write(data, 0, 12);
        int pos = 12;
        while (pos + 8 <= data.length) {
            String fourcc = new String(data, pos, 4, java.nio.charset.StandardCharsets.US_ASCII);
            int size = ByteBuffer.wrap(data, pos + 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
            int padded = size + (size & 1);
            if (size < 0 || pos + 8 + padded > data.length + (size & 1)) {
                throw new BusinessException("La imagen está dañada o no se puede leer");
            }
            int end = Math.min(pos + 8 + padded, data.length);
            if (!"EXIF".equals(fourcc) && !"XMP ".equals(fourcc)) {
                byte[] chunk = java.util.Arrays.copyOfRange(data, pos, end);
                if ("VP8X".equals(fourcc) && chunk.length > 8) {
                    chunk[8] &= (byte) ~0x0C; // bits de EXIF (0x08) y XMP (0x04)
                }
                out.write(chunk, 0, chunk.length);
            }
            pos = end;
        }
        byte[] result = out.toByteArray();
        ByteBuffer.wrap(result, 4, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(result.length - 8);
        return result;
    }
}
