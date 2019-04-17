# Zipkin-LightStep-Converter

Accepts Zipkin /api/v2/spans JSON format and converts to OpenTracing span format and writes to a LightStep tracer.

This code is meant to be an example of how to convert Zipkin span JSON format into Opentracing spans specifically for LightStep.

Configure the LightStep tracer directly in the TracerConfig.java file.

Configure Zipkin JSON to Span object mappings directly in the HelloController.java file.

Build the project using Maven and run the Jar as below. Then point your Zipkin endpoing to the app below.

java -Dserver.port=9411 -jar gs-spring-boot-0.1.0.jar
