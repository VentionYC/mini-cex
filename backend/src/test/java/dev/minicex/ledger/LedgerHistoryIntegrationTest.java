package dev.minicex.ledger;

import java.math.BigInteger;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

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

class LedgerHistoryIntegrationTest extends PostgresIntegrationTest {

    private static final String URL = "/api/ledger/history";
    private static final String PASSWORD = "History-Test-Password-2026!";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private AuthService auth;

    @Autowired
    private LedgerService ledger;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private JsonMapper json;

    private LedgerFixtures fixtures;

    @BeforeEach
    void prepareFixtures() {
        fixtures = new LedgerFixtures(jdbc, ledger);
    }

    @Test
    void missingTokenIsRejected() throws Exception {
        mvc.perform(get(URL))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void newUserGetsEmptyHistoryWithoutCreatingAccounts() throws Exception {
        UserResponse user = newUser();

        mvc.perform(get(URL)
                        .header(HttpHeaders.AUTHORIZATION, bearer(user)))
                .andExpect(status().isOk())
                .andExpect(header().string(
                        HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.nextBeforeId").doesNotExist());

        Long count = jdbc.sql("""
                SELECT COUNT(*)
                FROM ledger_accounts
                WHERE user_id = :userId
                """)
                .param("userId", user.id())
                .query(Long.class)
                .single();

        assertThat(count).isZero();
    }

    @Test
    void usersOnlySeeTheirOwnHistory() throws Exception {
        UserResponse alice = newUser();
        UserResponse bob = newUser();

        UUID aliceTransaction = fixtures.fund(
                alice.id(), Asset.TUSD, tusd(100));

        fixtures.fund(bob.id(), Asset.TUSD, tusd(999));

        mvc.perform(get(URL)
                        .param("userId", bob.id().toString())
                        .header(HttpHeaders.AUTHORIZATION, bearer(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].transactionId")
                        .value(aliceTransaction.toString()))
                .andExpect(jsonPath("$.items[0].availableChange")
                        .value("100.000000"))
                .andExpect(jsonPath("$.items[0].reservedChange")
                        .value("0.000000"));

        mvc.perform(get(URL)
                        .header(HttpHeaders.AUTHORIZATION, bearer(bob)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].availableChange")
                        .value("999.000000"));
    }

    @Test
    void reserveAndReleaseShowBothChangesOnce() throws Exception {
        UserResponse user = newUser();

        fixtures.fund(user.id(), Asset.TUSD, tusd(100));

        UUID reserveId = ledger.reserve(
                user.id(),
                new Amount(Asset.TUSD, tusd(30)),
                UUID.randomUUID().toString());

        UUID releaseId = ledger.release(
                user.id(),
                new Amount(Asset.TUSD, tusd(10)),
                UUID.randomUUID().toString());

        mvc.perform(get(URL)
                        .header(HttpHeaders.AUTHORIZATION, bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.items[0].transactionId")
                        .value(releaseId.toString()))
                .andExpect(jsonPath("$.items[0].operation").value("RELEASE"))
                .andExpect(jsonPath("$.items[0].asset").value("TUSD"))
                .andExpect(jsonPath("$.items[0].availableChange")
                        .value("10.000000"))
                .andExpect(jsonPath("$.items[0].reservedChange")
                        .value("-10.000000"))
                .andExpect(jsonPath("$.items[1].transactionId")
                        .value(reserveId.toString()))
                .andExpect(jsonPath("$.items[1].operation").value("RESERVE"))
                .andExpect(jsonPath("$.items[1].availableChange")
                        .value("-30.000000"))
                .andExpect(jsonPath("$.items[1].reservedChange")
                        .value("30.000000"))
                .andExpect(jsonPath("$.items[2].operation")
                        .value("TEST_FUNDING"))
                .andExpect(jsonPath("$.nextBeforeId").doesNotExist());
    }

    @Test
    void cursorReadsOlderEntriesWithoutRepeatingFirstPage() throws Exception {
        UserResponse user = newUser();

        fixtures.fund(user.id(), Asset.TUSD, tusd(1));
        fixtures.fund(user.id(), Asset.TUSD, tusd(2));
        fixtures.fund(user.id(), Asset.TUSD, tusd(3));

        String authorization = bearer(user);

        String firstBody = mvc.perform(get(URL)
                        .param("limit", "2")
                        .header(HttpHeaders.AUTHORIZATION, authorization))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].availableChange")
                        .value("3.000000"))
                .andExpect(jsonPath("$.items[1].availableChange")
                        .value("2.000000"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode first = json.readTree(firstBody);
        String cursor = first.get("nextBeforeId").asText();

        assertThat(cursor).isEqualTo(
                first.get("items").get(1).get("entryId").asText());

        // A newer entry arrives between the two requests.
        fixtures.fund(user.id(), Asset.TUSD, tusd(4));

        String secondBody = mvc.perform(get(URL)
                        .param("limit", "2")
                        .param("beforeId", cursor)
                        .header(HttpHeaders.AUTHORIZATION, authorization))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].availableChange")
                        .value("1.000000"))
                .andExpect(jsonPath("$.nextBeforeId").doesNotExist())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode second = json.readTree(secondBody);

        long olderId = Long.parseLong(
                second.get("items").get(0).get("entryId").asText());

        assertThat(olderId).isLessThan(Long.parseLong(cursor));
    }

    @Test
    void largeAmountsKeepEveryDecimalDigit() throws Exception {
        UserResponse user = newUser();

        fixtures.fund(
                user.id(),
                Asset.TETH,
                new BigInteger("123456789012345678901234567"));

        mvc.perform(get(URL)
                        .header(HttpHeaders.AUTHORIZATION, bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].asset").value("TETH"))
                .andExpect(jsonPath("$.items[0].availableChange")
                        .value("123456789.012345678901234567"))
                .andExpect(jsonPath("$.items[0].reservedChange")
                        .value("0.000000000000000000"));
    }

    @Test
    void invalidPaginationIsRejected() throws Exception {
        String authorization = bearer(newUser());

        for (String limit : new String[]{"0", "101", "abc"}) {
            mvc.perform(get(URL)
                            .param("limit", limit)
                            .header(HttpHeaders.AUTHORIZATION, authorization))
                    .andExpect(status().isBadRequest());
        }

        for (String cursor : new String[]{"0", "-1", "abc"}) {
            mvc.perform(get(URL)
                            .param("beforeId", cursor)
                            .header(HttpHeaders.AUTHORIZATION, authorization))
                    .andExpect(status().isBadRequest());
        }
    }

    private UserResponse newUser() {
        return auth.register(new RegisterRequest(
                "history-" + UUID.randomUUID() + "@example.com",
                PASSWORD));
    }

    private String bearer(UserResponse user) {
        return "Bearer " + auth.login(new LoginRequest(
                user.email(), PASSWORD)).accessToken();
    }

    private static BigInteger tusd(long tokens) {
        return BigInteger.valueOf(tokens)
                .multiply(BigInteger.TEN.pow(6));
    }
}