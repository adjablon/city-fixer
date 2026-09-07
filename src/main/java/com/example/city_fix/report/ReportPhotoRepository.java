package com.example.city_fix.report;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReportPhotoRepository extends JpaRepository<ReportPhoto, Long> {

    Optional<ReportPhoto> findByReportId(Long reportId);

    boolean existsByReportId(Long reportId);
}
