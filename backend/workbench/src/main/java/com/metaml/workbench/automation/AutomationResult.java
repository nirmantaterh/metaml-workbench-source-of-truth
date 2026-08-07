package com.metaml.workbench.automation;

import java.util.Map;

// What a project's automation has to say about one twin activity. The summary is the line that
// ends up on the twin instance as proof the activity really ran; outputs are for anything the
// project wants a gateway further down its own model to be able to read.
public record AutomationResult(String summary, Map<String, Object> outputs) {

    public AutomationResult {
        if (summary == null || summary.isBlank()) {
            throw new IllegalArgumentException("An automation result needs a summary");
        }
        outputs = outputs == null ? Map.of() : Map.copyOf(outputs);
    }

    // most implementations only want to say what they did
    public static AutomationResult of(String summary) {
        return new AutomationResult(summary, Map.of());
    }
}
