package com.example.city_fix.report;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "report_photos")
public class ReportPhoto {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "report_id", nullable = false, unique = true)
    private Report report;

    @Column(nullable = false)
    private String contentType;

    // Deliberately not @Lob: on PostgreSQL that maps byte[] to `oid` (a large-object
    // reference needing an active transaction to read), while a plain byte[] maps to
    // `bytea`, which is what this design stores.
    @Column(nullable = false)
    private byte[] imageData;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected ReportPhoto() {
    }

    public ReportPhoto(Report report, String contentType, byte[] imageData) {
        this.report = report;
        this.contentType = contentType;
        this.imageData = imageData;
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Report getReport() {
        return report;
    }

    public String getContentType() {
        return contentType;
    }

    // Returns the backing array rather than a copy: photos are capped at 2 MB and the
    // B1 instance runs with -Xmx1g, so duplicating the buffer on every read is a real
    // memory cost for no benefit — callers only stream these bytes to the response.
    public byte[] getImageData() {
        return imageData;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
