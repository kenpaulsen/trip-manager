package org.paulsens.trip.dynamo;

import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import software.amazon.awssdk.http.SdkHttpResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemResponse;

/** For the DAO tests of a write DynamoDB turns down: the save answers false and the version is put back. */
final class RefusingPersistence {

    private RefusingPersistence() {
    }

    /** A mocked {@link Persistence} whose every put answers HTTP 500. A fresh mock per call. */
    static Persistence refusingPuts() {
        final Persistence refusing = Mockito.mock(Persistence.class);
        final PutItemResponse failed = (PutItemResponse) PutItemResponse.builder()
                .sdkHttpResponse(SdkHttpResponse.builder().statusCode(500).build()).build();
        Mockito.when(refusing.putItem(ArgumentMatchers.any())).thenReturn(failed);
        return refusing;
    }
}
