package com.example.city_fix.report;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Staff triage surface. Authorization is the {@code /staff/**} matcher in SecurityConfig, not
 * an annotation here, so every route added to this controller is gated by construction.
 */
@Controller
@RequestMapping("/staff/reports")
public class StaffReportController {

    private static final Logger log = LoggerFactory.getLogger(StaffReportController.class);

    // thymeleaf-extras-java8time is not on the classpath, so the view is handed a formatter.
    private static final DateTimeFormatter CREATED_AT_FORMAT =
        DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.ENGLISH).withZone(ZoneId.systemDefault());

    private final StaffReportService staffReportService;
    private final ObjectMapper objectMapper;
    private final double defaultLatitude;
    private final double defaultLongitude;
    private final int defaultZoom;

    public StaffReportController(StaffReportService staffReportService,
                                 ObjectMapper objectMapper,
                                 @Value("${cityfix.map.default-lat}") double defaultLatitude,
                                 @Value("${cityfix.map.default-lng}") double defaultLongitude,
                                 @Value("${cityfix.map.default-zoom}") int defaultZoom) {
        this.staffReportService = staffReportService;
        this.objectMapper = objectMapper;
        this.defaultLatitude = defaultLatitude;
        this.defaultLongitude = defaultLongitude;
        this.defaultZoom = defaultZoom;
    }

    @GetMapping
    public String reportMap(Model model) {
        List<ReportPin> pins = staffReportService.listAll().stream().map(StaffReportController::toPin).toList();

        model.addAttribute("reportsJson", writePins(pins));
        model.addAttribute("reportCount", pins.size());
        model.addAttribute("defaultLat", defaultLatitude);
        model.addAttribute("defaultLng", defaultLongitude);
        model.addAttribute("defaultZoom", defaultZoom);
        return "staff-report-map";
    }

    private static ReportPin toPin(Report report) {
        return new ReportPin(
            report.getId(),
            report.getLatitude(),
            report.getLongitude(),
            report.getStatus().name(),
            report.getStatus().getLabel(),
            report.getCategory().getLabel(),
            CREATED_AT_FORMAT.format(report.getCreatedAt()));
    }

    private String writePins(List<ReportPin> pins) {
        try {
            return objectMapper.writeValueAsString(pins);
        } catch (JacksonException e) {
            // Jackson 3 makes this unchecked, so the catch exists only to log: serializing our
            // own records cannot fail on user input, and a broken page must not be rendered.
            log.warn("Could not serialize {} report pins for the staff map", pins.size(), e);
            throw e;
        }
    }
}
