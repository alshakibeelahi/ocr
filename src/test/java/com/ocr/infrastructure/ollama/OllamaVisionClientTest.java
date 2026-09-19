package com.ocr.infrastructure.ollama;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ocr.interfaces.config.OllamaProperties;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * The consumer is how a caller stops a stream it no longer wants: {@code StartPiDataExtractionJobHandler}
 * throws from it when its repetition guard trips, then catches that exact type to keep the good
 * prefix. Wrapping anything the consumer throws in a parse error broke that — the caller's catch no
 * longer matched, so a recoverable loop became a failed job blamed on a payload that parsed fine.
 * The handler's own tests stub the port out, so only a test against the real client covers it.
 */
class OllamaVisionClientTest {

  private static final String NDJSON = """
      {"model":"qwen2.5vl:3b","message":{"role":"assistant","content":"AAAA"},"done":false}
      {"model":"qwen2.5vl:3b","message":{"role":"assistant","content":"BBBB"},"done":false}
      {"model":"qwen2.5vl:3b","message":{"role":"assistant","content":"CCCC"},"done":true}
      """;

  private static final class StopStream extends RuntimeException {
  }

  private static OllamaVisionClient clientReturning(String body) {
    WebClient webClient = WebClient.builder()
        .exchangeFunction(request -> Mono.just(ClientResponse.create(HttpStatus.OK)
            .header("Content-Type", MediaType.APPLICATION_NDJSON_VALUE)
            .body(body)
            .build()))
        .build();
    OllamaProperties properties = new OllamaProperties(
        "http://localhost:11434", "qwen2.5vl:3b", null, null, null, 8192, 6500,
        2048, 1.1, 320, 0, "ocr prompt", "pi prompt");
    return new OllamaVisionClient(webClient, properties, new ObjectMapper());
  }

  @Test
  void consumerException_reachesTheCallerUnwrapped() {
    OllamaVisionClient client = clientReturning(NDJSON);

    assertThatThrownBy(() -> client.streamVision(List.of("img"), "prompt", true, chunk -> {
      throw new StopStream();
    })).isInstanceOf(StopStream.class);
  }

  @Test
  void consumerException_stopsTheStreamAtThatChunk() {
    OllamaVisionClient client = clientReturning(NDJSON);
    List<String> seen = new ArrayList<>();

    assertThatThrownBy(() -> client.streamVision(List.of("img"), "prompt", true, chunk -> {
      seen.add(chunk);
      if (seen.size() == 2) {
        throw new StopStream();
      }
    })).isInstanceOf(StopStream.class);

    assertThat(seen).containsExactly("AAAA", "BBBB");
  }

  @Test
  void wellFormedStream_deliversEveryChunk() {
    OllamaVisionClient client = clientReturning(NDJSON);
    List<String> seen = new ArrayList<>();

    client.streamVision(List.of("img"), "prompt", true, seen::add);

    assertThat(seen).containsExactly("AAAA", "BBBB", "CCCC");
  }

  @Test
  void malformedJson_isStillReportedAsAParseFailure() {
    OllamaVisionClient client = clientReturning("{\"message\":{\"content\":\"ok\"}\n");

    assertThatThrownBy(() -> client.streamVision(List.of("img"), "prompt", true, chunk -> {
    })).isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Failed to parse Ollama stream response");
  }
}
