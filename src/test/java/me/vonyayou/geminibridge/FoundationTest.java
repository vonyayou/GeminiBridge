package me.vonyayou.geminibridge;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import javax.sql.DataSource;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:sqlite::memory:", "bridge.data-dir=test_run/foundation"})
class FoundationTest {
    @Autowired DataSource dataSource;

    @Test void sqliteConnectionIsAvailable() throws Exception {
        try (var connection = dataSource.getConnection()) {
            assertThat(connection.isValid(1)).isTrue();
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("SQLite");
        }
    }
}
