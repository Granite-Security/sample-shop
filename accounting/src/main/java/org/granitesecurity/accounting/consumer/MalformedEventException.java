package org.granitesecurity.accounting.consumer;

/** A message that will not parse will not parse on the fifth attempt either. */
public class MalformedEventException extends RuntimeException {
    public MalformedEventException(String message, Throwable cause) {
        super(message, cause);
    }
}
