package com.sapari.productapp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

@SpringBootApplication(scanBasePackages = "com.sapari")
public class ProductAppApplication {

  public static void main(String[] args) {
    SpringApplication.run(ProductAppApplication.class, args);
  }

}
