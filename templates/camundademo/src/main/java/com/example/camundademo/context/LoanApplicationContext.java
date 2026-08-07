package com.example.camundademo.context;

import org.springframework.context.annotation.Bean;

import com.example.camundademo.delegates.CalculateInterestService;

public class LoanApplicationContext {
    @Bean
    public CalculateInterestService calculateInterestService() {
        return new CalculateInterestService();
    }
}
