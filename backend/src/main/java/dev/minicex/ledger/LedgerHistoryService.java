package dev.minicex.ledger;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.minicex.common.ApiException;
import dev.minicex.ledger.LedgerModels.AccountKind;
import dev.minicex.ledger.LedgerModels.Asset;

@Service
public class LedgerHistoryService {

    private final JdbcClient jdbc;

    public LedgerHistoryService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record Entry(
            String entryId,
            UUID transactionId,
            String operation,
            String asset,
            String availableChange,
            String reservedChange,
            Instant createdAt
    ) {}

    public record Page(
            List<Entry> items,
            String nextBeforeId
    ) {}

    @Transactional(readOnly = true, timeout = 15)
    public Page history(UUID userId, int limit, Long beforeId) {
        Objects.requireNonNull(userId, "userId");

        if (limit < 1 || limit > 100) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_PAGINATION",
                    "limit must be between 1 and 100");
        }

        if (beforeId != null && beforeId <= 0) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_PAGINATION",
                    "beforeId must be a positive integer");
        }

        long maxId = beforeId == null
                ? Long.MAX_VALUE
                : beforeId - 1;

        List<Entry> rows = jdbc.sql("""
                SELECT
                    p.id,
                    p.transaction_id,
                    p.asset_code,
                    p.amount,
                    t.operation,
                    t.created_at,
                    debit.user_id AS debit_user_id,
                    debit.kind AS debit_kind,
                    credit.user_id AS credit_user_id,
                    credit.kind AS credit_kind
                FROM ledger_postings p
                JOIN ledger_transactions t
                    ON t.id = p.transaction_id
                JOIN ledger_accounts debit
                    ON debit.id = p.debit_account_id
                JOIN ledger_accounts credit
                    ON credit.id = p.credit_account_id
                WHERE (
                    debit.user_id = :userId
                    OR credit.user_id = :userId
                )
                AND p.id <= :maxId
                ORDER BY p.id DESC
                LIMIT :fetchSize
                """)
                .param("userId", userId)
                .param("maxId", maxId)
                .param("fetchSize", limit + 1)
                .query((rs, rowNum) -> {
                    Asset asset = Asset.valueOf(
                            rs.getString("asset_code"));

                    BigInteger units = rs.getBigDecimal("amount")
                            .toBigIntegerExact();

                    UUID debitOwner = rs.getObject(
                            "debit_user_id", UUID.class);

                    UUID creditOwner = rs.getObject(
                            "credit_user_id", UUID.class);

                    String debitKind = rs.getString("debit_kind");
                    String creditKind = rs.getString("credit_kind");

                    BigInteger availableChange = change(
                            userId,
                            AccountKind.USER_AVAILABLE,
                            units,
                            debitOwner,
                            debitKind,
                            creditOwner,
                            creditKind);

                    BigInteger reservedChange = change(
                            userId,
                            AccountKind.USER_RESERVED,
                            units,
                            debitOwner,
                            debitKind,
                            creditOwner,
                            creditKind);

                    return new Entry(
                            Long.toString(rs.getLong("id")),
                            rs.getObject("transaction_id", UUID.class),
                            rs.getString("operation"),
                            asset.name(),
                            decimal(asset, availableChange),
                            decimal(asset, reservedChange),
                            rs.getTimestamp("created_at").toInstant());
                })
                .list();

        boolean hasMore = rows.size() > limit;

        List<Entry> items = List.copyOf(
                rows.subList(0, Math.min(limit, rows.size())));

        String nextBeforeId = hasMore
                ? items.getLast().entryId()
                : null;

        return new Page(items, nextBeforeId);
    }

    private static BigInteger change(
            UUID userId,
            AccountKind kind,
            BigInteger units,
            UUID debitOwner,
            String debitKind,
            UUID creditOwner,
            String creditKind) {

        BigInteger result = BigInteger.ZERO;

        // User accounts have credit-normal balances:
        // debit decreases the balance; credit increases it.
        if (userId.equals(debitOwner)
                && kind.name().equals(debitKind)) {
            result = result.subtract(units);
        }

        if (userId.equals(creditOwner)
                && kind.name().equals(creditKind)) {
            result = result.add(units);
        }

        return result;
    }

    private static String decimal(Asset asset, BigInteger units) {
        return new BigDecimal(units, asset.decimals()).toPlainString();
    }
}