package za.co.nmas.nmas;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class NmasApplication {

	public static void main(String[] args) {
		SpringApplication.run(NmasApplication.class, args);
	}

}
