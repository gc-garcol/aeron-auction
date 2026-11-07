package gc.garcol.aeronauction.client.realtime;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Creates the Centrifugo publisher, which does nothing while live updates are off.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RealtimeProperties.class)
class RealtimeConfig
{
    @Bean
    CentrifugoPublisher centrifugoPublisher(final RealtimeProperties properties, final MeterRegistry registry)
    {
        return new CentrifugoPublisher(properties, registry);
    }
}
