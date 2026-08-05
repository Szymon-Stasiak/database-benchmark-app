package com.dbagnets.backend.engine.driver;

import org.springframework.web.reactive.function.client.WebClient;

public final class HttpClients {

    private HttpClients() {}

    public static WebClient basic(String host, int port) {
        return WebClient.builder()
                .baseUrl(baseUrl(host, port))
                .build();
    }

    public static WebClient withAuthHeader(String host, int port, String authValue) {
        return WebClient.builder()
                .baseUrl(baseUrl(host, port))
                .defaultHeader("Authorization", authValue)
                .build();
    }

    public static WebClient large(String host, int port) {
        return WebClient.builder()
                .baseUrl(baseUrl(host, port))
                .codecs(c -> c.defaultCodecs().maxInMemorySize(DriverValues.WEBCLIENT_MAX_IN_MEMORY_BYTES))
                .build();
    }

    public static WebClient largeWithAuthHeader(String host, int port, String authValue) {
        return WebClient.builder()
                .baseUrl(baseUrl(host, port))
                .defaultHeader("Authorization", authValue)
                .codecs(c -> c.defaultCodecs().maxInMemorySize(DriverValues.WEBCLIENT_MAX_IN_MEMORY_BYTES))
                .build();
    }

    private static String baseUrl(String host, int port) {
        return "http://" + host + ":" + port;
    }
}
