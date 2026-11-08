package me.vonyayou.geminibridge.unit;

import me.vonyayou.geminibridge.gateway.*;
import me.vonyayou.geminibridge.provider.Provider;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class ConversationStoreTest {
    @Test void contextIsScopedToClient() {
        var store = new ConversationStore();
        store.save("resp_1", "client-a", List.of(new Provider.Message("user", "Hello")), "Reply");
        assertThat(store.previous("resp_1", "client-a")).hasSize(2);
        assertThatThrownBy(() -> store.previous("resp_1", "client-b")).isInstanceOf(ApiException.class);
        assertThat(store.previous(null, "client-b")).isEmpty();
    }
    @Test void oldestContextIsEvicted() {
        var store = new ConversationStore();
        for (int i = 0; i < 101; i++) store.save("resp_" + i, "client", List.of(), "Reply");
        assertThatThrownBy(() -> store.previous("resp_0", "client")).isInstanceOf(ApiException.class);
        assertThat(store.previous("resp_100", "client")).hasSize(1);
    }
}
