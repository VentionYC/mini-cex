package dev.minicex.auth;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class AuthModels {

    private AuthModels() {}

    public record RegisterRequest(
            @NotBlank
            @Email
            @Size(max = 254)
            String email,

            @NotBlank
            @Size(min = 12, max = 72)
            String password
    ) {
        public RegisterRequest {
            if (email != null) {
                email = email.strip().toLowerCase(Locale.ROOT);
            }
        }

        @Override
        public String toString() {
            return "RegisterRequest[credentials redacted]";
        }
    }

    public record UserResponse(
            UUID id,
            String email,
            Instant createdAt
    ) {}
}