package com.metaml.wbapi;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

// entity/repository scanning defaults to this class's own package (com.metaml.wbapi), not scanBasePackages above - Project/ProcessModelArchive and their repositories live under com.metaml.workbench, a sibling package, so both need pointing there explicitly.
@SpringBootApplication(scanBasePackages = "com.metaml")
@EntityScan("com.metaml.workbench.model")
@EnableJpaRepositories("com.metaml.workbench.repository")
public class WbapiApplication {

	public static void main(String[] args) {
		SpringApplication.run(WbapiApplication.class, args);
	}

}
