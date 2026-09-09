package com.example.city_fix.report;

import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Every read that crosses the report-ownership boundary lives here rather than on
 * {@link ReportService}, whose finders stay reporter-scoped by construction. Access is gated
 * at the path level for {@code /staff/**}; nothing in this class filters by reporter.
 */
@Service
public class StaffReportService {

    private final ReportRepository reportRepository;
    private final ReportPhotoRepository reportPhotoRepository;

    public StaffReportService(ReportRepository reportRepository,
                              ReportPhotoRepository reportPhotoRepository) {
        this.reportRepository = reportRepository;
        this.reportPhotoRepository = reportPhotoRepository;
    }

    public List<Report> listAll() {
        return reportRepository.findAllByOrderByCreatedAtDesc();
    }

    public Report get(Long id) {
        return reportRepository.findById(id)
            .orElseThrow(() -> new ReportService.ReportNotFoundException(id));
    }

    public Report getWithReporter(Long id) {
        return reportRepository.findWithReporterById(id)
            .orElseThrow(() -> new ReportService.ReportNotFoundException(id));
    }

    public boolean hasPhoto(Long reportId) {
        return reportPhotoRepository.existsByReportId(reportId);
    }

    public ReportPhoto getPhoto(Long reportId) {
        return reportPhotoRepository.findByReportId(reportId)
            .orElseThrow(() -> new ReportService.ReportNotFoundException(reportId));
    }

    // Transactional because this is a read-modify-write: the loaded report stays managed so
    // the status change is flushed by dirty checking rather than an explicit save.
    @Transactional
    public StatusChangeResult changeStatus(Long reportId, ReportStatus newStatus) {
        Report report = get(reportId);
        if (report.getStatus() == newStatus) {
            // Transitions are unrestricted, so the form can post the status the report already
            // has. Not writing keeps statusUpdatedAt meaningful.
            return StatusChangeResult.UNCHANGED;
        }
        report.changeStatus(newStatus);
        return StatusChangeResult.CHANGED;
    }

    public enum StatusChangeResult {
        CHANGED,
        UNCHANGED
    }
}
