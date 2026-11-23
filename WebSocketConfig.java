package za.co.nmas.nmas.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.server.standard.ServerEndpointExporter;

/**
 * Registers @ServerEndpoint classes (e.g. ConsultationSignalingServer)
 * with Spring Boot's embedded Tomcat so native Jakarta WebSocket works.
 */
@Configuration
public class WebSocketConfig {

    @Bean
    public ServerEndpointExporter serverEndpointExporter() {
        return new ServerEndpointExporter();
    }
}
