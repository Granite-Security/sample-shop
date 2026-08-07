package org.granitesecurity.shop.consumer;

/**
 * The message will never be understood, however many times it is redelivered.
 *
 * <p>The distinction this draws is the whole point of the retry policy: a bad message
 * goes straight to the dead-letter topic, while a good message that arrived at a bad
 * moment — a slow database, a dropped connection — is retried. Before this existed both
 * were caught and logged, which committed the offset and lost the transition for good.
 */
public class MalformedEventException extends RuntimeException {

    public MalformedEventException(String message, Throwable cause) {
        super(message, cause);
    }

    public MalformedEventException(String message) {
        super(message);
    }
}
