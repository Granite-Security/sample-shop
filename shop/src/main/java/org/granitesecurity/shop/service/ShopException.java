package org.granitesecurity.shop.service;

import org.springframework.http.HttpStatus;

public class ShopException extends RuntimeException {

    private final HttpStatus status;
    private final String title;

    public ShopException(String message) {
        this(message, HttpStatus.BAD_REQUEST, "Bad Request");
    }

    public ShopException(String message, HttpStatus status, String title) {
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
