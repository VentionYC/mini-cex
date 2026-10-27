package dev.minicex.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import tools.jackson.databind.json.JsonMapper;

import dev.minicex.auth.AuthModels.LoginResponse;
import dev.minicex.auth.AuthModels.UserResponse;
import dev.minicex.support.PostgresIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthIntegrationTest extends PostgresIntegrationTest {

    private static final String PASSWORD = "LocalTest-Password-2026!";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JsonMapper json;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void registrationNormalizesEmailAndHashesPassword() throws Exception {
        String email = uniqueEmail("alice");

        UserResponse user = register(
                " " + email.toUpperCase(Locale.ROOT) + " ");

        assertThat(user.id()).isNotNull();
        assertThat(user.email()).isEqualTo(email);
        assertThat(user.createdAt()).isNotNull();

        String storedHash = jdbc.sql("""
                SELECT password_hash
                FROM users
                WHERE id = :id
                """)
                .param("id", user.id())
                .query(String.class)
                .single();

        assertThat(storedHash).isNotEqualTo(PASSWORD);
        assertThat(passwordEncoder.matches(PASSWORD, storedHash)).isTrue();
    }

    @Test
    void duplicateEmailReturnsConflict() throws Exception {
        String email = uniqueEmail("duplicate");
        register(email);

        postCredentials(
                "/api/auth/register",
                email.toUpperCase(Locale.ROOT),
                PASSWORD)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code")
                        .value("EMAIL_ALREADY_REGISTERED"));

        Long count = jdbc.sql("""
                SELECT COUNT(*)
                FROM users
                WHERE email = :email
                """)
                .param("email", email)
                .query(Long.class)
                .single();

        assertThat(count).isEqualTo(1L);
    }

    @Test
    void invalidPasswordsDoNotCreateAnAccount() throws Exception {
        String email = uniqueEmail("invalid");

        postCredentials("/api/auth/register", email, "short")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        // 25 characters, but 75 UTF-8 bytes: beyond BCrypt's limit.
        postCredentials("/api/auth/register", email, "密".repeat(25))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PASSWORD"));

        Long count = jdbc.sql("""
                SELECT COUNT(*)
                FROM users
                WHERE email = :email
                """)
                .param("email", email)
                .query(Long.class)
                .single();

        assertThat(count).isZero();
    }

    @Test
    void loginStoresOnlyTokenHashAndReturnsCorrectUser() throws Exception {
        UserResponse user = register(uniqueEmail("login"));
        LoginResponse session = login(user.email());

        assertThat(session.tokenType()).isEqualTo("Bearer");
        assertThat(session.accessToken()).matches("[A-Za-z0-9_-]{43}");
        assertThat(session.expiresAt()).isNotNull();
        assertThat(session.user().id()).isEqualTo(user.id());

        String storedHash = jdbc.sql("""
                SELECT token_hash
                FROM sessions
                WHERE user_id = :userId
                """)
                .param("userId", user.id())
                .query(String.class)
                .single();

        assertThat(storedHash)
                .isEqualTo(hashToken(session.accessToken()))
                .isNotEqualTo(session.accessToken());

        me(session.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(user.id().toString()))
                .andExpect(jsonPath("$.email").value(user.email()))
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.tokenHash").doesNotExist());

        // A successful request must not authenticate a later tokenless one.
        mvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void wrongPasswordAndUnknownEmailAreRejected() throws Exception {
        UserResponse user = register(uniqueEmail("credentials"));

        postCredentials(
                "/api/auth/login",
                user.email(),
                "WrongPassword-2026!")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                .andExpect(jsonPath("$.message")
                        .value("Invalid email or password"));

        postCredentials(
                "/api/auth/login",
                uniqueEmail("unknown"),
                PASSWORD)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                .andExpect(jsonPath("$.message")
                        .value("Invalid email or password"));

        Long count = jdbc.sql("""
                SELECT COUNT(*)
                FROM sessions
                WHERE user_id = :userId
                """)
                .param("userId", user.id())
                .query(Long.class)
                .single();

        assertThat(count).isZero();
    }

    @Test
    void missingMalformedUnknownAndDuplicateTokensAreRejected()
            throws Exception {

        mvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(
                        HttpHeaders.WWW_AUTHENTICATE, "Bearer"));

        me("invalid-token")
                .andExpect(status().isUnauthorized());

        // Valid shape, but no matching session.
        me("A".repeat(43))
                .andExpect(status().isUnauthorized());

        UserResponse user = register(uniqueEmail("headers"));
        String token = login(user.email()).accessToken();

        mvc.perform(get("/api/auth/me")
                        .header(
                                HttpHeaders.AUTHORIZATION,
                                "Bearer " + token,
                                "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void logoutRevokesOnlyCurrentSession() throws Exception {
        UserResponse alice = register(uniqueEmail("alice"));
        UserResponse bob = register(uniqueEmail("bob"));

        String aliceFirst = login(alice.email()).accessToken();
        String aliceSecond = login(alice.email()).accessToken();
        String bobToken = login(bob.email()).accessToken();

        assertThat(aliceFirst).isNotEqualTo(aliceSecond);

        // Caller-supplied IDs must not override the token's identity.
        mvc.perform(get("/api/auth/me")
                        .param("userId", bob.id().toString())
                        .header(
                                HttpHeaders.AUTHORIZATION,
                                "Bearer " + aliceFirst))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(alice.id().toString()));

        mvc.perform(post("/api/auth/logout")
                        .header(
                                HttpHeaders.AUTHORIZATION,
                                "Bearer " + aliceFirst))
                .andExpect(status().isNoContent());

        me(aliceFirst)
                .andExpect(status().isUnauthorized());

        me(aliceSecond)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(alice.id().toString()));

        me(bobToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(bob.id().toString()));
    }

    @Test
    void expiredSessionIsRejected() throws Exception {
        UserResponse user = register(uniqueEmail("expiry"));
        String token = login(user.email()).accessToken();

        me(token).andExpect(status().isOk());

        // Test database only: move this session into the past.
        int updated = jdbc.sql("""
                UPDATE sessions
                SET created_at = CURRENT_TIMESTAMP - INTERVAL '2 hours',
                    expires_at = CURRENT_TIMESTAMP - INTERVAL '1 hour'
                WHERE token_hash = :tokenHash
                """)
                .param("tokenHash", hashToken(token))
                .update();

        assertThat(updated).isEqualTo(1);

        me(token)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    private UserResponse register(String email) throws Exception {
        String body = postCredentials(
                "/api/auth/register", email, PASSWORD)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return json.readValue(body, UserResponse.class);
    }

    private LoginResponse login(String email) throws Exception {
        String body = postCredentials(
                "/api/auth/login", email, PASSWORD)
                .andExpect(status().isOk())
                .andExpect(header().string(
                        HttpHeaders.CACHE_CONTROL, "no-store"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        return json.readValue(body, LoginResponse.class);
    }

    private ResultActions postCredentials(
            String path,
            String email,
            String password) throws Exception {

        String body = json.writeValueAsString(Map.of(
                "email", email,
                "password", password));

        return mvc.perform(post(path)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions me(String token) throws Exception {
        return mvc.perform(get("/api/auth/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    private static String uniqueEmail(String prefix) {
        return prefix + "-" + UUID.randomUUID() + "@example.com";
    }

    private static String hashToken(String token) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(token.getBytes(StandardCharsets.UTF_8));

        return HexFormat.of().formatHex(digest);
    }
}