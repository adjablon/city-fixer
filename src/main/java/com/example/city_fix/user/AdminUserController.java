package com.example.city_fix.user;

import com.example.city_fix.auth.AuthService;
import jakarta.validation.Valid;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Admin account management. Authorization is the {@code /admin/**} matcher in SecurityConfig,
 * not an annotation here, so every route added to this controller is gated by construction.
 */
@Controller
@RequestMapping("/admin/users")
public class AdminUserController {

    // thymeleaf-extras-java8time is not on the classpath, so the view is handed a formatter.
    private static final DateTimeFormatter CREATED_AT_FORMAT =
        DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH).withZone(ZoneId.systemDefault());

    private final AdminUserService adminUserService;

    public AdminUserController(AdminUserService adminUserService) {
        this.adminUserService = adminUserService;
    }

    @GetMapping
    public String accountList(Model model) {
        model.addAttribute("accounts", adminUserService.listManageableAccounts());
        model.addAttribute("dateFormatter", CREATED_AT_FORMAT);
        return "admin-users";
    }

    @GetMapping("/new")
    public String createStaffForm(Model model) {
        model.addAttribute("createStaffForm", new CreateStaffForm());
        return "admin-user-new";
    }

    @PostMapping
    public String createStaff(@Valid @ModelAttribute("createStaffForm") CreateStaffForm createStaffForm,
                              BindingResult bindingResult,
                              RedirectAttributes redirectAttributes,
                              Model model) {
        if (bindingResult.hasErrors()) {
            return "admin-user-new";
        }

        try {
            adminUserService.createStaff(createStaffForm.email(), createStaffForm.password());
        } catch (AuthService.EmailAlreadyExistsException e) {
            // Re-rendered rather than thrown: a taken address is ordinary user input, not a
            // server fault. The typed email is preserved by the bound form object.
            model.addAttribute("error", "That email address is already in use.");
            return "admin-user-new";
        }

        redirectAttributes.addFlashAttribute("accountMessage",
            "Staff account created for " + createStaffForm.email().trim().toLowerCase(Locale.ROOT) + ".");
        return "redirect:/admin/users";
    }

    @PostMapping("/{id}/active")
    public String setActive(@PathVariable Long id,
                            @RequestParam boolean active,
                            RedirectAttributes redirectAttributes) {
        adminUserService.setActive(id, active);

        redirectAttributes.addFlashAttribute("accountMessage",
            active
                ? "Account reactivated."
                : "Account deactivated — any open session has been ended.");
        return "redirect:/admin/users";
    }

    @ExceptionHandler(AdminUserService.UserNotFoundException.class)
    public ResponseEntity<Void> handleUserNotFound() {
        // Controller-scoped, following AuthController and StaffReportController — the
        // project has no @ControllerAdvice.
        return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }

    @ExceptionHandler(AdminUserService.AdminAccountNotManageableException.class)
    public ResponseEntity<Void> handleAdminTarget() {
        // Admin rows are never listed, so reaching this means a hand-crafted request.
        return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
    }
}
