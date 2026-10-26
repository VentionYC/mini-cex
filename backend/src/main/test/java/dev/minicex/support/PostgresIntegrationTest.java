package dev.minicex.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(properties = {
        "spring.datasource.hikari.maximum-pool-size=3",
        "spring.flyway.enabled=true"
})
@ActiveProfiles("test")
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@Import(PostgresIntegrationTest.DatabaseConfiguration.class)
public abstract class PostgresIntegrationTest {

    @TestConfiguration(proxyBeanMethods = false)
    public static class DatabaseConfiguration {

        @Bean
        @ServiceConnection
        PostgreSQLContainer postgres() {
            return new PostgreSQLContainer("postgres:16.15")
                    .withDatabaseName("minicex_test")
                    .withUsername("test")
                    .withPassword("test");
        }
    }
}