package com.metaml.workbench.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

// What happened when we tried to move the twin's own token one activity on. Not advancing is the normal case far more often than it's a problem: gateways and end events have no message of their own, and neither has a twin activity the original has already walked past.
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TwinAdvance {
    private boolean advanced;
    private String twinActivityId;
    private String messageName;
    private String reason;

    public static TwinAdvance advanced(String twinActivityId, String messageName) {
        return new TwinAdvance(true, twinActivityId, messageName, "Twin activity executed");
    }

    public static TwinAdvance skipped(String twinActivityId, String reason) {
        return new TwinAdvance(false, twinActivityId, null, reason);
    }
}
