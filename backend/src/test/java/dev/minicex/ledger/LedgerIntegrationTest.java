package dev.minicex.ledger;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import dev.minicex.common.ApiException;
import dev.minicex.ledger.LedgerModels.Account;
import dev.minicex.ledger.LedgerModels.AccountKind;
import dev.minicex.ledger.LedgerModels.Amount;
import dev.minicex.ledger.LedgerModels.Asset;
import dev.minicex.ledger.LedgerModels.JournalRequest;
import dev.minicex.ledger.LedgerModels.Operation;
import dev.minicex.ledger.LedgerModels.Posting;
import dev.minicex.ledger.LedgerModels.Side;
import dev.minicex.support.LedgerFixtures;
import dev.minicex.support.PostgresIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LedgerIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private LedgerService ledger;

    @Autowired
    private JdbcClient jdbc;

    private LedgerFixtures fixtures;

    @BeforeEach
    void prepareFixtures() {
        fixtures = new LedgerFixtures(jdbc, ledger);
    }

    @Test
    void reserveAndReleasePreserveFundsAndJournalBalances() {
        UUID userId = fundedUser();

        ledger.reserve(userId, tusd(30), reference());
        assertBalances(userId, 70, 30);

        ledger.release(userId, tusd(10), reference());
        assertBalances(userId, 80, 20);
    }

    @Test
    void insufficientFundsLeaveNoJournalOrBalanceChanges() {
        UUID userId = fundedUser();
        String reference = reference();

        assertThatThrownBy(() ->
                ledger.reserve(userId, tusd(101), reference))
                .isInstanceOfSatisfying(ApiException.class, exception ->
                        assertThat(exception.code())
                                .isEqualTo("INSUFFICIENT_FUNDS"));

        assertBalances(userId, 100, 0);
        assertNoJournal(reference);
    }

    @Test
    void duplicateReferenceRollsBackEarlierSqlUpdates() {
        UUID userId = fundedUser();
        String reference = reference();

        ledger.reserve(userId, tusd(30), reference);
        assertBalances(userId, 70, 30);

        // This reaches journal insertion after executing balance updates.
        // The duplicate reference must roll the whole operation back.
        assertThatThrownBy(() ->
                ledger.reserve(userId, tusd(10), reference))
                .isInstanceOfSatisfying(ApiException.class, exception ->
                        assertThat(exception.code())
                                .isEqualTo("LEDGER_REFERENCE_EXISTS"));

        assertBalances(userId, 70, 30);

        Long postings = jdbc.sql("""
                SELECT COUNT(*)
                FROM ledger_postings p
                JOIN ledger_transactions t ON t.id = p.transaction_id
                WHERE t.reference = :reference
                """)
                .param("reference", reference)
                .query(Long.class)
                .single();

        assertThat(postings).isEqualTo(1L);
    }

    @Test
    void mismatchedAssetsAreRejected() {
        UUID userId = fundedUser();
        String reference = reference();

        Account availableTusd = fixtures.account(
                userId, Asset.TUSD, AccountKind.USER_AVAILABLE);

        Account reservedTeth = fixtures.account(
                userId, Asset.TETH, AccountKind.USER_RESERVED);

        JournalRequest request = new JournalRequest(
                Operation.RESERVE,
                reference,
                List.of(new Posting(
                        availableTusd.id(),
                        reservedTeth.id(),
                        tusd(1))));

        assertThatThrownBy(() -> ledger.post(request))
                .isInstanceOfSatisfying(ApiException.class, exception ->
                        assertThat(exception.code())
                                .isEqualTo("ASSET_MISMATCH"));

        assertBalances(userId, 100, 0);

        assertThat(fixtures.account(
                userId, Asset.TETH, AccountKind.USER_RESERVED).balance())
                .isZero();

        assertNoJournal(reference);
    }

    @Test
    void combinedPostingsCannotOverspendOneAccount() {
        UUID userId = fundedUser();
        String reference = reference();

        Account available = fixtures.account(
                userId, Asset.TUSD, AccountKind.USER_AVAILABLE);

        Account reserved = fixtures.account(
                userId, Asset.TUSD, AccountKind.USER_RESERVED);

        // Each amount fits individually; their combined 110 does not.
        JournalRequest request = new JournalRequest(
                Operation.RESERVE,
                reference,
                List.of(
                        new Posting(available.id(), reserved.id(), tusd(60)),
                        new Posting(available.id(), reserved.id(), tusd(50))));

        assertThatThrownBy(() -> ledger.post(request))
                .isInstanceOfSatisfying(ApiException.class, exception ->
                        assertThat(exception.code())
                                .isEqualTo("INSUFFICIENT_FUNDS"));

        assertBalances(userId, 100, 0);
        assertNoJournal(reference);
    }

    @Test
    void concurrentReservationsCannotOverspend() throws Exception {
        UUID userId = fundedUser();

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);

        try {
            var first = executor.submit(() ->
                    attemptReservation(userId, ready, start));

            var second = executor.submit(() ->
                    attemptReservation(userId, ready, start));

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<String> results = List.of(
                    first.get(20, TimeUnit.SECONDS),
                    second.get(20, TimeUnit.SECONDS));

            assertThat(results).containsExactlyInAnyOrder(
                    "OK", "INSUFFICIENT_FUNDS");

            assertBalances(userId, 20, 80);
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    private String attemptReservation(
            UUID userId,
            CountDownLatch ready,
            CountDownLatch start) throws InterruptedException {

        ready.countDown();

        if (!start.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Concurrent test did not start");
        }

        try {
            ledger.reserve(userId, tusd(80), reference());
            return "OK";
        } catch (ApiException exception) {
            return exception.code();
        }
    }

    private UUID fundedUser() {
        UUID userId = fixtures.newUser();
        fixtures.fund(userId, Asset.TUSD, tusd(100).units());
        return userId;
    }

    private void assertBalances(
            UUID userId,
            long availableTokens,
            long reservedTokens) {

        Account available = fixtures.account(
                userId, Asset.TUSD, AccountKind.USER_AVAILABLE);

        Account reserved = fixtures.account(
                userId, Asset.TUSD, AccountKind.USER_RESERVED);

        assertThat(available.balance())
                .isEqualTo(tusd(availableTokens).units());

        assertThat(reserved.balance())
                .isEqualTo(tusd(reservedTokens).units());

        assertProjectionMatchesJournal(available);
        assertProjectionMatchesJournal(reserved);
        assertProjectionMatchesJournal(ledger.custody(Asset.TUSD));
    }

    private void assertProjectionMatchesJournal(Account account) {
        BigInteger netDebits = jdbc.sql("""
                SELECT COALESCE(SUM(
                    CASE
                        WHEN debit_account_id = :id THEN amount
                        ELSE -amount
                    END
                ), 0)
                FROM ledger_postings
                WHERE debit_account_id = :id
                   OR credit_account_id = :id
                """)
                .param("id", account.id())
                .query(BigDecimal.class)
                .single()
                .toBigIntegerExact();

        BigInteger expected = account.kind().normalSide() == Side.DEBIT
                ? netDebits
                : netDebits.negate();

        assertThat(account.balance()).isEqualTo(expected);
    }

    private void assertNoJournal(String reference) {
        Long count = jdbc.sql("""
                SELECT COUNT(*)
                FROM ledger_transactions
                WHERE reference = :reference
                """)
                .param("reference", reference)
                .query(Long.class)
                .single();

        assertThat(count).isZero();
    }

    private static Amount tusd(long wholeTokens) {
        return new Amount(
                Asset.TUSD,
                BigInteger.valueOf(wholeTokens)
                        .multiply(BigInteger.TEN.pow(Asset.TUSD.decimals())));
    }

    private static String reference() {
        return UUID.randomUUID().toString();
    }
}