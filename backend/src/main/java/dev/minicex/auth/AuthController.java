package dev.minicex.auth;

import jakarta.validation.Valid;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import dev.minicex.auth.AuthModels.AuthenticatedUser;
import dev.minicex.auth.AuthModels.LoginRequest;
import dev.minicex.auth.AuthModels.LoginResponse;
import dev.minicex.auth.AuthModels.RegisterRequest;
import dev.minicex.auth.AuthModels.UserResponse;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService service;

    public AuthController(AuthService service) {
        this.service = service;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse register(
            @Valid @RequestBody RegisterRequest request) {

        return service.register(request);
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(
            @Valid @RequestBody LoginRequest request) {

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(service.login(request));
    }

    @GetMapping("/me")
    public UserResponse me(
            @AuthenticationPrincipal AuthenticatedUser principal) {

        return principal.user();
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(
            @AuthenticationPrincipal AuthenticatedUser principal) {

        service.logout(principal);
    }
}