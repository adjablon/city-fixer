package com.example.city_fix.report;

import com.example.city_fix.user.Role;
import com.example.city_fix.user.User;
import com.example.city_fix.user.UserRepository;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReportServiceTest {

    private static final Long REPORTER_ID = 7L;
    private static final Long OTHER_REPORTER_ID = 99L;

    @Mock
    private ReportRepository reportRepository;

    @Mock
    private ReportPhotoRepository reportPhotoRepository;

    @Mock
    private PhotoValidator photoValidator;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private ReportService reportService;

    @Test
    void createWithoutPhoto_stampsNewStatusAndReporter() {
        User reporter = new User("resident@example.com", "encoded", Role.RESIDENT);
        when(userRepository.getReferenceById(REPORTER_ID)).thenReturn(reporter);
        when(reportRepository.save(any(Report.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Report created = reportService.create(52.1, 21.0, "Deep pothole", Category.POTHOLE, REPORTER_ID, null);

        assertThat(created.getStatus()).isEqualTo(ReportStatus.NEW);
        assertThat(created.getReporter()).isSameAs(reporter);
        assertThat(created.getLatitude()).isEqualTo(52.1);
        assertThat(created.getLongitude()).isEqualTo(21.0);
        assertThat(created.getCategory()).isEqualTo(Category.POTHOLE);
    }

    @Test
    void createWithoutPhoto_writesNoPhotoRow() {
        when(userRepository.getReferenceById(REPORTER_ID))
            .thenReturn(new User("resident@example.com", "encoded", Role.RESIDENT));
        when(reportRepository.save(any(Report.class))).thenAnswer(invocation -> invocation.getArgument(0));

        reportService.create(52.1, 21.0, "Broken light", Category.STREETLIGHT, REPORTER_ID, null);

        verifyNoInteractions(reportPhotoRepository);
    }

    @Test
    void createWithEmptyFilePart_treatsItAsNoPhoto() {
        when(userRepository.getReferenceById(REPORTER_ID))
            .thenReturn(new User("resident@example.com", "encoded", Role.RESIDENT));
        when(reportRepository.save(any(Report.class))).thenAnswer(invocation -> invocation.getArgument(0));
        MockMultipartFile emptyPart = new MockMultipartFile("photo", "", "application/octet-stream", new byte[0]);

        reportService.create(52.1, 21.0, "Graffiti", Category.GRAFFITI, REPORTER_ID, emptyPart);

        verifyNoInteractions(reportPhotoRepository);
        verifyNoInteractions(photoValidator);
    }

    @Test
    void createWithPhoto_storesValidatedContentType() {
        when(userRepository.getReferenceById(REPORTER_ID))
            .thenReturn(new User("resident@example.com", "encoded", Role.RESIDENT));
        when(reportRepository.save(any(Report.class))).thenAnswer(invocation -> invocation.getArgument(0));
        MockMultipartFile photo = new MockMultipartFile(
            "photo", "photo.jpg", "image/jpeg", "fake-image-bytes".getBytes(StandardCharsets.UTF_8));
        when(photoValidator.validate(photo)).thenReturn(
            new PhotoValidator.ValidatedPhoto("image/jpeg", "fake-image-bytes".getBytes(StandardCharsets.UTF_8)));

        reportService.create(52.1, 21.0, "Trash pile", Category.TRASH, REPORTER_ID, photo);

        ArgumentCaptor<ReportPhoto> savedPhoto = ArgumentCaptor.forClass(ReportPhoto.class);
        verify(reportPhotoRepository).save(savedPhoto.capture());
        assertThat(savedPhoto.getValue().getContentType()).isEqualTo("image/jpeg");
        assertThat(savedPhoto.getValue().getImageData()).isEqualTo("fake-image-bytes".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void createWithInvalidPhoto_persistsNoReport() {
        MockMultipartFile photo = new MockMultipartFile(
            "photo", "photo.jpg", "image/jpeg", "not-an-image".getBytes(StandardCharsets.UTF_8));
        when(photoValidator.validate(photo))
            .thenThrow(new ReportService.InvalidPhotoException("Photo must be a JPEG, PNG or WebP image."));

        assertThatThrownBy(() -> reportService.create(52.1, 21.0, "Sign down", Category.SIGN, REPORTER_ID, photo))
            .isInstanceOf(ReportService.InvalidPhotoException.class);

        verify(reportRepository, never()).save(any(Report.class));
        verifyNoInteractions(reportPhotoRepository);
    }

    @Test
    void listOwn_returnsOnlyTheReportersOwnReportsNewestFirst() {
        List<Report> own = List.of(reportOf(new User("resident@example.com", "encoded", Role.RESIDENT)));
        when(reportRepository.findByReporterIdOrderByCreatedAtDesc(REPORTER_ID)).thenReturn(own);

        assertThat(reportService.listOwn(REPORTER_ID)).isEqualTo(own);
    }

    @Test
    void getOwnForForeignReporter_throwsReportNotFound() {
        when(reportRepository.findByIdAndReporterId(1L, OTHER_REPORTER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> reportService.getOwn(1L, OTHER_REPORTER_ID))
            .isInstanceOf(ReportService.ReportNotFoundException.class);
    }

    @Test
    void getOwnPhotoForForeignReporter_throwsBeforeTouchingThePhotoRepository() {
        when(reportRepository.findByIdAndReporterId(1L, OTHER_REPORTER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> reportService.getOwnPhoto(1L, OTHER_REPORTER_ID))
            .isInstanceOf(ReportService.ReportNotFoundException.class);

        verifyNoInteractions(reportPhotoRepository);
    }

    @Test
    void hasPhotoForForeignReporter_throwsBeforeTouchingThePhotoRepository() {
        when(reportRepository.findByIdAndReporterId(1L, OTHER_REPORTER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> reportService.hasPhoto(1L, OTHER_REPORTER_ID))
            .isInstanceOf(ReportService.ReportNotFoundException.class);

        verifyNoInteractions(reportPhotoRepository);
    }

    @Test
    void getOwnPhotoWhenReportHasNoPhoto_throwsReportNotFound() {
        Report report = reportOf(new User("resident@example.com", "encoded", Role.RESIDENT));
        when(reportRepository.findByIdAndReporterId(1L, REPORTER_ID)).thenReturn(Optional.of(report));
        when(reportPhotoRepository.findByReportId(report.getId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> reportService.getOwnPhoto(1L, REPORTER_ID))
            .isInstanceOf(ReportService.ReportNotFoundException.class);
    }

    private static Report reportOf(User reporter) {
        return new Report(52.1, 21.0, "Deep pothole", Category.POTHOLE, reporter);
    }
}
