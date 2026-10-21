package dev.minicex.auth;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import dev.minicex.auth.AuthModels.UserResponse;

@Repository
public class AuthRepository {

    private final JdbcClient jdbc;

    public AuthRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
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
                .query((resultSet, rowNumber) -> new UserResponse(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getString("email"),
                        resultSet.getObject(
                                "created_at",
                                OffsetDateTime.class).toInstant()))
                .optional();
    }
}