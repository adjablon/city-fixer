package com.example.city_fix.report;

import com.example.city_fix.user.User;
import com.example.city_fix.user.UserRepository;
import java.io.IOException;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class ReportService {

    private final ReportRepository reportRepository;
    private final ReportPhotoRepository reportPhotoRepository;
    private final PhotoValidator photoValidator;
    private final UserRepository userRepository;

    public ReportService(ReportRepository reportRepository,
                         ReportPhotoRepository reportPhotoRepository,
                         PhotoValidator photoValidator,
                         UserRepository userRepository) {
        this.reportRepository = reportRepository;
        this.reportPhotoRepository = reportPhotoRepository;
        this.photoValidator = photoValidator;
        this.userRepository = userRepository;
    }

    // Transactional because this writes two rows: an invalid or unreadable photo must
    // not leave a report behind without it.
    @Transactional
    public Report create(double latitude,
                         double longitude,
                         String description,
                         Category category,
                         Long reporterId,
                         MultipartFile photo) {
        boolean hasPhoto = photo != null && !photo.isEmpty();
        String contentType = hasPhoto ? photoValidator.validateAndDetectContentType(photo) : null;

        User reporter = userRepository.getReferenceById(reporterId);
        Report saved = reportRepository.save(new Report(latitude, longitude, description, category, reporter));

        if (hasPhoto) {
            reportPhotoRepository.save(new ReportPhoto(saved, contentType, readBytes(photo)));
        }
        return saved;
    }

    public List<Report> listOwn(Long reporterId) {
        return reportRepository.findByReporterIdOrderByCreatedAtDesc(reporterId);
    }

    public Report getOwn(Long id, Long reporterId) {
        return reportRepository.findByIdAndReporterId(id, reporterId)
            .orElseThrow(() -> new ReportNotFoundException(id));
    }

    public ReportPhoto getOwnPhoto(Long reportId, Long reporterId) {
        // Ownership is resolved before the photo is loaded: a foreign report must never
        // reach the byte-loading query.
        Report report = getOwn(reportId, reporterId);
        return reportPhotoRepository.findByReportId(report.getId())
            .orElseThrow(() -> new ReportNotFoundException(reportId));
    }

    public boolean hasPhoto(Long reportId) {
        return reportPhotoRepository.existsByReportId(reportId);
    }

    private static byte[] readBytes(MultipartFile photo) {
        try {
            return photo.getBytes();
        } catch (IOException e) {
            throw new InvalidPhotoException("Photo could not be read", e);
        }
    }

    public static class ReportNotFoundException extends RuntimeException {
        public ReportNotFoundException(Long id) {
            super("Report not found: " + id);
        }
    }

    public static class InvalidPhotoException extends RuntimeException {
        public InvalidPhotoException(String message) {
            super(message);
        }

        public InvalidPhotoException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
