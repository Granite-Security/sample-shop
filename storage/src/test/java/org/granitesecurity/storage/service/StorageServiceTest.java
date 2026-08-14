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
import java.util.Set;

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

    private static final Set<String> ADMIN = Set.of("ROLE_ADMIN");
    private static final Set<String> PLAIN_USER = Set.of("SCOPE_openid");

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

        StepVerifier.create(storageService.presign("hero.jpg", "image/jpeg", "products", ADMIN, "admin"))
                .assertNext(response -> {
                    assert response.key().startsWith("products/");
                    assert response.key().endsWith("hero.jpg");
                    assert response.uploadUrl().equals(url.toString());
                    assert response.publicUrl().equals(PUBLIC_BASE_URL + "/" + response.key());
                    assert response.expiresIn() == 7200L;
                })
                .verifyComplete();
    }

    @Test
    void presignShouldSanitizeFileNameFromPathSegments() throws Exception {
        URL url = URI.create("http://localhost:3900/product-media/products/abc/evil.txt?signed=1").toURL();
        when(presignedPutObjectRequest.url()).thenReturn(url);
        when(s3Presigner.presignPutObject(any(PutObjectPresignRequest.class)))
                .thenReturn(presignedPutObjectRequest);

        StepVerifier.create(storageService.presign("../../etc/evil.txt", "image/png", "products", ADMIN, "admin"))
                .assertNext(response -> {
                    assert !response.key().contains("..");
                    assert !response.key().contains("/etc/");
                })
                .verifyComplete();
    }

    @Test
    void presignShouldRejectDisallowedContentType() {
        StepVerifier.create(storageService.presign("hero.jpg", "application/pdf", "products", ADMIN, "admin"))
                .expectErrorMatches(ex -> ex instanceof StorageException)
                .verify();

        verifyNoInteractions(s3Presigner);
    }

    /**
     * The scope is an allow-list, so an unrecognised one is refused before anything
     * is signed. This named "avatars" until avatars became a real scope, at which
     * point it started asserting a rejection that no longer happens — hence a scope
     * that cannot become legitimate by a later feature landing.
     */
    @Test
    void presignShouldRejectUnknownScope() {
        StepVerifier.create(storageService.presign("hero.jpg", "image/jpeg", "not-a-scope", ADMIN, "admin"))
                .expectErrorMatches(ex -> ex instanceof StorageException)
                .verify();

        verifyNoInteractions(s3Presigner);
    }

    @Test
    void deleteObjectShouldCallS3ForAllowedPrefix() {
        when(s3Client.deleteObject(any(DeleteObjectRequest.class)))
                .thenReturn(DeleteObjectResponse.builder().build());

        StepVerifier.create(storageService.deleteObject("products/abc/hero.jpg", ADMIN))
                .verifyComplete();

        verify(s3Client).deleteObject(any(DeleteObjectRequest.class));
    }

    @Test
    void deleteObjectShouldRejectKeyOutsideAllowedPrefix() {
        StepVerifier.create(storageService.deleteObject("../secrets/private.jpg", ADMIN))
                .expectErrorMatches(ex -> ex instanceof StorageException)
                .verify();

        verifyNoInteractions(s3Client);
    }

    @Test
    void presignShouldAllowPlainUserForUserFilesScope() throws Exception {
        URL url = URI.create("http://localhost:3900/product-media/user-files/abc/note.pdf?signed=1").toURL();
        when(presignedPutObjectRequest.url()).thenReturn(url);
        when(s3Presigner.presignPutObject(any(PutObjectPresignRequest.class)))
                .thenReturn(presignedPutObjectRequest);

        StepVerifier.create(storageService.presign("note.pdf", "application/pdf", "user-files", PLAIN_USER, "alice"))
                .assertNext(response -> {
                    assert response.key().startsWith("user-files/alice/");
                })
                .verifyComplete();
    }

    @Test
    void presignShouldSanitizeUsernameSegment() throws Exception {
        URL url = URI.create("http://localhost:3900/product-media/user-files/abc/note.pdf?signed=1").toURL();
        when(presignedPutObjectRequest.url()).thenReturn(url);
        when(s3Presigner.presignPutObject(any(PutObjectPresignRequest.class)))
                .thenReturn(presignedPutObjectRequest);

        StepVerifier.create(storageService.presign(
                        "note.pdf", "application/pdf", "user-files", PLAIN_USER, "../../etc/passwd"))
                .assertNext(response -> {
                    assert !response.key().contains("..");
                    assert !response.key().contains("/etc/");
                    assert response.key().startsWith("user-files/");
                })
                .verifyComplete();
    }

    @Test
    void presignShouldNotAddUsernameSegmentForProductsScope() throws Exception {
        URL url = URI.create("http://localhost:3900/product-media/products/abc/hero.jpg?signed=1").toURL();
        when(presignedPutObjectRequest.url()).thenReturn(url);
        when(s3Presigner.presignPutObject(any(PutObjectPresignRequest.class)))
                .thenReturn(presignedPutObjectRequest);

        StepVerifier.create(storageService.presign("hero.jpg", "image/jpeg", "products", ADMIN, "admin"))
                .assertNext(response -> {
                    assert !response.key().contains("/admin/");
                })
                .verifyComplete();
    }

    @Test
    void presignShouldRejectPlainUserForProductsScope() {
        StepVerifier.create(storageService.presign("hero.jpg", "image/jpeg", "products", PLAIN_USER, "alice"))
                .expectErrorMatches(ex -> ex instanceof StorageException)
                .verify();

        verifyNoInteractions(s3Presigner);
    }

    @Test
    void deleteObjectShouldRejectPlainUserForProductsScope() {
        StepVerifier.create(storageService.deleteObject("products/abc/hero.jpg", PLAIN_USER))
                .expectErrorMatches(ex -> ex instanceof StorageException)
                .verify();

        verifyNoInteractions(s3Client);
    }
}
