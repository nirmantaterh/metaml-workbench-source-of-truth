package com.metaml.wbapi.payload.request;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ConnectActivityRequest {
    private String twinProcessId;
    private String originalActivityId;
    private String twinActivityId;
}
