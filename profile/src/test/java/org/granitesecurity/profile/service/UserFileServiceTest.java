package org.granitesecurity.profile.service;

import org.granitesecurity.profile.client.StorageClient;
import org.granitesecurity.profile.client.StoragePresignResult;
import org.granitesecurity.profile.domain.UserFile;
import org.granitesecurity.profile.dto.PresignFileRequest;
import org.granitesecurity.profile.dto.RegisterFileRequest;
import org.granitesecurity.profile.repository.UserFileRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserFileServiceTest {

    private static final String PUBLIC_BASE_URL = "http://product-media.localhost:3902";

    @Mock
    private UserFileRepository userFileRepository;

    @Mock
    private StorageClient storageClient;

    private UserFileService userFileService;

    private UserFileService newService() {
        return new UserFileService(userFileRepository, storageClient, PUBLIC_BASE_URL);
    }

    @Test
    void presignRejectsDisallowedContentType() {
        userFileService = newService();
        var req = new PresignFileRequest("evil.exe", "application/x-msdownload", 100L);

        StepVerifier.create(userFileService.presign("alice", req))
                .expectErrorMatches(e -> e instanceof ResponseStatusException rse
                        && rse.getStatusCode() == HttpStatus.BAD_REQUEST)
                .verify();

        verifyNoInteractions(storageClient);
    }

    @Test
    void presignRejectsOversizedFile() {
        userFileService = newService();
        var req = new PresignFileRequest("big.pdf", "application/pdf", 11L * 1024 * 1024);

        StepVerifier.create(userFileService.presign("alice", req))
                .expectErrorMatches(e -> e instanceof ResponseStatusException rse
                        && rse.getStatusCode() == HttpStatus.BAD_REQUEST)
                .verify();

        verifyNoInteractions(storageClient);
    }

    @Test
    void presignRejectsWhenFileCountAtLimit() {
        userFileService = newService();
        when(userFileRepository.countByUsername("alice")).thenReturn(Mono.just(50L));

        var req = new PresignFileRequest("note.pdf", "application/pdf", 100L);

        StepVerifier.create(userFileService.presign("alice", req))
                .expectErrorMatches(e -> e instanceof ResponseStatusException rse
                        && rse.getStatusCode() == HttpStatus.BAD_REQUEST)
                .verify();

        verifyNoInteractions(storageClient);
    }

    @Test
    void presignDelegatesToStorageClientWhenValid() {
        userFileService = newService();
        when(userFileRepository.countByUsername("alice")).thenReturn(Mono.just(1L));
        when(storageClient.presign("note.pdf", "application/pdf")).thenReturn(Mono.just(
                new StoragePresignResult("user-files/abc/note.pdf", "http://upload", "http://public", 600L)));

        var req = new PresignFileRequest("note.pdf", "application/pdf", 100L);

        StepVerifier.create(userFileService.presign("alice", req))
                .expectNextMatches(res -> res.key().equals("user-files/abc/note.pdf"))
                .verifyComplete();
    }

    @Test
    void registerRejectsKeyWithoutUserFilesPrefix() {
        userFileService = newService();
        var req = new RegisterFileRequest("products/abc/note.pdf", "http://evil", "note.pdf", "application/pdf", 100L);

        StepVerifier.create(userFileService.register("alice", req))
                .expectErrorMatches(e -> e instanceof ResponseStatusException rse
                        && rse.getStatusCode() == HttpStatus.BAD_REQUEST)
                .verify();

        verifyNoInteractions(userFileRepository);
    }

    @Test
    void registerRecomputesUrlServerSideRatherThanTrustingRequestBody() {
        userFileService = newService();
        when(userFileRepository.existsByObjectKey("user-files/abc/note.pdf")).thenReturn(Mono.just(false));
        when(userFileRepository.save(any(UserFile.class))).thenAnswer(inv -> {
            UserFile saved = inv.getArgument(0);
            saved.setId(1L);
            saved.setCreatedAt(Instant.now());
            return Mono.just(saved);
        });

        var req = new RegisterFileRequest(
                "user-files/abc/note.pdf", "http://attacker-controlled/evil", "note.pdf", "application/pdf", 100L);

        StepVerifier.create(userFileService.register("alice", req))
                .expectNextMatches(res -> res.url().equals(PUBLIC_BASE_URL + "/user-files/abc/note.pdf"))
                .verifyComplete();
    }

    @Test
    void registerRejectsDuplicateObjectKey() {
        userFileService = newService();
        when(userFileRepository.existsByObjectKey(anyString())).thenReturn(Mono.just(true));

        var req = new RegisterFileRequest("user-files/abc/note.pdf", "ignored", "note.pdf", "application/pdf", 100L);

        StepVerifier.create(userFileService.register("alice", req))
                .expectErrorMatches(e -> e instanceof ResponseStatusException rse
                        && rse.getStatusCode() == HttpStatus.CONFLICT)
                .verify();
    }

    @Test
    void deleteCrossUserReturnsNotFound() {
        userFileService = newService();
        when(userFileRepository.findByIdAndUsername(1L, "bob")).thenReturn(Mono.empty());

        StepVerifier.create(userFileService.delete(1L, "bob"))
                .expectErrorMatches(e -> e instanceof ResponseStatusException rse
                        && rse.getStatusCode() == HttpStatus.NOT_FOUND)
                .verify();

        verifyNoInteractions(storageClient);
    }

    @Test
    void deleteKeepsRowWhenStorageDeleteFails() {
        userFileService = newService();
        UserFile file = new UserFile();
        file.setId(1L);
        file.setUsername("alice");
        file.setObjectKey("user-files/abc/note.pdf");
        when(userFileRepository.findByIdAndUsername(1L, "alice")).thenReturn(Mono.just(file));
        when(storageClient.delete("user-files/abc/note.pdf")).thenReturn(
                Mono.error(new ResponseStatusException(HttpStatus.BAD_GATEWAY, "storage request failed")));

        StepVerifier.create(userFileService.delete(1L, "alice"))
                .expectErrorMatches(e -> e instanceof ResponseStatusException rse
                        && rse.getStatusCode() == HttpStatus.BAD_GATEWAY)
                .verify();

        verify(userFileRepository, org.mockito.Mockito.never()).delete(any(UserFile.class));
    }

    @Test
    void deleteRemovesRowWhenStorageDeleteSucceeds() {
        userFileService = newService();
        UserFile file = new UserFile();
        file.setId(1L);
        file.setUsername("alice");
        file.setObjectKey("user-files/abc/note.pdf");
        when(userFileRepository.findByIdAndUsername(1L, "alice")).thenReturn(Mono.just(file));
        when(storageClient.delete("user-files/abc/note.pdf")).thenReturn(Mono.empty());
        when(userFileRepository.delete(file)).thenReturn(Mono.empty());

        StepVerifier.create(userFileService.delete(1L, "alice"))
                .verifyComplete();

        verify(userFileRepository).delete(file);
    }
}
