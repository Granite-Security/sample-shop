package org.granitesecurity.authserver.user;

public class UserNotBlockedException extends RuntimeException {

    public UserNotBlockedException(String message) {
        super(message);
    }
}
