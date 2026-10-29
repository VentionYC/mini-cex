package dev.minicex.ledger;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import dev.minicex.auth.AuthModels.AuthenticatedUser;
import dev.minicex.ledger.LedgerModels.Account;
import dev.minicex.ledger.LedgerModels.AccountKind;
import dev.minicex.ledger.LedgerModels.Asset;

@RestController
public class BalanceController {

    private final LedgerService ledger;

    public BalanceController(LedgerService ledger) {
        this.ledger = ledger;
    }

    public record BalanceResponse(
            String asset,
            int decimals,
            String available,
            String reserved,
            String total
    ) {}

    @GetMapping("/api/balances")
    public ResponseEntity<List<BalanceResponse>> balances(
            @AuthenticationPrincipal AuthenticatedUser principal) {

        List<Account> accounts =
                ledger.readAccounts(principal.user().id());

        List<BalanceResponse> response = Arrays.stream(Asset.values())
                .map(asset -> {
                    BigInteger available = balance(
                            accounts, asset, AccountKind.USER_AVAILABLE);

                    BigInteger reserved = balance(
                            accounts, asset, AccountKind.USER_RESERVED);

                    return new BalanceResponse(
                            asset.name(),
                            asset.decimals(),
                            decimal(asset, available),
                            decimal(asset, reserved),
                            decimal(asset, available.add(reserved)));
                })
                .toList();

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(response);
    }

    private static BigInteger balance(
            List<Account> accounts,
            Asset asset,
            AccountKind kind) {

        return accounts.stream()
                .filter(account ->
                        account.asset() == asset && account.kind() == kind)
                .map(Account::balance)
                .reduce(BigInteger.ZERO, BigInteger::add);
    }

    private static String decimal(Asset asset, BigInteger units) {
        return new BigDecimal(units, asset.decimals()).toPlainString();
    }
}