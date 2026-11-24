package org.paulsens.trip.security;

import java.util.function.Supplier;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.PutSecretValueRequest;

/**
 * One Secrets Manager secret, read and replaced as a string: the store behind {@link ProcessorSecrets} and the
 * push secrets. A client is built per call (the {@link Pepper} pattern): both owners cache what they read for a
 * minute, and a write is a rare admin action.
 */
public final class SecretsManagerReader implements ProcessorSecrets.Store {
    private final String secretId;
    /** Test seam; null means build a real per-call client. */
    private final Supplier<SecretsManagerClient> clients;

    public SecretsManagerReader(final String secretId, final Supplier<SecretsManagerClient> clients) {
        this.secretId = secretId;
        this.clients = clients;
    }

    @Override
    public String read() {
        try (SecretsManagerClient client = client()) {
            return client.getSecretValue(GetSecretValueRequest.builder().secretId(secretId).build())
                    .secretString();
        }
    }

    @Override
    public void write(final String json) {
        try (SecretsManagerClient client = client()) {
            client.putSecretValue(PutSecretValueRequest.builder().secretId(secretId).secretString(json).build());
        }
    }

    /** Public (with {@link #resolveRegion}) so the no-network construction path is testable. */
    public SecretsManagerClient client() {
        if (clients != null) {
            return clients.get();
        }
        return SecretsManagerClient.builder()
                .region(resolveRegion())
                .credentialsProvider(DefaultCredentialsProvider.builder().build())
                .build();
    }

    /**
     * The secrets' region. Deliberately NOT {@code Env.awsRegion()}: this ladder takes a system property that is
     * set but blank as the answer (us-west-2) without consulting the environment, and does not trim. Both secret
     * owners have always read it this way, and a credentials path is the wrong place to change precedence in
     * passing.
     */
    public static Region resolveRegion() {
        final String dynamoRegion = System.getProperty("trip.dynamo.region",
                System.getenv().getOrDefault("TRIP_DYNAMO_REGION", ""));
        return dynamoRegion.isBlank() ? Region.US_WEST_2 : Region.of(dynamoRegion);
    }
}
