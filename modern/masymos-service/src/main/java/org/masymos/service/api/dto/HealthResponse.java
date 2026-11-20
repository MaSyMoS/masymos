package org.masymos.service.api.dto;

/**
 * The payload of {@code GET /api/v1/health}.
 *
 * @param status {@code UP} while the service answers, {@code DOWN} when the storage probe failed
 * @param storage {@code UP}, {@code DOWN}, or {@code disabled} when no store is configured at all
 */
public record HealthResponse(String status, String storage) {
}
