package com.example.city_fix.report;

import com.example.city_fix.auth.CustomUserDetails;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

@Controller
public class ReportWebController {

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

    @GetMapping("/reports/new")
    public String newReportPage(Model model) {
        model.addAttribute("reportForm", new ReportForm(null, null, null, null));
        addFormModel(model);
        return "report-new";
    }

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
            model.addAttribute("error", e.getMessage());
            addFormModel(model);
            return "report-new";
        }

        return "redirect:/reports";
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
