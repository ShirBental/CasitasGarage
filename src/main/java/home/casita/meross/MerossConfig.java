package home.casita.meross;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import home.casita.garage.DoorDevice;

/** Wires the Meross door in when {@code casita.door=meross}. */
@Configuration
@ConditionalOnProperty(name = "casita.door", havingValue = "meross")
class MerossConfig {

    @Bean
    MerossCloudApi merossCloudApi(MerossProperties properties) {
        return new MerossCloudApi(properties.apiBaseUrl(), properties.requestTimeout());
    }

    @Bean
    MerossMqttTransport merossTransport(MerossCloudApi api, MerossProperties properties) {
        return new MerossMqttTransport(api, properties);
    }

    @Bean
    DoorDevice merossDoor(MerossMqttTransport transport, MerossProperties properties) {
        return new MerossDoor(transport, properties.deviceUuid(), properties.channel());
    }
}
