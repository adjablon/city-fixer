package com.example.city_fix.auth;

import com.example.city_fix.user.User;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final AuthService authService;
    private final SessionRegistry sessionRegistry;

    public AuthController(AuthenticationManager authenticationManager,
                          AuthService authService,
                          SessionRegistry sessionRegistry) {
        this.authenticationManager = authenticationManager;
        this.authService = authService;
        this.sessionRegistry = sessionRegistry;
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest credentials, HttpServletRequest request) {
        try {
            Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(
                    credentials.email(),
                    credentials.password()
                )
            );

            // Rotate the session id on login — session-fixation protection that
            // filter-based formLogin applies automatically but manual login does not.
            request.getSession(true);
            request.changeSessionId();

            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            SecurityContextHolder.setContext(context);
            request.getSession()
                .setAttribute(
                    HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                    context
                );

            // Stands in for RegisterSessionAuthenticationStrategy, which the formLogin
            // filter runs but manual authentication does not. Without it the registry never
            // learns about API sessions and deactivation cannot evict them. Must come after
            // changeSessionId() — registering first would record the pre-rotation id.
            CustomUserDetails user = (CustomUserDetails) authentication.getPrincipal();
            sessionRegistry.registerNewSession(request.getSession().getId(), user);

            return ResponseEntity.ok(UserResponse.from(user));
        } catch (AuthenticationException e) {
            // Catches DisabledException (deactivated account) alongside BadCredentialsException.
            // The body is deliberately the same for both: a distinct "account deactivated"
            // message would turn this endpoint into an oracle for which addresses exist.
            return ResponseEntity.status(401).body(Map.of(
                "message", "Invalid email or password"
            ));
        }
    }

    @PostMapping("/register")
    public ResponseEntity<?> register(@Valid @RequestBody RegisterRequest body) {
        try {
            User user = authService.register(body.email(), body.password());
            return ResponseEntity.status(HttpStatus.CREATED).body(UserResponse.from(user));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (AuthService.EmailAlreadyExistsException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", e.getMessage()));
        }
    }

    @GetMapping("/me")
    public ResponseEntity<?> me(@AuthenticationPrincipal CustomUserDetails user) {
        return ResponseEntity.ok(UserResponse.from(user));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<?> handleValidationErrors(MethodArgumentNotValidException e) {
        // Preserve the {"message": ...} error contract the API established
        // before Bean Validation was introduced.
        String message = e.getBindingResult().getFieldErrors().stream()
            .findFirst()
            .map(error -> error.getDefaultMessage())
            .orElse("Validation failed");
        return ResponseEntity.badRequest().body(Map.of("message", message));
    }
}
