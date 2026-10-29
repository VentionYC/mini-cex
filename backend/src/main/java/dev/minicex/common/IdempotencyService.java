package dev.minicex.common;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.json.JsonMapper;

@Service
public class IdempotencyService {

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final TransactionTemplate transactions;

    public IdempotencyService(
            JdbcClient jdbc,
            PlatformTransactionManager transactionManager,
            JsonMapper json) {

        this.jdbc = jdbc;
        this.json = json;

        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setIsolationLevel(
                TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.transactions.setTimeout(15);
    }

    public record Result(int status, String body) {
        public Result {
            if (status < 200 || status > 299) {
                throw new IllegalArgumentException(
                        "Only successful responses can be stored");
            }

            Objects.requireNonNull(body, "body");
        }
    }

    private record Cached(String requestHash, Result result) {}

    public Result response(int status, Object body) {
        return new Result(status, json.writeValueAsString(body));
    }

    public Result execute(
            UUID userId,
            String operation,
            String key,
            List<String> normalizedFields,
            Supplier<Result> action) {

        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(action, "action");

        if (operation == null
                || !operation.matches("[A-Za-z0-9._:-]{1,64}")) {
            throw new IllegalArgumentException("Invalid operation name");
        }

        if (key == null || !key.matches("[A-Za-z0-9._:-]{1,128}")) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_IDEMPOTENCY_KEY",
                    "Use 1 to 128 letters, digits, dots, underscores, colons or hyphens");
        }

        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "Call idempotency before starting other database work");
        }

        List<String> fields = List.copyOf(normalizedFields);

        String requestHash = HexFormat.of().formatHex(
                digest(json.writeValueAsBytes(fields)));

        long lockId = ByteBuffer.wrap(digest(json.writeValueAsBytes(
                List.of(userId.toString(), operation, key)))).getLong();

        return Objects.requireNonNull(transactions.execute(transaction -> {
            jdbc.sql("SELECT pg_advisory_xact_lock(:lockId)")
                    .param("lockId", lockId)
                    .query((rs, rowNumber) -> true)
                    .single();

            var cached = jdbc.sql("""
                    SELECT request_hash,
                           response_status,
                           response_body::text AS body
                    FROM idempotency_records
                    WHERE user_id = :userId
                      AND operation = :operation
                      AND idempotency_key = :key
                    """)
                    .param("userId", userId)
                    .param("operation", operation)
                    .param("key", key)
                    .query((rs, rowNumber) -> new Cached(
                            rs.getString("request_hash"),
                            new Result(
                                    rs.getInt("response_status"),
                                    rs.getString("body"))))
                    .optional();

            if (cached.isPresent()) {
                Cached previous = cached.orElseThrow();

                if (!previous.requestHash().equals(requestHash)) {
                    throw new ApiException(
                            HttpStatus.CONFLICT,
                            "IDEMPOTENCY_KEY_REUSED",
                            "This key was already used with different inputs");
                }

                return previous.result();
            }

            Result result = Objects.requireNonNull(action.get());

            return jdbc.sql("""
                    INSERT INTO idempotency_records (
                        user_id,
                        operation,
                        idempotency_key,
                        request_hash,
                        response_status,
                        response_body
                    )
                    VALUES (
                        :userId,
                        :operation,
                        :key,
                        :requestHash,
                        :responseStatus,
                        CAST(:responseBody AS jsonb)
                    )
                    RETURNING response_status, response_body::text AS body
                    """)
                    .param("userId", userId)
                    .param("operation", operation)
                    .param("key", key)
                    .param("requestHash", requestHash)
                    .param("responseStatus", result.status())
                    .param("responseBody", result.body())
                    .query((rs, rowNumber) -> new Result(
                            rs.getInt("response_status"),
                            rs.getString("body")))
                    .single();
        }));
    }

    private static byte[] digest(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "SHA-256 is unavailable", exception);
        }
    }
}