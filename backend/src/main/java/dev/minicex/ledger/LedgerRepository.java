package dev.minicex.ledger;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import dev.minicex.ledger.LedgerModels.Account;
import dev.minicex.ledger.LedgerModels.AccountKind;
import dev.minicex.ledger.LedgerModels.Asset;
import dev.minicex.ledger.LedgerModels.JournalRequest;

@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class LedgerRepository {

    private final JdbcClient jdbc;

    public LedgerRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void ensureUserAccounts(UUID userId) {
        for (Asset asset : Asset.values()) {
            for (AccountKind kind : List.of(
                    AccountKind.USER_AVAILABLE,
                    AccountKind.USER_RESERVED)) {

                jdbc.sql("""
                        INSERT INTO ledger_accounts
                            (id, user_id, asset_code, kind)
                        VALUES (:id, :userId, :asset, :kind)
                        ON CONFLICT ON CONSTRAINT
                            ledger_accounts_owner_asset_kind_unique
                        DO NOTHING
                        """)
                        .param("id", UUID.randomUUID())
                        .param("userId", userId)
                        .param("asset", asset.name())
                        .param("kind", kind.name())
                        .update();
            }
        }
    }

    public List<Account> findUserAccounts(UUID userId) {
        return jdbc.sql("""
                SELECT id, user_id, asset_code, kind, balance
                FROM ledger_accounts
                WHERE user_id = :userId
                ORDER BY asset_code, kind
                """)
                .param("userId", userId)
                .query(this::readAccount)
                .list();
    }

    public Account findCustodyAccount(Asset asset) {
        return jdbc.sql("""
                SELECT id, user_id, asset_code, kind, balance
                FROM ledger_accounts
                WHERE asset_code = :asset
                  AND kind = 'CUSTODY'
                  AND user_id IS NULL
                """)
                .param("asset", asset.name())
                .query(this::readAccount)
                .single();
    }

    public List<Account> lockAccounts(Collection<UUID> accountIds) {
        return jdbc.sql("""
                SELECT id, user_id, asset_code, kind, balance
                FROM ledger_accounts
                WHERE id IN (:accountIds)
                ORDER BY id
                FOR UPDATE
                """)
                .param("accountIds", accountIds)
                .query(this::readAccount)
                .list();
    }

    public void updateBalance(UUID accountId, BigInteger balance) {
        int updated = jdbc.sql("""
                UPDATE ledger_accounts
                SET balance = :balance
                WHERE id = :id
                """)
                .param("balance", new BigDecimal(balance))
                .param("id", accountId)
                .update();

        if (updated != 1) {
            throw new IllegalStateException(
                    "Expected exactly one ledger account update");
        }
    }

    public void insertJournal(UUID transactionId, JournalRequest request) {
        jdbc.sql("""
                INSERT INTO ledger_transactions (id, operation, reference)
                VALUES (:id, :operation, :reference)
                """)
                .param("id", transactionId)
                .param("operation", request.operation().name())
                .param("reference", request.reference())
                .update();

        for (var posting : request.postings()) {
            jdbc.sql("""
                    INSERT INTO ledger_postings (
                        transaction_id,
                        asset_code,
                        debit_account_id,
                        credit_account_id,
                        amount
                    )
                    VALUES (
                        :transactionId,
                        :asset,
                        :debitAccountId,
                        :creditAccountId,
                        :amount
                    )
                    """)
                    .param("transactionId", transactionId)
                    .param("asset", posting.amount().asset().name())
                    .param("debitAccountId", posting.debitAccountId())
                    .param("creditAccountId", posting.creditAccountId())
                    .param("amount",
                            new BigDecimal(posting.amount().units()))
                    .update();
        }
    }

    private Account readAccount(ResultSet rs, int rowNumber)
            throws SQLException {

        return new Account(
                rs.getObject("id", UUID.class),
                rs.getObject("user_id", UUID.class),
                Asset.valueOf(rs.getString("asset_code")),
                AccountKind.valueOf(rs.getString("kind")),
                rs.getBigDecimal("balance").toBigIntegerExact());
    }
}