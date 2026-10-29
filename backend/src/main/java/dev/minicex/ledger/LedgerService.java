package dev.minicex.ledger;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.minicex.common.ApiException;
import dev.minicex.ledger.LedgerModels.Account;
import dev.minicex.ledger.LedgerModels.AccountKind;
import dev.minicex.ledger.LedgerModels.Amount;
import dev.minicex.ledger.LedgerModels.Asset;
import dev.minicex.ledger.LedgerModels.JournalRequest;
import dev.minicex.ledger.LedgerModels.Operation;
import dev.minicex.ledger.LedgerModels.Posting;
import dev.minicex.ledger.LedgerModels.Side;

@Service
@Transactional(timeout = 15)
public class LedgerService {

    private final LedgerRepository repository;

    public LedgerService(LedgerRepository repository) {
        this.repository = repository;
    }

    public List<Account> accounts(UUID userId) {
        Objects.requireNonNull(userId, "userId");

        repository.ensureUserAccounts(userId);
        return repository.findUserAccounts(userId);
    }

    public Account custody(Asset asset) {
        return repository.findCustodyAccount(asset);
    }

    public UUID reserve(UUID userId, Amount amount, String reference) {
        return moveBetweenUserAccounts(
                userId,
                amount,
                reference,
                Operation.RESERVE,
                AccountKind.USER_AVAILABLE,
                AccountKind.USER_RESERVED);
    }

    public UUID release(UUID userId, Amount amount, String reference) {
        return moveBetweenUserAccounts(
                userId,
                amount,
                reference,
                Operation.RELEASE,
                AccountKind.USER_RESERVED,
                AccountKind.USER_AVAILABLE);
    }

    public UUID post(JournalRequest request) {
        Objects.requireNonNull(request, "request");

        var accountIds = request.postings().stream()
                .flatMap(posting -> Stream.of(
                        posting.debitAccountId(),
                        posting.creditAccountId()))
                .collect(Collectors.toSet());

        List<Account> lockedAccounts =
                repository.lockAccounts(accountIds);

        if (lockedAccounts.size() != accountIds.size()) {
            throw new ApiException(
                    HttpStatus.NOT_FOUND,
                    "LEDGER_ACCOUNT_NOT_FOUND",
                    "A ledger account does not exist");
        }

        Map<UUID, Account> byId = lockedAccounts.stream()
                .collect(Collectors.toMap(
                        Account::id,
                        account -> account));

        Map<UUID, BigInteger> deltas = new HashMap<>();

        for (Posting posting : request.postings()) {
            Account debit = byId.get(posting.debitAccountId());
            Account credit = byId.get(posting.creditAccountId());

            if (debit.asset() != posting.amount().asset()
                    || credit.asset() != posting.amount().asset()) {
                throw new ApiException(
                        HttpStatus.BAD_REQUEST,
                        "ASSET_MISMATCH",
                        "Both accounts must match the posting asset");
            }

            addDelta(deltas, debit, Side.DEBIT, posting.amount().units());
            addDelta(deltas, credit, Side.CREDIT, posting.amount().units());
        }

        Map<UUID, BigInteger> nextBalances = new HashMap<>();

        for (Account account : lockedAccounts) {
            BigInteger next = account.balance().add(
                    deltas.getOrDefault(account.id(), BigInteger.ZERO));

            if (next.signum() < 0) {
                throw new ApiException(
                        HttpStatus.CONFLICT,
                        "INSUFFICIENT_FUNDS",
                        "Insufficient funds for this operation");
            }

            if (next.compareTo(LedgerModels.MAX_UNITS) > 0) {
                throw new ApiException(
                        HttpStatus.CONFLICT,
                        "BALANCE_LIMIT_EXCEEDED",
                        "The resulting balance exceeds the supported range");
            }

            nextBalances.put(account.id(), next);
        }

        for (Account account : lockedAccounts) {
            repository.updateBalance(
                    account.id(),
                    nextBalances.get(account.id()));
        }

        UUID transactionId = UUID.randomUUID();

        try {
            repository.insertJournal(transactionId, request);
        } catch (DuplicateKeyException exception) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "LEDGER_REFERENCE_EXISTS",
                    "This ledger operation reference already exists");
        }

        return transactionId;
    }

    private UUID moveBetweenUserAccounts(
            UUID userId,
            Amount amount,
            String reference,
            Operation operation,
            AccountKind debitKind,
            AccountKind creditKind) {

        List<Account> userAccounts = accounts(userId);

        Account debit = findAccount(
                userAccounts, amount.asset(), debitKind);

        Account credit = findAccount(
                userAccounts, amount.asset(), creditKind);

        return post(new JournalRequest(
                operation,
                reference,
                List.of(new Posting(debit.id(), credit.id(), amount))));
    }

    private static Account findAccount(
            List<Account> accounts,
            Asset asset,
            AccountKind kind) {

        return accounts.stream()
                .filter(account ->
                        account.asset() == asset && account.kind() == kind)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Required user ledger account is missing"));
    }

    private static void addDelta(
            Map<UUID, BigInteger> deltas,
            Account account,
            Side side,
            BigInteger units) {

        BigInteger delta = account.kind().normalSide() == side
                ? units
                : units.negate();

        deltas.merge(account.id(), delta, BigInteger::add);
    }
}