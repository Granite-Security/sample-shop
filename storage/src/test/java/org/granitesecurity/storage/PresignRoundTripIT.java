package org.granitesecurity.storage;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Step-1 spike (docs/plans/storage.md): proves the exact production call chain —
 * AWS SDK v2 S3Presigner (path-style, endpoint override) → raw HTTP PUT of bytes
 * against Garage → anonymous GET of the same object (public bucket).
 *
 * Self-skips unless a bootstrapped local Garage is reachable:
 *   docker compose up -d garage && ./storage/local/garage-init.sh
 * and STORAGE_S3_ACCESS_KEY / STORAGE_S3_SECRET_KEY are set (script output).
 */
class PresignRoundTripIT {

    private static final String ENDPOINT = env("STORAGE_S3_ENDPOINT", "http://localhost:3900");
    private static final String REGION = env("STORAGE_S3_REGION", "garage");
    private static final String BUCKET = env("STORAGE_S3_BUCKET", "product-media");
    private static final String ACCESS_KEY = env("STORAGE_S3_ACCESS_KEY", "");
    private static final String SECRET_KEY = env("STORAGE_S3_SECRET_KEY", "");
    // Public reads go through Garage's s3_web website endpoint (vhost-style).
    private static final String PUBLIC_BASE_URL = env("STORAGE_PUBLIC_BASE_URL",
            "http://product-media.localhost:3902");

    @BeforeAll
    static void requiresRunningGarage() {
        assumeTrue(!ACCESS_KEY.isBlank() && !SECRET_KEY.isBlank(),
                "set STORAGE_S3_ACCESS_KEY/STORAGE_S3_SECRET_KEY from garage-init.sh output");
        URI uri = URI.create(ENDPOINT);
        boolean reachable;
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(uri.getHost(), uri.getPort()), 500);
            reachable = true;
        } catch (IOException e) {
            reachable = false;
        }
        assumeTrue(reachable, "Garage not reachable at " + ENDPOINT + " — run: docker compose up -d garage");
    }

    @Test
    void presignedPutThenAnonymousGetRoundTripsBytes() throws Exception {
        String key = "products/spike/hello-" + System.currentTimeMillis() + ".txt";
        byte[] payload = "granite storage spike".getBytes();

        try (S3Presigner presigner = S3Presigner.builder()
                .endpointOverride(URI.create(ENDPOINT))
                .region(Region.of(REGION))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY)))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build()) {

            PresignedPutObjectRequest presigned = presigner.presignPutObject(PutObjectPresignRequest.builder()
                    .signatureDuration(Duration.ofMinutes(10))
                    .putObjectRequest(PutObjectRequest.builder()
                            .bucket(BUCKET)
                            .key(key)
                            .contentType("text/plain")
                            .build())
                    .build());

            // A browser would do exactly this: raw PUT of the file bytes with the signed headers.
            HttpClient http = HttpClient.newHttpClient();
            HttpRequest.Builder put = HttpRequest.newBuilder(presigned.url().toURI())
                    .PUT(HttpRequest.BodyPublishers.ofByteArray(payload));
            presigned.signedHeaders().forEach((name, values) -> {
                // java.net.http manages Host itself; it's still covered by the signature
                if (!name.equalsIgnoreCase("host")) {
                    values.forEach(value -> put.header(name, value));
                }
            });
            HttpResponse<String> putResponse = http.send(put.build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, putResponse.statusCode(), "presigned PUT failed: " + putResponse.body());
            System.out.println("SPIKE-KEY=" + key);

            // Public bucket: shoppers' browsers read media anonymously from the
            // s3_web website endpoint (path-style /bucket/key).
            HttpResponse<byte[]> getResponse = http.send(
                    HttpRequest.newBuilder(URI.create(PUBLIC_BASE_URL + "/" + key)).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, getResponse.statusCode(), "anonymous GET failed — is the bucket public-read?");
            assertEquals(new String(payload), new String(getResponse.body()));
        }
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value != null ? value : fallback;
    }
}
