package com.metaml.wbapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.metaml.wbapi.controller.workbench.WorkbenchController;
import com.metaml.wbapi.payload.response.ApiResponse;
import com.metaml.workbench.model.TwinProcess;
import com.metaml.workbench.service.WorkbenchService;

class TwinDiscoveryEndpointTest {

    private final WorkbenchService service = mock(WorkbenchService.class);
    private final WorkbenchController controller = new WorkbenchController(service);

    @Test
    void listingTwinsForKnownModelReturns200WithTwinList() {
        TwinProcess twinA = new TwinProcess();
        twinA.setId("twin-a");
        twinA.setModelId("model-1");

        TwinProcess twinB = new TwinProcess();
        twinB.setId("twin-b");
        twinB.setModelId("model-1");

        when(service.listTwinProcesses("model-1")).thenReturn(List.of(twinA, twinB));

        ResponseEntity<ApiResponse> response = controller.listTwinProcesses("model-1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        List<TwinProcess> result = (List<TwinProcess>) response.getBody().getData();
        assertThat(result).hasSize(2);
        assertThat(result).extracting(TwinProcess::getId).containsExactly("twin-a", "twin-b");
    }

    @Test
    void listingTwinsForModelWithNoTwinsReturns200WithEmptyList() {
        when(service.listTwinProcesses("model-empty")).thenReturn(List.of());

        ResponseEntity<ApiResponse> response = controller.listTwinProcesses("model-empty");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        List<TwinProcess> result = (List<TwinProcess>) response.getBody().getData();
        assertThat(result).isEmpty();
    }

    @Test
    void blankOrNullModelIdReturns400BadRequest() {
        when(service.listTwinProcesses(null))
                .thenThrow(new IllegalArgumentException("modelId must not be blank"));

        ResponseEntity<ApiResponse> response = controller.listTwinProcesses(null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
