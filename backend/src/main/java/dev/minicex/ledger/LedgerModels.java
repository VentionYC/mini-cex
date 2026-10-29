package dev.minicex.ledger;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class LedgerModels {

    public static final BigInteger MAX_UNITS =
            BigInteger.ONE.shiftLeft(256).subtract(BigInteger.ONE);

    private LedgerModels() {}

    public enum Asset {
        TETH(18),
        TUSD(6);

        private final int decimals;

        Asset(int decimals) {
            this.decimals = decimals;
        }

        public int decimals() {
            return decimals;
        }
    }

    public enum Side {
        DEBIT,
        CREDIT
    }

    public enum AccountKind {
        USER_AVAILABLE,
        USER_RESERVED,
        CUSTODY;

        public Side normalSide() {
            return this == CUSTODY ? Side.DEBIT : Side.CREDIT;
        }
    }

    public enum Operation {
        DEPOSIT,
        RESERVE,
        RELEASE,
        TRADE,
        WITHDRAWAL,
        TEST_FUNDING
    }

    public record Amount(Asset asset, BigInteger units) {

        public Amount {
            Objects.requireNonNull(asset, "asset");
            requireValidUnits(units);
        }

        public String toDecimalString() {
            return new BigDecimal(units, asset.decimals())
                    .toPlainString();
        }
    }

    public record Account(
            UUID id,
            UUID userId,
            Asset asset,
            AccountKind kind,
            BigInteger balance
    ) {
        public Account {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(asset, "asset");
            Objects.requireNonNull(kind, "kind");
            requireValidUnits(balance);

            if (kind == AccountKind.CUSTODY && userId != null) {
                throw new IllegalArgumentException(
                        "Custody accounts must not have a user owner");
            }

            if (kind != AccountKind.CUSTODY && userId == null) {
                throw new IllegalArgumentException(
                        "User accounts must have a user owner");
            }
        }
    }

    public record Posting(
            UUID debitAccountId,
            UUID creditAccountId,
            Amount amount
    ) {
        public Posting {
            Objects.requireNonNull(debitAccountId, "debitAccountId");
            Objects.requireNonNull(creditAccountId, "creditAccountId");
            Objects.requireNonNull(amount, "amount");

            if (debitAccountId.equals(creditAccountId)) {
                throw new IllegalArgumentException(
                        "Debit and credit accounts must differ");
            }

            if (amount.units().signum() == 0) {
                throw new IllegalArgumentException(
                        "Posting amount must be positive");
            }
        }
    }

    public record JournalRequest(
            Operation operation,
            String reference,
            List<Posting> postings
    ) {
        public JournalRequest {
            Objects.requireNonNull(operation, "operation");
            Objects.requireNonNull(reference, "reference");
            Objects.requireNonNull(postings, "postings");

            if (reference.isBlank() || reference.length() > 160) {
                throw new IllegalArgumentException(
                        "Reference must contain 1 to 160 characters");
            }

            postings = List.copyOf(postings);

            if (postings.isEmpty()) {
                throw new IllegalArgumentException(
                        "A journal request must contain postings");
            }
        }
    }

    private static void requireValidUnits(BigInteger units) {
        Objects.requireNonNull(units, "units");

        if (units.signum() < 0 || units.compareTo(MAX_UNITS) > 0) {
            throw new IllegalArgumentException(
                    "Units must be within the uint256 range");
        }
    }
}