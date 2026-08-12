package com.metaml.wbapi;

import org.springframework.test.context.TestPropertySource;
import org.junit.jupiter.api.Test;

// mem, not the file db the app itself now uses - a test run shouldn't drop a data/ folder next to
// the demo's one, and every one of these wants an empty engine anyway
@IsolatedWorkbenchTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:metaml-test;DB_CLOSE_DELAY=-1"
})
class WbapiApplicationTests {

	@Test
	void contextLoads() {
	}

}
