package dev.minicex.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import dev.minicex.auth.AuthModels.AuthenticatedUser;
import dev.minicex.auth.AuthModels.LoginRequest;
import dev.minicex.auth.AuthModels.LoginResponse;
import dev.minicex.auth.AuthModels.RegisterRequest;
import dev.minicex.auth.AuthModels.UserResponse;
import dev.minicex.common.ApiException;

@Service
public class AuthService {

    private static final Pattern TOKEN_FORMAT =
            Pattern.compile("[A-Za-z0-9_-]{43}");

    private final AuthRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final SecureRandom random = new SecureRandom();
    private final String dummyPasswordHash;

    public AuthService(
            AuthRepository repository,
            PasswordEncoder passwordEncoder) {

        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
        this.dummyPasswordHash =
                passwordEncoder.encode(UUID.randomUUID().toString());
    }

    public UserResponse register(RegisterRequest request) {
        if (passwordBytes(request.password()) > 72) {
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

    public LoginResponse login(LoginRequest request) {
        if (passwordBytes(request.password()) > 72) {
            throw invalidCredentials();
        }

        var account = repository.findLoginUser(request.email());

        String expectedHash = account
                .map(AuthRepository.LoginUser::passwordHash)
                .orElse(dummyPasswordHash);

        boolean passwordMatches =
                passwordEncoder.matches(request.password(), expectedHash);

        if (account.isEmpty() || !passwordMatches) {
            throw invalidCredentials();
        }

        byte[] randomBytes = new byte[32];
        random.nextBytes(randomBytes);

        String token = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(randomBytes);

        UserResponse user = account.orElseThrow().user();

        Instant expiresAt = repository.createSession(
                user.id(),
                hashToken(token));

        return new LoginResponse(token, "Bearer", expiresAt, user);
    }

    public Optional<AuthenticatedUser> authenticate(String token) {
        if (!TOKEN_FORMAT.matcher(token).matches()) {
            return Optional.empty();
        }

        return repository.findActiveSession(hashToken(token));
    }

    public void logout(AuthenticatedUser authenticatedUser) {
        repository.revokeSession(
                authenticatedUser.user().id(),
                authenticatedUser.tokenHash());
    }

    private static int passwordBytes(String password) {
        return password.getBytes(StandardCharsets.UTF_8).length;
    }

    private static String hashToken(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));

            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "SHA-256 is unavailable", exception);
        }
    }

    private static ApiException invalidCredentials() {
        return new ApiException(
                HttpStatus.UNAUTHORIZED,
                "INVALID_CREDENTIALS",
                "Invalid email or password");
    }
}