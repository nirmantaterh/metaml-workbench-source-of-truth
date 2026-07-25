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
    public static final String TRANSMUTE_GENERATE = WB_TRANSMUTE + "/generate";
    public static final String TRANSMUTE_LAUNCH = WB_TRANSMUTE + "/launch";
    public static final String TRANSMUTE_EVOLVE = WB_TRANSMUTE + "/evolve";
    public static final String TRANSMUTE_TWIN = WB_TRANSMUTE + "/twin";
    public static final String TRANSMUTE_BRIDGE = WB_TRANSMUTE + "/bridge";

    public static final String TRANSMUTE_SAMPLE_ONLY = WB_TRANSMUTE + "/sample";
    /* ======== End Transmute API ======== */

    /* ======== Start Governance API ======== */
    public static final String GOVERNANCE = API + "/governance";
    public static final String GOVERNANCE_POLICY = "/policy";
    public static final String GOVERNANCE_USAGE = "/usage";
    /* ======== End Governance API ======== */
}
