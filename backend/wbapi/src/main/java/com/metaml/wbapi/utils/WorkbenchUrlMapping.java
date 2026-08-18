package com.metaml.wbapi.utils;

public class WorkbenchUrlMapping {
    public static final String HOME = "/";
    public static final String API = "/api/v1";

    public static final String WORKBENCH = API + "/wb";

    /* ======== Start Transmute API ======== */
    public static final String WB_TRANSMUTE = "/transmute";
    public static final String TRANSMUTE_CREATE = WB_TRANSMUTE + "/create";
    public static final String TRANSMUTE_CONNECT = WB_TRANSMUTE + "/connect";
    public static final String TRANSMUTE_MODELE = WB_TRANSMUTE + "/model";
    // First-class alternative to TRANSMUTE_MODELE for a model with its own independently authored
    // second BPMN (e.g. a Manufacturing + Twin pair supplied as two separate files), routed to
    // WorkbenchService.saveProcessModelWithAuthoredTwin - see that method's own comment. Generation
    // itself still goes through the existing TRANSMUTE_GENERATE_PROJECT endpoint unchanged; only
    // saving needs its own entry point, since it's the one place the extra BPMN has to be supplied.
    public static final String TRANSMUTE_MODELE_AUTHORED_TWIN = TRANSMUTE_MODELE + "/authored-twin";
    public static final String TRANSMUTE_GENERATE = WB_TRANSMUTE + "/generate";
    public static final String TRANSMUTE_GENERATE_PROJECT = WB_TRANSMUTE + "/generate-project";
    // named distinctly from TRANSMUTE_LAUNCH, which starts a twin process instance - this starts
    // a generated Spring Boot app as its own process, an unrelated notion of "launch"
    public static final String TRANSMUTE_LAUNCH_PROJECT = WB_TRANSMUTE + "/launch-project";
    public static final String TRANSMUTE_STOP_PROJECT = WB_TRANSMUTE + "/stop-project";
    public static final String TRANSMUTE_RUNNING_PROJECTS = WB_TRANSMUTE + "/running-projects";
    // path-variable form deliberately, not a query param - this reads a specific model's state,
    // same shape as TRANSMUTE_MODELE + "/{id}" just above it
    public static final String TRANSMUTE_WORKFLOW = TRANSMUTE_MODELE + "/{id}/workflow";
    public static final String TRANSMUTE_LAUNCH = WB_TRANSMUTE + "/launch";
    public static final String TRANSMUTE_EVOLVE = WB_TRANSMUTE + "/evolve";
    // Phase 4 (approval workflow) - nested under evolve since an approval only ever exists for
    // a paused evolution, never anything else
    public static final String TRANSMUTE_EVOLVE_APPROVALS = TRANSMUTE_EVOLVE + "/approvals";
    public static final String TRANSMUTE_TWIN = WB_TRANSMUTE + "/twin";
    public static final String TRANSMUTE_BRIDGE = WB_TRANSMUTE + "/bridge";
    public static final String TRANSMUTE_COMPLETE_TASK = WB_TRANSMUTE + "/complete-task";

    public static final String TRANSMUTE_SAMPLE_ONLY = WB_TRANSMUTE + "/sample";
    /* ======== End Transmute API ======== */

    /* ======== Start Governance API ======== */
    public static final String GOVERNANCE = API + "/governance";
    public static final String GOVERNANCE_POLICY = "/policy";
    public static final String GOVERNANCE_USAGE = "/usage";

    // Phase 1 (tenant identity + persistent tenant-scoped policies) - a separate controller
    // from the pre-existing deny-list/quota GovernanceController above, mounted under the same
    // GOVERNANCE base since neither's sub-paths collide with the other's
    public static final String GOVERNANCE_TENANTS = "/tenants";
    public static final String GOVERNANCE_PLATFORM_POLICIES = "/platform-policies";
    public static final String GOVERNANCE_POLICIES = "/policies";
    public static final String GOVERNANCE_POLICY_VERSIONS = "/policy-versions";

    // Phase 2 (policy decision engine) - decides only, executes nothing. Same GOVERNANCE base
    // as everything above.
    public static final String GOVERNANCE_EVALUATE = "/evaluate";
    /* ======== End Governance API ======== */
}
