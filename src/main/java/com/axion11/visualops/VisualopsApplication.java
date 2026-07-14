package com.axion11.visualops;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class VisualopsApplication {

	public static void main(String[] args) {
		SpringApplication.run(VisualopsApplication.class, args);
	}

}
