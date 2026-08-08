package com.metaml.workbench.generation;

// A launch failure that knows the port it was actually attempting (and, when the generated
// process exited on its own rather than just timing out, its exit code) - every launch failure
// used to carry only a plain message. Extends IllegalStateException, not RuntimeException
// directly, so existing callers/tests that only ever asserted "launch() throws
// IllegalStateException with such-and-such message" keep working unchanged - this is strictly
// additive information riding along on the same exception, not a new failure mode.
public class GeneratedProjectLaunchException extends IllegalStateException {

    private final int port;
    private final Integer exitCode;

    public GeneratedProjectLaunchException(String message, int port, Integer exitCode, Throwable cause) {
        super(message, cause);
        this.port = port;
        this.exitCode = exitCode;
    }

    public int port() {
        return port;
    }

    // null when the generated process never actually exited on its own (a pure listen timeout
    // rather than a crash) - there's no real exit code to report in that case, not a missing one
    public Integer exitCode() {
        return exitCode;
    }
}
