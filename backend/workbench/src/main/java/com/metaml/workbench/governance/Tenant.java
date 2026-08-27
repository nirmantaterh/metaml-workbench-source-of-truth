package com.metaml.workbench.governance;

import java.time.Instant;

// Minimal tenant identity record.
public record Tenant(String id, String name, Instant createdAt) {
}
