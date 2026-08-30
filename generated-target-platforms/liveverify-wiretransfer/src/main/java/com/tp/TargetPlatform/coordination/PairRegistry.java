package com.tp.TargetPlatform.coordination;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.springframework.stereotype.Component;

// Pairs a proxy instance with its twin by the caller-supplied business key both /start endpoints accept - no BPMN-specific knowledge. The first process instance to register a given business key is the "initiator" (proxy, in this generated platform's own usage); the next instance to register the SAME key is the "responder" (twin). A business key is pairing/correlation data only, not the communication mechanism itself - see SignalBroadcaster for how these roles turn each shared signal into a real, targeted proxy -> twin -> proxy handoff instead of an undifferentiated broadcast.
@Component
public class PairRegistry {

    private final ConcurrentMap<String, String> initiators = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> responders = new ConcurrentHashMap<>();

    // Returns "initiator" for the first instance registered under businessKey, "responder" for the second, and null for a blank key or a third-or-later instance sharing an already-claimed key - unpaired, callers fall back to their own default behavior.
    public String registerAndClassify(String businessKey, String processInstanceId) {
        if (businessKey == null || businessKey.isBlank()) {
            return null;
        }
        String initiator = initiators.putIfAbsent(businessKey, processInstanceId);
        if (initiator == null || initiator.equals(processInstanceId)) {
            return "initiator";
        }
        String responder = responders.putIfAbsent(businessKey, processInstanceId);
        if (responder == null || responder.equals(processInstanceId)) {
            return "responder";
        }
        return null;
    }

    // The other half of the pair for this business key, or null if unpaired.
    public String partnerOf(String businessKey, String processInstanceId) {
        if (businessKey == null || businessKey.isBlank()) {
            return null;
        }
        String initiator = initiators.get(businessKey);
        String responder = responders.get(businessKey);
        if (processInstanceId.equals(initiator)) {
            return responder;
        }
        if (processInstanceId.equals(responder)) {
            return initiator;
        }
        return null;
    }

    public String roleOf(String businessKey, String processInstanceId) {
        if (businessKey == null || businessKey.isBlank()) {
            return null;
        }
        if (processInstanceId.equals(initiators.get(businessKey))) {
            return "initiator";
        }
        if (processInstanceId.equals(responders.get(businessKey))) {
            return "responder";
        }
        return null;
    }
}
