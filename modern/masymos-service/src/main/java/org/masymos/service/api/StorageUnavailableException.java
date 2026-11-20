package org.masymos.service.api;

public class StorageUnavailableException extends RuntimeException {
    public StorageUnavailableException(String message) {
        super(message);
    }
}
