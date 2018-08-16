package pksServiceBroker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.stereotype.Component;

@Component
@SpringBootApplication
public class PKSServiceBrokerApp {
	
	public static void main(String[] args) {
		SpringApplication.run(PKSServiceBrokerApp.class, args);
	}

}
