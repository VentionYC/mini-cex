package dev.minicex.ledger;

import java.math.BigInteger;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import dev.minicex.auth.AuthModels.LoginRequest;
import dev.minicex.auth.AuthModels.RegisterRequest;
import dev.minicex.auth.AuthModels.UserResponse;
import dev.minicex.auth.AuthService;
import dev.minicex.ledger.LedgerModels.Amount;
import dev.minicex.ledger.LedgerModels.Asset;
import dev.minicex.support.LedgerFixtures;
import dev.minicex.support.PostgresIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class BalanceIntegrationTest extends PostgresIntegrationTest {

    private static final String PASSWORD = "Balance-Test-Password-2026!";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private AuthService auth;

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
    void missingTokenIsRejected() throws Exception {
        mvc.perform(get("/api/balances"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void newUserGetsZerosWithoutCreatingAccounts() throws Exception {
        UserResponse user = newUser();

        mvc.perform(get("/api/balances")
                        .header(HttpHeaders.AUTHORIZATION,
                                "Bearer " + token(user)))
                .andExpect(status().isOk())
                .andExpect(header().string(
                        HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].asset").value("TETH"))
                .andExpect(jsonPath("$[0].available")
                        .value("0.000000000000000000"))
                .andExpect(jsonPath("$[0].reserved")
                        .value("0.000000000000000000"))
                .andExpect(jsonPath("$[1].asset").value("TUSD"))
                .andExpect(jsonPath("$[1].available").value("0.000000"))
                .andExpect(jsonPath("$[1].reserved").value("0.000000"))
                .andExpect(jsonPath("$[1].total").value("0.000000"));

        Long accountCount = jdbc.sql("""
                SELECT COUNT(*)
                FROM ledger_accounts
                WHERE user_id = :userId
                """)
                .param("userId", user.id())
                .query(Long.class)
                .single();

        assertThat(accountCount).isZero();
    }

    @Test
    void usersOnlySeeTheirOwnBalances() throws Exception {
        UserResponse alice = newUser();
        UserResponse bob = newUser();

        fixtures.fund(alice.id(), Asset.TUSD, tusd(100));
        fixtures.fund(bob.id(), Asset.TUSD, tusd(999));

        ledger.reserve(
                alice.id(),
                new Amount(Asset.TUSD, tusd(30)),
                UUID.randomUUID().toString());

        mvc.perform(get("/api/balances")
                        .param("userId", bob.id().toString())
                        .header(HttpHeaders.AUTHORIZATION,
                                "Bearer " + token(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[1].available").value("70.000000"))
                .andExpect(jsonPath("$[1].reserved").value("30.000000"))
                .andExpect(jsonPath("$[1].total").value("100.000000"));

        mvc.perform(get("/api/balances")
                        .header(HttpHeaders.AUTHORIZATION,
                                "Bearer " + token(bob)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[1].available").value("999.000000"))
                .andExpect(jsonPath("$[1].reserved").value("0.000000"));
    }

    @Test
    void largeAmountsKeepEveryDecimalDigit() throws Exception {
        UserResponse user = newUser();

        fixtures.fund(
                user.id(),
                Asset.TETH,
                new BigInteger("123456789012345678901234567"));

        mvc.perform(get("/api/balances")
                        .header(HttpHeaders.AUTHORIZATION,
                                "Bearer " + token(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].available")
                        .value("123456789.012345678901234567"))
                .andExpect(jsonPath("$[0].total")
                        .value("123456789.012345678901234567"));
    }

    private UserResponse newUser() {
        return auth.register(new RegisterRequest(
                "balance-" + UUID.randomUUID() + "@example.com",
                PASSWORD));
    }

    private String token(UserResponse user) {
        return auth.login(new LoginRequest(
                user.email(), PASSWORD)).accessToken();
    }

    private static BigInteger tusd(long tokens) {
        return BigInteger.valueOf(tokens)
                .multiply(BigInteger.TEN.pow(6));
    }
}