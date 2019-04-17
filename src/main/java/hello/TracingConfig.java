package hello;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;

import com.lightstep.tracer.jre.JRETracer;

@Configuration                  //Setup tracer to be available for ot-spring-cloud / globally within the Spring container
@EnableAutoConfiguration
public class TracingConfig {

    //Tracer configuration current set up to report to Developer Mode
    //Service name is configured here in the withComponentName() method
    @Bean
    public JRETracer lightstepTracer() throws Exception {
        return new com.lightstep.tracer.jre.JRETracer(
                new com.lightstep.tracer.shared.Options.OptionsBuilder()
                        .withComponentName("zipkin_converter_test_service")
                        .withAccessToken("developer")
                        .withCollectorHost("localhost")
                        .withCollectorPort(8360)
                        .withCollectorProtocol("http")
                        .withVerbosity(4)
                        .build());
    }
}
