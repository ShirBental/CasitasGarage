package home.casita;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class CasitaApplication {

    public static void main(String[] args) {
        SpringApplication.run(CasitaApplication.class, args);
    }
}
