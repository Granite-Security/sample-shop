package org.granitesecurity.authserver.user;

public class NonLocalAccountException extends RuntimeException {

    public NonLocalAccountException(String message) {
        super(message);
    }
}
