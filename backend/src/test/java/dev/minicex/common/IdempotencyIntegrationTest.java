package dev.minicex.common;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

import tools.jackson.databind.json.JsonMapper;

import dev.minicex.common.IdempotencyService.Result;
import dev.minicex.ledger.LedgerModels.AccountKind;
import dev.minicex.ledger.LedgerModels.Amount;
import dev.minicex.ledger.LedgerModels.Asset;
import dev.minicex.ledger.LedgerService;
import dev.minicex.support.LedgerFixtures;
import dev.minicex.support.PostgresIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdempotencyIntegrationTest extends PostgresIntegrationTest {

    private static final String OPERATION = "reserve:v1";

    @Autowired
    private IdempotencyService idempotency;

    @Autowired
    private LedgerService ledger;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JsonMapper json;

    private LedgerFixtures fixtures;

    @BeforeEach
    void prepareFixtures() {
        fixtures = new LedgerFixtures(jdbc, ledger);
    }

    @Test
    void replayUsesStoredResponseEvenFromANewServiceInstance() {
        UUID userId = fundedUser();
        String key = key();

        Result first = reserve(userId, OPERATION, key, 30);
        Result replay = reserve(userId, OPERATION, key, 30);

        assertThat(replay).isEqualTo(first);

        var freshService = new IdempotencyService(
                jdbc, transactionManager, json);

        Result persisted = freshService.execute(
                userId,
                OPERATION,
                key,
                fields(30),
                () -> {
                    throw new AssertionError(
                            "A replay must not execute the operation");
                });

        assertThat(persisted).isEqualTo(first);
        assertBalances(userId, 70, 30);
        assertThat(recordCount(userId, key)).isEqualTo(1L);
    }

    @Test
    void sameKeyWithDifferentInputsIsRejected() {
        UUID userId = fundedUser();
        String key = key();

        reserve(userId, OPERATION, key, 30);

        assertThatThrownBy(() ->
                reserve(userId, OPERATION, key, 40))
                .isInstanceOfSatisfying(ApiException.class, exception ->
                        assertThat(exception.code())
                                .isEqualTo("IDEMPOTENCY_KEY_REUSED"));

        assertBalances(userId, 70, 30);
        assertThat(recordCount(userId, key)).isEqualTo(1L);
    }

    @Test
    void keysAreScopedByUserAndOperation() {
        UUID alice = fundedUser();
        UUID bob = fundedUser();
        String sharedKey = key();

        reserve(alice, OPERATION, sharedKey, 30);
        reserve(bob, OPERATION, sharedKey, 30);
        reserve(alice, "reserve:v2", sharedKey, 10);

        assertBalances(alice, 60, 40);
        assertBalances(bob, 70, 30);

        assertThat(recordCount(alice, sharedKey)).isEqualTo(2L);
        assertThat(recordCount(bob, sharedKey)).isEqualTo(1L);
    }

    @Test
    void failureRollsBackLedgerAndAllowsRetry() {
        UUID userId = fundedUser();
        String key = key();
        String reference = UUID.randomUUID().toString();

        assertThatThrownBy(() -> idempotency.execute(
                userId,
                OPERATION,
                key,
                fields(30),
                () -> {
                    ledger.reserve(userId, tusd(30), reference);
                    throw new IllegalStateException("Simulated failure");
                }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Simulated failure");

        assertBalances(userId, 100, 0);
        assertThat(recordCount(userId, key)).isZero();

        Long journalCount = jdbc.sql("""
                SELECT COUNT(*)
                FROM ledger_transactions
                WHERE reference = :reference
                """)
                .param("reference", reference)
                .query(Long.class)
                .single();

        assertThat(journalCount).isZero();

        Result retry = reserve(userId, OPERATION, key, 30);

        assertThat(retry.status()).isEqualTo(201);
        assertBalances(userId, 70, 30);
        assertThat(recordCount(userId, key)).isEqualTo(1L);
    }

    @Test
    void concurrentDuplicatesExecuteOnce() throws Exception {
        UUID userId = fundedUser();
        String key = key();

        AtomicInteger executions = new AtomicInteger();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);

        try {
            var first = executor.submit(() ->
                    concurrentReserve(
                            userId, key, executions, ready, start));

            var second = executor.submit(() ->
                    concurrentReserve(
                            userId, key, executions, ready, start));

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            Result firstResult = first.get(20, TimeUnit.SECONDS);
            Result secondResult = second.get(20, TimeUnit.SECONDS);

            assertThat(secondResult).isEqualTo(firstResult);
            assertThat(executions.get()).isEqualTo(1);
            assertThat(recordCount(userId, key)).isEqualTo(1L);
            assertBalances(userId, 70, 30);
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    private Result concurrentReserve(
            UUID userId,
            String key,
            AtomicInteger executions,
            CountDownLatch ready,
            CountDownLatch start) throws InterruptedException {

        ready.countDown();

        if (!start.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Concurrent test did not start");
        }

        return idempotency.execute(
                userId,
                OPERATION,
                key,
                fields(30),
                () -> {
                    executions.incrementAndGet();
                    return performReservation(userId, 30);
                });
    }

    private Result reserve(
            UUID userId,
            String operation,
            String key,
            long tokens) {

        return idempotency.execute(
                userId,
                operation,
                key,
                fields(tokens),
                () -> performReservation(userId, tokens));
    }

    private Result performReservation(UUID userId, long tokens) {
        UUID transactionId = ledger.reserve(
                userId,
                tusd(tokens),
                UUID.randomUUID().toString());

        return idempotency.response(
                201,
                Map.of("transactionId", transactionId.toString()));
    }

    private UUID fundedUser() {
        UUID userId = fixtures.newUser();
        fixtures.fund(userId, Asset.TUSD, tusd(100).units());
        return userId;
    }

    private void assertBalances(
            UUID userId,
            long available,
            long reserved) {

        assertThat(fixtures.account(
                userId, Asset.TUSD, AccountKind.USER_AVAILABLE).balance())
                .isEqualTo(tusd(available).units());

        assertThat(fixtures.account(
                userId, Asset.TUSD, AccountKind.USER_RESERVED).balance())
                .isEqualTo(tusd(reserved).units());
    }

    private long recordCount(UUID userId, String key) {
        return jdbc.sql("""
                SELECT COUNT(*)
                FROM idempotency_records
                WHERE user_id = :userId
                  AND idempotency_key = :key
                """)
                .param("userId", userId)
                .param("key", key)
                .query(Long.class)
                .single();
    }

    private static List<String> fields(long tokens) {
        return List.of("TUSD", tusd(tokens).units().toString());
    }

    private static Amount tusd(long tokens) {
        return new Amount(
                Asset.TUSD,
                BigInteger.valueOf(tokens)
                        .multiply(BigInteger.TEN.pow(6)));
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }
}