package com.example.city_fix.user;

import com.example.city_fix.auth.AuthService;
import jakarta.validation.Valid;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
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

    // Oldest first, with id as a tiebreak so the ordering is total — two accounts created in
    // the same millisecond must not be able to swap places between pages.
    private static final Sort ACCOUNT_SORT = Sort.by("createdAt").ascending().and(Sort.by("id").ascending());
    private static final int PAGE_SIZE = 25;

    @GetMapping
    public String accountList(@RequestParam(defaultValue = "0") int page, Model model) {
        Page<AccountRow> accounts =
            adminUserService.listManageableAccounts(PageRequest.of(Math.max(page, 0), PAGE_SIZE, ACCOUNT_SORT));

        model.addAttribute("accounts", accounts.getContent());
        model.addAttribute("page", accounts);
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

        User created;
        try {
            created = adminUserService.createStaff(createStaffForm.email(), createStaffForm.password());
        } catch (AuthService.EmailAlreadyExistsException e) {
            // Re-rendered rather than thrown: a taken address is ordinary user input, not a
            // server fault. The typed email is preserved by the bound form object.
            model.addAttribute("error", "That email address is already in use.");
            return "admin-user-new";
        }

        // Read the email off the persisted entity rather than re-normalising the form value:
        // a fourth copy of trim().toLowerCase() here could drift from the service that owns
        // the write, and would silently report an address different from the stored one.
        redirectAttributes.addFlashAttribute("accountMessage",
            "Staff account created for " + created.getEmail() + ".");
        return "redirect:/admin/users";
    }

    @PostMapping("/{id}/active")
    public String setActive(@PathVariable Long id,
                            @RequestParam boolean active,
                            RedirectAttributes redirectAttributes) {
        adminUserService.setActive(id, active);

        // Deliberately does not claim the open session is gone. Eviction is best-effort: the
        // session registry is in-memory and per-instance, and a login already in flight can
        // register after the sweep. That the account can no longer sign in is true under
        // every topology and both orderings, so that is what the admin is told.
        redirectAttributes.addFlashAttribute("accountMessage",
            active
                ? "Account reactivated."
                : "Account deactivated — they can no longer sign in.");
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
