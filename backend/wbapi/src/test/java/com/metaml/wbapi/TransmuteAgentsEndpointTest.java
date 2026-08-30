package com.metaml.wbapi;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.metaml.wbapi.controller.workbench.WorkbenchController;
import com.metaml.wbapi.payload.response.ApiResponse;
import com.metaml.workbench.client.AgentAvailabilityResult;
import com.metaml.workbench.client.NodeManagerUnavailableException;
import com.metaml.workbench.service.WorkbenchService;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

class TransmuteAgentsEndpointTest {

    private final WorkbenchService service = mock(WorkbenchService.class);
    private final WorkbenchController controller = new WorkbenchController(service);

    @Test
    void listAgentsReturns200WithCandidateList() {
        AgentAvailabilityResult credit = new AgentAvailabilityResult(
                "credit-risk-assessor", true, "credit-risk-agent-01",
                "Available", Map.of("riskFlagged", true),
                "Credit risk assessor agent", List.of("risk assessment", "credit check")
        );
        given(service.listAvailableAgents()).willReturn(List.of(credit));

        ResponseEntity<ApiResponse> response = controller.listAgents();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getData()).isInstanceOf(List.class);

        @SuppressWarnings("unchecked")
        List<AgentAvailabilityResult> list = (List<AgentAvailabilityResult>) response.getBody().getData();
        assertThat(list).hasSize(1);
        assertThat(list.get(0).getAgentType()).isEqualTo("credit-risk-assessor");
        assertThat(list.get(0).getAgentName()).isEqualTo("credit-risk-agent-01");
        assertThat(list.get(0).getCapabilities()).contains("risk assessment");
    }

    @Test
    void listAgentsReturns503WhenNodeManagerUnavailable() {
        given(service.listAvailableAgents()).willThrow(new NodeManagerUnavailableException("Node manager offline"));

        ResponseEntity<ApiResponse> response = controller.listAgents();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getMessage()).contains("Node manager offline");
    }
}
