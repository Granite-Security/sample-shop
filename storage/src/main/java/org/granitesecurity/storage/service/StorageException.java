package org.granitesecurity.storage.service;

import org.springframework.http.HttpStatus;

public class StorageException extends RuntimeException {

    private final HttpStatus status;
    private final String title;

    public StorageException(String message) {
        this(message, HttpStatus.BAD_REQUEST, "Bad Request");
    }

    public StorageException(String message, HttpStatus status, String title) {
        super(message);
        this.status = status;
        this.title = title;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getTitle() {
        return title;
    }
}
