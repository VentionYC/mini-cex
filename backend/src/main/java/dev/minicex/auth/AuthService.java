package dev.minicex.auth;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import dev.minicex.auth.AuthModels.RegisterRequest;
import dev.minicex.auth.AuthModels.UserResponse;
import dev.minicex.common.ApiException;

@Service
public class AuthService {

    private final AuthRepository repository;
    private final PasswordEncoder passwordEncoder;

    public AuthService(
            AuthRepository repository,
            PasswordEncoder passwordEncoder) {

        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
    }

    public UserResponse register(RegisterRequest request) {
        int passwordBytes = request.password()
                .getBytes(StandardCharsets.UTF_8)
                .length;

        if (passwordBytes > 72) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_PASSWORD",
                    "Password must not exceed 72 UTF-8 bytes");
        }

        String passwordHash = passwordEncoder.encode(request.password());

        return repository.createUser(
                        UUID.randomUUID(),
                        request.email(),
                        passwordHash)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.CONFLICT,
                        "EMAIL_ALREADY_REGISTERED",
                        "An account with this email already exists"));
    }
}