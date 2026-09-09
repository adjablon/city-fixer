package com.example.city_fix.report;

import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

@Component
public class PhotoValidator {

    private static final long MAX_PHOTO_BYTES = 2L * 1024 * 1024;
    private static final String WEBP_CONTENT_TYPE = "image/webp";

    private static final byte[] JPEG_SIGNATURE = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
    private static final byte[] WEBP_RIFF_SIGNATURE = {0x52, 0x49, 0x46, 0x46};
    private static final byte[] WEBP_FORMAT_SIGNATURE = {0x57, 0x45, 0x42, 0x50};
    private static final int WEBP_FORMAT_OFFSET = 8;

    public ValidatedPhoto validate(MultipartFile photo) {
        if (photo == null || photo.isEmpty()) {
            throw new ReportService.InvalidPhotoException("Please choose a photo file.");
        }
        // Size is checked before the bytes are read so an oversize part is never copied
        // onto the heap — the container spools it to temp disk instead.
        if (photo.getSize() > MAX_PHOTO_BYTES) {
            throw new ReportService.InvalidPhotoException("Photo must be 2 MB or smaller.");
        }

        // The bytes are read once, here, and handed back to the caller: re-reading the
        // spooled part to persist it would double the transient heap per upload and leave
        // room for the validated bytes and the stored bytes to differ.
        byte[] content = readBytes(photo);
        if (matchesAt(content, 0, JPEG_SIGNATURE)) {
            return new ValidatedPhoto(MediaType.IMAGE_JPEG_VALUE, content);
        }
        if (matchesAt(content, 0, PNG_SIGNATURE)) {
            return new ValidatedPhoto(MediaType.IMAGE_PNG_VALUE, content);
        }
        if (matchesAt(content, 0, WEBP_RIFF_SIGNATURE)
            && matchesAt(content, WEBP_FORMAT_OFFSET, WEBP_FORMAT_SIGNATURE)) {
            return new ValidatedPhoto(WEBP_CONTENT_TYPE, content);
        }
        throw new ReportService.InvalidPhotoException("Photo must be a JPEG, PNG or WebP image.");
    }

    private static byte[] readBytes(MultipartFile photo) {
        try {
            return photo.getBytes();
        } catch (IOException e) {
            throw new ReportService.PhotoUnreadableException("Could not read the uploaded photo", e);
        }
    }

    private static boolean matchesAt(byte[] content, int offset, byte[] signature) {
        if (content.length < offset + signature.length) {
            return false;
        }
        for (int i = 0; i < signature.length; i++) {
            if (content[offset + i] != signature[i]) {
                return false;
            }
        }
        return true;
    }

    public record ValidatedPhoto(String contentType, byte[] data) {
    }
}
