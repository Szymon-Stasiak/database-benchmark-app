package com.dbagnets.backend.engine.driver.dynamo;

import com.dbagnets.backend.engine.driver.ConnectionCache;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import java.net.URI;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class DynamodbClientCache implements ConnectionCache {

    private final Map<String, DynamoDbClient> clients = new ConcurrentHashMap<>();

    public DynamoDbClient get(String databaseId, String host, int port) {
        return clients.computeIfAbsent(databaseId, id -> create(host, port));
    }

    @Override
    public void evict(String databaseId) {
        DynamoDbClient client = clients.remove(databaseId);
        if (client != null) {
            client.close();
        }
    }

    @PreDestroy
    public void shutdown() {
        clients.values().forEach(DynamoDbClient::close);
        clients.clear();
    }

    private DynamoDbClient create(String host, int port) {
        return DynamoDbClient.builder().endpointOverride(URI.create("http://" + host + ":" + port)).region(Region.US_EAST_1).credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("dummy", "dummy"))).httpClient(UrlConnectionHttpClient.create()).build();
    }
}