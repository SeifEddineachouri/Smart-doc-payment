package com.example.smartdoc.payment;

import com.example.smartdoc.payment.config.PaymentProperties;
import com.example.smartdoc.payment.model.Plan;
import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
@ConfigurationPropertiesScan
public class PaymentServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(PaymentServiceApplication.class, args);
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    Plan defaultPlan(PaymentProperties properties) {
        return properties.defaultPlan().toDomain();
    }
}


