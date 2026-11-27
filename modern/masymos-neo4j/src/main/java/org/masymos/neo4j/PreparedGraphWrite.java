package org.masymos.neo4j;

import java.util.Map;

record PreparedGraphWrite(
        String cypher,
        Map<String, Object> parameters
) {
}
