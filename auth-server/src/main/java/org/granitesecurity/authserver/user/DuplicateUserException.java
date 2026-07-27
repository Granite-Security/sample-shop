package org.granitesecurity.authserver.user;

public class DuplicateUserException extends RuntimeException {

    private final String field;

    public DuplicateUserException(String field, String message) {
        super(message);
        this.field = field;
    }

    public String getField() {
        return field;
    }
}
