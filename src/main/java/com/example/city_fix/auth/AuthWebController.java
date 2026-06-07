package com.example.city_fix.auth;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class AuthWebController {

    private final AuthService authService;

    public AuthWebController(AuthService authService) {
        this.authService = authService;
    }

    @GetMapping("/login")
    public String loginPage(@RequestParam(required = false) String error,
                            @RequestParam(required = false) String logout,
                            @RequestParam(required = false) String registered,
                            Model model) {
        if (error != null) {
            model.addAttribute("error", "Invalid email or password.");
        }
        if (logout != null) {
            model.addAttribute("message", "You have been logged out.");
        }
        if (registered != null) {
            model.addAttribute("message", "Registration successful! Please log in.");
        }
        return "login";
    }

    @GetMapping("/register")
    public String registerPage() {
        return "register";
    }

    @PostMapping("/register")
    public String register(@RequestParam String email,
                           @RequestParam String password,
                           Model model) {
        try {
            authService.register(email, password);
            return "redirect:/login?registered";
        } catch (IllegalArgumentException | AuthService.EmailAlreadyExistsException e) {
            model.addAttribute("error", e.getMessage());
            model.addAttribute("email", email);
            return "register";
        }
    }
}