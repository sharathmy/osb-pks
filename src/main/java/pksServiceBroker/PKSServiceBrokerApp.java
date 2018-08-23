package pksServiceBroker;

import java.util.logging.Level;
import java.util.logging.Logger;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.stereotype.Component;

@Component
@SpringBootApplication
public class PKSServiceBrokerApp {
	
	public static void main(String[] args) {
		Logger.getGlobal().getParent().getHandlers()[0].setLevel(Level.FINEST);
		SpringApplication.run(PKSServiceBrokerApp.class, args);
	}

}
