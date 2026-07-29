package com.metaml.nodemanager.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.metaml.nodemanager.payload.AgentAvailabilityResponse;

import static org.assertj.core.api.Assertions.assertThat;

// the real application.yml, not a stubbed catalog - half of what's worth checking here is that
// the yaml binds the way the workbench expects it to
@SpringBootTest
class NodeManagerServiceImplTest {

    @Autowired
    private NodeManagerService nodeManagerService;

    @Test
    void answersFromTheConfiguredCatalog() {
        AgentAvailabilityResponse validator = nodeManagerService.checkAvailability("validator");

        assertThat(validator.isAvailable()).isTrue();
        assertThat(validator.getAgentName()).isEqualTo("validator-agent-01");
        assertThat(validator.getOutputs()).isEmpty();
    }

    // validator gets its own test above since it's the one the rest of the suite leans on. These
    // three never had anything pinning their names to the yaml until now.
    @ParameterizedTest
    @CsvSource({
            "data-enricher, data-enricher-agent-01",
            "notifier,      notifier-agent-01",
            "recommender,   recommender-agent-01"
    })
    void theOtherCatalogEntriesReportNoOutputsAndTheirOwnAgentName(String agentType, String agentName) {
        AgentAvailabilityResponse response = nodeManagerService.checkAvailability(agentType);

        assertThat(response.isAvailable()).isTrue();
        assertThat(response.getAgentName()).isEqualTo(agentName);
        assertThat(response.getOutputs()).isEmpty();
    }

    @Test
    void unknownAgentTypeIsNotAvailableAndNamesNoAgent() {
        AgentAvailabilityResponse unknown = nodeManagerService.checkAvailability("no-such-agent");

        assertThat(unknown.isAvailable()).isFalse();
        assertThat(unknown.getAgentName()).isNull();
        assertThat(unknown.getReason()).contains("not found");
        assertThat(unknown.getOutputs()).isEmpty();
    }

    // a real Boolean, not the string "true". The whole generic-output mechanism rests on this:
    // the gateway on the far end asks whether the variable == true, and a String would sail
    // past that check every time without anything looking broken.
    @Test
    void outputValuesKeepTheTypeTheYamlGaveThem() {
        AgentAvailabilityResponse credit = nodeManagerService.checkAvailability("credit-risk-assessor");

        assertThat(credit.isAvailable()).isTrue();
        assertThat(credit.getAgentName()).isEqualTo("credit-risk-agent-01");
        assertThat(credit.getOutputs()).containsOnlyKeys("riskFlagged");
        assertThat(credit.getOutputs().get("riskFlagged")).isInstanceOf(Boolean.class).isEqualTo(true);
    }
}
