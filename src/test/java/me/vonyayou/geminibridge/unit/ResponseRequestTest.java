package me.vonyayou.geminibridge.unit;

import com.fasterxml.jackson.databind.ObjectMapper;
import me.vonyayou.geminibridge.gateway.ApiException;
import me.vonyayou.geminibridge.gateway.ResponseRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class ResponseRequestTest {
    private final ObjectMapper mapper = new ObjectMapper();
    @Test void supportsTextAndMessageArrays() throws Exception {
        var request = ResponseRequest.parse(mapper.readTree("""
            {"model":"mock-echo","instructions":"Be brief","stream":true,"input":[
              {"role":"user","content":[{"type":"input_text","text":"Привет 👋"}]},
              {"type":"message","role":"assistant","content":[{"type":"output_text","text":"Hello"}]}]}
            """));
        assertThat(request.messages()).hasSize(2);
        assertThat(request.messages().getFirst().content()).isEqualTo("Привет 👋");
        assertThat(request.instructions()).isEqualTo("Be brief");
        assertThat(request.stream()).isTrue();
    }
    @ParameterizedTest
    @ValueSource(strings = {"\"temperature\":0.2", "\"max_output_tokens\":32", "\"tools\":[{\"type\":\"function\"}]", "\"tool_choice\":\"auto\"", "\"text\":{\"format\":{\"type\":\"json_schema\"}}", "\"background\":true"})
    void rejectsUnsupportedParameters(String field) throws Exception {
        var input = mapper.readTree("{\"model\":\"mock-echo\",\"input\":\"Hello\"," + field + "}");
        assertThatThrownBy(() -> ResponseRequest.parse(input)).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.status()).isEqualTo(400); assertThat(e.code()).isEqualTo("unsupported_parameter");
        });
    }
    @ParameterizedTest
    @ValueSource(strings = {"{}", "[]", "{\"model\":1,\"input\":\"hi\"}", "{\"model\":\"mock-echo\",\"input\":[]}", "{\"model\":\"mock-echo\",\"input\":\"hi\",\"stream\":\"true\"}", "{\"model\":\"mock-echo\",\"input\":null}"})
    void rejectsMalformedInputs(String body) throws Exception {
        var input = mapper.readTree(body);
        assertThatThrownBy(() -> ResponseRequest.parse(input)).isInstanceOf(ApiException.class);
    }
    @Test void rejectsImageInput() throws Exception {
        var input = mapper.readTree("""
            {"model":"mock-echo","input":[{"role":"user","content":[{"type":"input_image","image_url":"https://example.com/image.png"}]}]}
            """);
        assertThatThrownBy(() -> ResponseRequest.parse(input)).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.param()).isEqualTo("input.content"));
    }
}
