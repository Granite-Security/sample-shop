package org.granitesecurity.storage.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.test.StepVerifier;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectResponse;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.net.URI;
import java.net.URL;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StorageServiceTest {

    private static final String BUCKET = "product-media";
    private static final String PUBLIC_BASE_URL = "http://product-media.localhost:3902";

    @Mock
    private S3Presigner s3Presigner;

    @Mock
    private S3Client s3Client;

    @Mock
    private PresignedPutObjectRequest presignedPutObjectRequest;

    private StorageService storageService;

    @BeforeEach
    void setUp() {
        storageService = new StorageService(s3Presigner, s3Client, BUCKET, PUBLIC_BASE_URL);
    }

    @Test
    void presignShouldReturnKeyPrefixedByScopeAndPublicUrl() throws Exception {
        URL url = URI.create("http://localhost:3900/product-media/products/abc/hero.jpg?signed=1").toURL();
        when(presignedPutObjectRequest.url()).thenReturn(url);
        when(s3Presigner.presignPutObject(any(PutObjectPresignRequest.class)))
                .thenReturn(presignedPutObjectRequest);

        StepVerifier.create(storageService.presign("hero.jpg", "image/jpeg", "products"))
                .assertNext(response -> {
                    assert response.key().startsWith("products/");
                    assert response.key().endsWith("hero.jpg");
                    assert response.uploadUrl().equals(url.toString());
                    assert response.publicUrl().equals(PUBLIC_BASE_URL + "/" + response.key());
                    assert response.expiresIn() == 600L;
                })
                .verifyComplete();
    }

    @Test
    void presignShouldSanitizeFileNameFromPathSegments() throws Exception {
        URL url = URI.create("http://localhost:3900/product-media/products/abc/evil.txt?signed=1").toURL();
        when(presignedPutObjectRequest.url()).thenReturn(url);
        when(s3Presigner.presignPutObject(any(PutObjectPresignRequest.class)))
                .thenReturn(presignedPutObjectRequest);

        StepVerifier.create(storageService.presign("../../etc/evil.txt", "image/png", "products"))
                .assertNext(response -> {
                    assert !response.key().contains("..");
                    assert !response.key().contains("/etc/");
                })
                .verifyComplete();
    }

    @Test
    void presignShouldRejectDisallowedContentType() {
        StepVerifier.create(storageService.presign("hero.jpg", "application/pdf", "products"))
                .expectErrorMatches(ex -> ex instanceof StorageException)
                .verify();

        verifyNoInteractions(s3Presigner);
    }

    @Test
    void presignShouldRejectDisallowedScope() {
        StepVerifier.create(storageService.presign("hero.jpg", "image/jpeg", "avatars"))
                .expectErrorMatches(ex -> ex instanceof StorageException)
                .verify();

        verifyNoInteractions(s3Presigner);
    }

    @Test
    void deleteObjectShouldCallS3ForAllowedPrefix() {
        when(s3Client.deleteObject(any(DeleteObjectRequest.class)))
                .thenReturn(DeleteObjectResponse.builder().build());

        StepVerifier.create(storageService.deleteObject("products/abc/hero.jpg"))
                .verifyComplete();

        verify(s3Client).deleteObject(any(DeleteObjectRequest.class));
    }

    @Test
    void deleteObjectShouldRejectKeyOutsideAllowedPrefix() {
        StepVerifier.create(storageService.deleteObject("../secrets/private.jpg"))
                .expectErrorMatches(ex -> ex instanceof StorageException)
                .verify();

        verifyNoInteractions(s3Client);
    }
}
