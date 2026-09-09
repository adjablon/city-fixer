package com.example.city_fix.report;

import com.example.city_fix.user.Role;
import com.example.city_fix.user.User;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StaffReportServiceTest {

    private static final Long REPORT_ID = 42L;

    @Mock
    private ReportRepository reportRepository;

    @Mock
    private ReportPhotoRepository reportPhotoRepository;

    @InjectMocks
    private StaffReportService staffReportService;

    @Test
    void listAll_readsEveryReportNewestFirstWithNoReporterFilter() {
        List<Report> all = List.of(reportOf(resident("a@example.com")), reportOf(resident("b@example.com")));
        when(reportRepository.findAllByOrderByCreatedAtDesc()).thenReturn(all);

        assertThat(staffReportService.listAll()).isEqualTo(all);
    }

    @Test
    void get_throwsWhenAbsent() {
        when(reportRepository.findById(REPORT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> staffReportService.get(REPORT_ID))
            .isInstanceOf(ReportService.ReportNotFoundException.class);
    }

    @Test
    void getWithReporter_usesTheFetchJoiningFinder() {
        Report report = reportOf(resident("owner@example.com"));
        when(reportRepository.findWithReporterById(REPORT_ID)).thenReturn(Optional.of(report));

        assertThat(staffReportService.getWithReporter(REPORT_ID)).isSameAs(report);
    }

    @Test
    void getWithReporter_throwsWhenAbsent() {
        when(reportRepository.findWithReporterById(REPORT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> staffReportService.getWithReporter(REPORT_ID))
            .isInstanceOf(ReportService.ReportNotFoundException.class);
    }

    @Test
    void changeStatus_toADifferentStatusMutatesTheManagedEntity() {
        Report report = reportOf(resident("owner@example.com"));
        when(reportRepository.findById(REPORT_ID)).thenReturn(Optional.of(report));

        StaffReportService.StatusChangeResult result =
            staffReportService.changeStatus(REPORT_ID, ReportStatus.IN_PROGRESS);

        assertThat(result).isEqualTo(StaffReportService.StatusChangeResult.CHANGED);
        assertThat(report.getStatus()).isEqualTo(ReportStatus.IN_PROGRESS);
        assertThat(report.getStatusUpdatedAt()).isNotNull();
        // The write is a dirty-check flush inside the transaction, not an explicit save.
        verify(reportRepository, never()).save(any());
    }

    @Test
    void changeStatus_toTheCurrentStatusWritesNothing() {
        Report report = reportOf(resident("owner@example.com"));
        when(reportRepository.findById(REPORT_ID)).thenReturn(Optional.of(report));

        StaffReportService.StatusChangeResult result =
            staffReportService.changeStatus(REPORT_ID, ReportStatus.NEW);

        assertThat(result).isEqualTo(StaffReportService.StatusChangeResult.UNCHANGED);
        assertThat(report.getStatus()).isEqualTo(ReportStatus.NEW);
        assertThat(report.getStatusUpdatedAt()).isNull();
    }

    @Test
    void changeStatus_throwsWhenTheReportIsMissing() {
        when(reportRepository.findById(REPORT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> staffReportService.changeStatus(REPORT_ID, ReportStatus.RESOLVED))
            .isInstanceOf(ReportService.ReportNotFoundException.class);
    }

    @Test
    void photoReads_resolveByReportIdAloneWithNoReporterArgument() {
        Report report = reportOf(resident("owner@example.com"));
        ReportPhoto photo = new ReportPhoto(report, "image/jpeg", new byte[] {1, 2, 3});
        when(reportPhotoRepository.existsByReportId(REPORT_ID)).thenReturn(true);
        when(reportPhotoRepository.findByReportId(REPORT_ID)).thenReturn(Optional.of(photo));

        assertThat(staffReportService.hasPhoto(REPORT_ID)).isTrue();
        assertThat(staffReportService.getPhoto(REPORT_ID)).isSameAs(photo);
    }

    @Test
    void getPhoto_throwsWhenTheReportHasNoPhoto() {
        when(reportPhotoRepository.findByReportId(REPORT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> staffReportService.getPhoto(REPORT_ID))
            .isInstanceOf(ReportService.ReportNotFoundException.class);
    }

    private static User resident(String email) {
        return new User(email, "encoded", Role.RESIDENT);
    }

    private static Report reportOf(User reporter) {
        return new Report(52.1, 21.0, "Deep pothole", Category.POTHOLE, reporter);
    }
}
