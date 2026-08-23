package com.metaml.workbench.codegen;

import java.io.ByteArrayInputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

// Auto-derives a Twin BPMN for the RedCollarTP/TargetPlatform pipeline by mirroring the proxy's
// own graph, rather than TwinModelGenerator's rewrite into receiveTask/serviceTask pairs waiting
// on ${twinAutomationDelegate} - a bean that belongs to the older camundademo-based governance/
// evolve twin workflow and does not exist in a generated Target Platform. A real, hand-authored
// RedCollar Twin (Twin-camunda.bpmn) turns out to already BE this: the same signal catch events
// (same signalRef, so the shared name SignalBroadcaster matches on is preserved) and the same
// external-task activities, just under their own topic names.
//
// Two things change, everything else is copied verbatim:
//   - the process element's own id/name get a "_twin" / " (twin)" suffix - it has to be a
//     distinct process definition, not a second copy of the same one
//   - every camunda:topic gets a "Twin" suffix - external-task topics are a GLOBAL subscription
//     namespace in one Camunda engine (see ExternalTaskPoller), so proxy and twin would otherwise
//     both answer to the identical topic and each other's workers
// signalRef / signal names are deliberately left untouched - that shared name is the entire
// mechanism SignalBroadcaster/PairRegistry use to recognize proxy and twin as synchronizing on
// the same point (see TargetPlatformMessagingGenerator). Activity ids are also left untouched:
// they only need to be unique within one process definition, not across two.
@Component
public class TargetPlatformTwinMirrorGenerator {

    private static final String CAMUNDA_NS = "http://camunda.org/schema/1.0/bpmn";
    private static final String TWIN_ID_SUFFIX = "_twin";
    private static final String TWIN_NAME_SUFFIX = " (twin)";
    private static final String TWIN_TOPIC_SUFFIX = "Twin";

    public String mirror(String proxyBpmnXml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            Document document = factory.newDocumentBuilder().parse(
                    new ByteArrayInputStream(proxyBpmnXml.getBytes(StandardCharsets.UTF_8)));

            retagProcessElement(document);
            suffixExternalTaskTopics(document);

            Transformer transformer = TransformerFactory.newInstance().newTransformer();
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
            transformer.setOutputProperty(OutputKeys.INDENT, "yes");
            StringWriter xml = new StringWriter();
            transformer.transform(new DOMSource(document), new StreamResult(xml));
            return xml.toString();
        } catch (Exception e) {
            throw new IllegalArgumentException("Could not mirror BPMN into a Twin: " + e.getMessage(), e);
        }
    }

    private static void retagProcessElement(Document document) {
        NodeList processes = document.getElementsByTagNameNS("*", "process");
        for (int i = 0; i < processes.getLength(); i++) {
            Element process = (Element) processes.item(i);
            if (!"true".equals(process.getAttribute("isExecutable"))) continue;
            String id = process.getAttribute("id");
            if (id != null && !id.isBlank()) {
                process.setAttribute("id", id + TWIN_ID_SUFFIX);
            }
            String name = process.getAttribute("name");
            if (name != null && !name.isBlank()) {
                process.setAttribute("name", name + TWIN_NAME_SUFFIX);
            }
        }
    }

    // One worker's topic subscription is global across the whole engine (see
    // ExternalTaskPoller.poll's own fetchAndLock) - without this, a twin external task and its
    // proxy counterpart of the same name would both be served by whichever worker happened to be
    // generated for that topic, silently running the wrong side's logic.
    private static void suffixExternalTaskTopics(Document document) {
        NodeList all = document.getElementsByTagNameNS("*", "*");
        for (int i = 0; i < all.getLength(); i++) {
            Element element = (Element) all.item(i);
            String topic = element.getAttributeNS(CAMUNDA_NS, "topic");
            if (topic == null || topic.isBlank()) continue;
            element.setAttributeNS(CAMUNDA_NS, "camunda:topic", topic + TWIN_TOPIC_SUFFIX);
        }
    }
}
