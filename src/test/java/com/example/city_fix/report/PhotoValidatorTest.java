package com.example.city_fix.report;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PhotoValidatorTest {

    private final PhotoValidator photoValidator = new PhotoValidator();

    @Test
    void jpegSignature_isAcceptedAsJpeg() {
        MultipartFile photo = photoWithBytes(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0x00, 0x10});

        assertThat(photoValidator.validate(photo).contentType()).isEqualTo("image/jpeg");
    }

    @Test
    void pngSignature_isAcceptedAsPng() {
        MultipartFile photo = photoWithBytes(
            new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00});

        assertThat(photoValidator.validate(photo).contentType()).isEqualTo("image/png");
    }

    @Test
    void webpSignature_isAcceptedAsWebp() {
        byte[] content = new byte[16];
        System.arraycopy("RIFF".getBytes(StandardCharsets.US_ASCII), 0, content, 0, 4);
        System.arraycopy("WEBP".getBytes(StandardCharsets.US_ASCII), 0, content, 8, 4);

        assertThat(photoValidator.validate(photoWithBytes(content)).contentType()).isEqualTo("image/webp");
    }

    @Test
    void riffWithoutWebpMarker_isRejected() {
        byte[] content = new byte[16];
        System.arraycopy("RIFF".getBytes(StandardCharsets.US_ASCII), 0, content, 0, 4);
        System.arraycopy("AVI ".getBytes(StandardCharsets.US_ASCII), 0, content, 8, 4);

        assertThatThrownBy(() -> photoValidator.validate(photoWithBytes(content)))
            .isInstanceOf(ReportService.InvalidPhotoException.class);
    }

    @Test
    void executableRenamedToJpg_isRejected() {
        MockMultipartFile photo = new MockMultipartFile(
            "photo", "photo.jpg", "image/jpeg", "#!/bin/sh\nrm -rf /".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> photoValidator.validate(photo))
            .isInstanceOf(ReportService.InvalidPhotoException.class)
            .hasMessageContaining("JPEG, PNG or WebP");
    }

    @Test
    void emptyFile_isRejected() {
        MockMultipartFile photo = new MockMultipartFile("photo", "empty.jpg", "image/jpeg", new byte[0]);

        assertThatThrownBy(() -> photoValidator.validate(photo))
            .isInstanceOf(ReportService.InvalidPhotoException.class);
    }

    @Test
    void nullFile_isRejected() {
        assertThatThrownBy(() -> photoValidator.validate(null))
            .isInstanceOf(ReportService.InvalidPhotoException.class);
    }

    @Test
    void oversizeFile_isRejectedWithoutReadingBytesOntoTheHeap() throws Exception {
        MultipartFile photo = mock(MultipartFile.class);
        when(photo.isEmpty()).thenReturn(false);
        when(photo.getSize()).thenReturn(3L * 1024 * 1024);

        assertThatThrownBy(() -> photoValidator.validate(photo))
            .isInstanceOf(ReportService.InvalidPhotoException.class)
            .hasMessageContaining("2 MB");

        verify(photo, never()).getBytes();
    }

    @Test
    void fileExactlyAtTheLimit_isAccepted() {
        byte[] content = new byte[2 * 1024 * 1024];
        content[0] = (byte) 0xFF;
        content[1] = (byte) 0xD8;
        content[2] = (byte) 0xFF;

        assertThat(photoValidator.validate(photoWithBytes(content)).contentType()).isEqualTo("image/jpeg");
    }

    private static MockMultipartFile photoWithBytes(byte[] content) {
        return new MockMultipartFile("photo", "photo.bin", "application/octet-stream", content);
    }
}
