package com.dbagnets.backend.engine.driver.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

class HttpClientsTest {

    @Test
    void basicReturnsWebClient() {
        WebClient client = HttpClients.basic("localhost", 8080);
        assertThat(client).isNotNull();
    }

    @Test
    void withAuthHeaderReturnsWebClient() {
        WebClient client = HttpClients.withAuthHeader("localhost", 8080, "Basic xyz");
        assertThat(client).isNotNull();
    }

    @Test
    void largeReturnsWebClient() {
        WebClient client = HttpClients.large("host", 9200);
        assertThat(client).isNotNull();
    }

    @Test
    void largeWithAuthHeaderReturnsWebClient() {
        WebClient client = HttpClients.largeWithAuthHeader("host", 8086, "Token xyz");
        assertThat(client).isNotNull();
    }
}
