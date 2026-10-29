package dev.minicex.support;

import java.math.BigInteger;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;

import dev.minicex.ledger.LedgerModels.Account;
import dev.minicex.ledger.LedgerModels.AccountKind;
import dev.minicex.ledger.LedgerModels.Amount;
import dev.minicex.ledger.LedgerModels.Asset;
import dev.minicex.ledger.LedgerModels.JournalRequest;
import dev.minicex.ledger.LedgerModels.Operation;
import dev.minicex.ledger.LedgerModels.Posting;
import dev.minicex.ledger.LedgerService;

public final class LedgerFixtures {

    private final JdbcClient jdbc;
    private final LedgerService ledger;

    public LedgerFixtures(JdbcClient jdbc, LedgerService ledger) {
        this.jdbc = jdbc;
        this.ledger = ledger;
    }

    public UUID newUser() {
        UUID userId = UUID.randomUUID();

        jdbc.sql("""
                INSERT INTO users (id, email, password_hash)
                VALUES (:id, :email, :passwordHash)
                """)
                .param("id", userId)
                .param("email", "ledger-" + userId + "@example.com")
                .param("passwordHash", "not-used-by-ledger-tests")
                .update();

        ledger.accounts(userId);
        return userId;
    }

    public UUID fund(UUID userId, Asset asset, BigInteger units) {
        Account available = account(
                userId, asset, AccountKind.USER_AVAILABLE);

        Account custody = ledger.custody(asset);

        return ledger.post(new JournalRequest(
                Operation.TEST_FUNDING,
                UUID.randomUUID().toString(),
                List.of(new Posting(
                        custody.id(),
                        available.id(),
                        new Amount(asset, units)))));
    }

    public Account account(UUID userId, Asset asset, AccountKind kind) {
        return ledger.accounts(userId).stream()
                .filter(account ->
                        account.asset() == asset && account.kind() == kind)
                .findFirst()
                .orElseThrow();
    }
}