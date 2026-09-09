package com.example.city_fix.report;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReportRepository extends JpaRepository<Report, Long> {

    List<Report> findByReporterIdOrderByCreatedAtDesc(Long reporterId);

    Optional<Report> findByIdAndReporterId(Long id, Long reporterId);

    // Staff-only reads. Deliberately separate from the reporter-scoped finders above:
    // ownership stays a property of those queries, and nothing here relaxes them.
    List<Report> findAllByOrderByCreatedAtDesc();

    // The staff detail page renders the reporter's email, and Report.reporter is LAZY.
    @EntityGraph(attributePaths = "reporter")
    Optional<Report> findWithReporterById(Long id);
}
