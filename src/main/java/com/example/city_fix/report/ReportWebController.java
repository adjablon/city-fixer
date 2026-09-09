package com.example.city_fix.report;

import com.example.city_fix.auth.CustomUserDetails;
import jakarta.validation.Valid;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

@Controller
public class ReportWebController {

    private static final Logger log = LoggerFactory.getLogger(ReportWebController.class);

    // Thymeleaf has no #temporals dialect on this classpath, so the view is handed a
    // zone-bound formatter rather than formatting the Instant itself.
    private static final DateTimeFormatter CREATED_AT_FORMAT =
        DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.ENGLISH).withZone(ZoneId.systemDefault());

    private final ReportService reportService;
    private final double defaultLatitude;
    private final double defaultLongitude;
    private final int defaultZoom;

    public ReportWebController(ReportService reportService,
                               @Value("${cityfix.map.default-lat}") double defaultLatitude,
                               @Value("${cityfix.map.default-lng}") double defaultLongitude,
                               @Value("${cityfix.map.default-zoom}") int defaultZoom) {
        this.reportService = reportService;
        this.defaultLatitude = defaultLatitude;
        this.defaultLongitude = defaultLongitude;
        this.defaultZoom = defaultZoom;
    }

    // Reporting is a resident capability (PRD Access Control); staff and admin have no
    // requirement to file reports. The project's first method-security rule.
    @PreAuthorize("hasRole('RESIDENT')")
    @GetMapping("/reports/new")
    public String newReportPage(Model model) {
        model.addAttribute("reportForm", new ReportForm(null, null, null, null));
        addFormModel(model);
        return "report-new";
    }

    @PreAuthorize("hasRole('RESIDENT')")
    @PostMapping("/reports")
    public String createReport(@Valid @ModelAttribute("reportForm") ReportForm reportForm,
                               BindingResult bindingResult,
                               @RequestParam(name = "photo", required = false) MultipartFile photo,
                               @AuthenticationPrincipal CustomUserDetails principal,
                               Model model) {
        if (bindingResult.hasErrors()) {
            model.addAttribute("error", firstErrorMessage(bindingResult));
            addFormModel(model);
            return "report-new";
        }

        try {
            reportService.create(
                reportForm.latitude(),
                reportForm.longitude(),
                reportForm.description(),
                reportForm.category(),
                principal.getId(),
                photo
            );
        } catch (ReportService.InvalidPhotoException e) {
            // The photo failed validation: the message describes what the user must change,
            // and there is nothing here for an operator to act on.
            model.addAttribute("error", e.getMessage());
            addFormModel(model);
            return "report-new";
        } catch (ReportService.PhotoUnreadableException e) {
            // Infrastructure failure, not user error — the upload may be a perfectly good
            // photo the server could not read. Log it, and keep the user-facing text generic
            // rather than telling the reporter their file is invalid.
            log.warn("Could not read uploaded photo for reporter {}", principal.getId(), e);
            model.addAttribute("error", "We could not read that photo. Please try again.");
            addFormModel(model);
            return "report-new";
        }

        return "redirect:/reports";
    }

    @GetMapping("/reports")
    public String listReports(@AuthenticationPrincipal CustomUserDetails principal, Model model) {
        model.addAttribute("reports", reportService.listOwn(principal.getId()));
        model.addAttribute("dateFormatter", CREATED_AT_FORMAT);
        return "report-list";
    }

    @GetMapping("/reports/{id}")
    public String reportDetail(@PathVariable Long id,
                               @AuthenticationPrincipal CustomUserDetails principal,
                               Model model) {
        Report report = reportService.getOwn(id, principal.getId());
        model.addAttribute("report", report);
        model.addAttribute("hasPhoto", reportService.hasPhoto(report.getId(), principal.getId()));
        model.addAttribute("dateFormatter", CREATED_AT_FORMAT);
        return "report-detail";
    }

    @GetMapping("/reports/{id}/photo")
    public ResponseEntity<byte[]> reportPhoto(@PathVariable Long id,
                                              @AuthenticationPrincipal CustomUserDetails principal) {
        ReportPhoto photo = reportService.getOwnPhoto(id, principal.getId());
        // The content type comes from PhotoValidator's allowlist, never from the client,
        // and no client-supplied filename is echoed into the response.
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(photo.getContentType()))
            .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
            .body(photo.getImageData());
    }

    @ExceptionHandler(ReportService.ReportNotFoundException.class)
    public ResponseEntity<Void> handleReportNotFound() {
        // 404 rather than 403: another user's report must be indistinguishable from
        // one that does not exist.
        return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }

    private void addFormModel(Model model) {
        model.addAttribute("categories", Category.values());
        model.addAttribute("defaultLat", defaultLatitude);
        model.addAttribute("defaultLng", defaultLongitude);
        model.addAttribute("defaultZoom", defaultZoom);
    }

    private static String firstErrorMessage(BindingResult bindingResult) {
        return bindingResult.getFieldErrors().stream()
            .findFirst()
            .map(fieldError -> fieldError.isBindingFailure()
                ? "The submitted " + fieldError.getField() + " value is not valid."
                : fieldError.getDefaultMessage())
            .orElse("Please correct the highlighted fields.");
    }
}
