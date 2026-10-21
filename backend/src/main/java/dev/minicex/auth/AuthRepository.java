package dev.minicex.auth;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import dev.minicex.auth.AuthModels.AuthenticatedUser;
import dev.minicex.auth.AuthModels.UserResponse;

@Repository
public class AuthRepository {

    private final JdbcClient jdbc;

    public AuthRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record LoginUser(UserResponse user, String passwordHash) {
        @Override
        public String toString() {
            return "LoginUser[credentials redacted]";
        }
    }

    public Optional<UserResponse> createUser(
            UUID id,
            String email,
            String passwordHash) {

        return jdbc.sql("""
                INSERT INTO users (id, email, password_hash)
                VALUES (:id, :email, :passwordHash)
                ON CONFLICT ON CONSTRAINT users_email_unique
                DO NOTHING
                RETURNING id, email, created_at
                """)
                .param("id", id)
                .param("email", email)
                .param("passwordHash", passwordHash)
                .query((rs, rowNumber) -> readUser(rs))
                .optional();
    }

    public Optional<LoginUser> findLoginUser(String email) {
        return jdbc.sql("""
                SELECT id, email, password_hash, created_at
                FROM users
                WHERE email = :email
                """)
                .param("email", email)
                .query((rs, rowNumber) -> new LoginUser(
                        readUser(rs),
                        rs.getString("password_hash")))
                .optional();
    }

    public Instant createSession(UUID userId, String tokenHash) {
        return jdbc.sql("""
                INSERT INTO sessions (token_hash, user_id, expires_at)
                VALUES (
                    :tokenHash,
                    :userId,
                    CURRENT_TIMESTAMP + INTERVAL '1 hour'
                )
                RETURNING expires_at
                """)
                .param("tokenHash", tokenHash)
                .param("userId", userId)
                .query((rs, rowNumber) -> rs.getObject(
                        "expires_at", OffsetDateTime.class).toInstant())
                .single();
    }

    public Optional<AuthenticatedUser> findActiveSession(String tokenHash) {
        return jdbc.sql("""
                SELECT u.id, u.email, u.created_at
                FROM sessions s
                JOIN users u ON u.id = s.user_id
                WHERE s.token_hash = :tokenHash
                  AND s.revoked_at IS NULL
                  AND s.expires_at > CURRENT_TIMESTAMP
                """)
                .param("tokenHash", tokenHash)
                .query((rs, rowNumber) -> new AuthenticatedUser(
                        readUser(rs),
                        tokenHash))
                .optional();
    }

    public void revokeSession(UUID userId, String tokenHash) {
        jdbc.sql("""
                UPDATE sessions
                SET revoked_at = CURRENT_TIMESTAMP
                WHERE token_hash = :tokenHash
                  AND user_id = :userId
                  AND revoked_at IS NULL
                """)
                .param("tokenHash", tokenHash)
                .param("userId", userId)
                .update();
    }

    private static UserResponse readUser(ResultSet rs) throws SQLException {
        return new UserResponse(
                rs.getObject("id", UUID.class),
                rs.getString("email"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant());
    }
}