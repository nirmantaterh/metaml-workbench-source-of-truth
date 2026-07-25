package com.metaml.wbapi;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.metaml")
public class WbapiApplication {

	public static void main(String[] args) {
		SpringApplication.run(WbapiApplication.class, args);
	}

}
