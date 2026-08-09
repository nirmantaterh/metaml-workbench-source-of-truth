package com.metaml.workbench.generation;

import java.io.IOException;
import java.io.UncheckedIOException;

// One specific generated delegate's own file failed to write - carries which one, since that's
// already known right there in SpringBootProjectGenerator.writeDelegates' own loop and would
// otherwise be lost the moment it's wrapped in a bare UncheckedIOException the way every other
// file write in that class is. This is the one point in the whole generate pipeline that's
// genuinely scoped to a single BPMN element rather than the operation as a whole - a missing
// template directory or an unparsable BPMN document isn't attributable to one delegate, but a
// failure writing THIS delegate's file, mid-loop, is. Extends UncheckedIOException so nothing
// downstream that only expects that type breaks.
public class DelegateWriteException extends UncheckedIOException {

    private final String beanName;
    private final String bpmnElementId;

    public DelegateWriteException(String message, IOException cause, String beanName, String bpmnElementId) {
        super(message, cause);
        this.beanName = beanName;
        this.bpmnElementId = bpmnElementId;
    }

    public String beanName() {
        return beanName;
    }

    // null when the delegate that failed to write is itself shared by more than one BPMN element
    // (see GeneratedDelegate's own comment on why that's left null there) - not decided here,
    // just carried through unchanged from the GeneratedDelegate that failed
    public String bpmnElementId() {
        return bpmnElementId;
    }
}
