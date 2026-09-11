package com.demo.cs.infrastructure.resources.transport;

/** Safe public error: never contains a provider response body or credentials. */
public class TransportException extends IllegalStateException {
    private final String code;
    public TransportException(String code, String message) { super(message); this.code = code; }
    public String code() { return code; }
}
