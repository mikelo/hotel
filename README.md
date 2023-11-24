<img src=https://github.com/mikelo/hotel/assets/1353545/5902dc01-ba2c-44fc-94c7-4e612186047e width=100> + <img src=https://github.com/mikelo/hotel/assets/1353545/ee73239a-1da6-480e-b833-453d83dfd9f2 width=100> + <img src=https://github.com/mikelo/hotel/assets/1353545/fe004468-98b7-465a-8933-c7a3e17ccbb6 width=100>
# Quarkus instrumentation using Opentelemetry and Instana 



This project uses Quarkus, the Supersonic Subatomic Java Framework.

If you want to learn more about Quarkus, please visit its website: https://quarkus.io/ .

## Running the application in dev mode

You can run your application in dev mode that enables live coding using quarkus CLI:
```shell script
quarkus dev
```
> **_NOTE:_**  Quarkus now ships with a Dev UI, which is available in dev mode only at http://localhost:8080/q/dev/.

to test locally you can edit [application.properties](src/main/resources/application.properties) such that:
```shell script
quarkus.otel.exporter.otlp.traces.endpoint=http://localhost:4317
# quarkus.otel.exporter.otlp.traces.endpoint=http://instana-agent.instana-agent.svc:4317
```
and then spin up an OTEL collector:
```shell script
podman run -p 127.0.0.1:4317:4317 -p 127.0.0.1:55679:55679 otel/opentelemetry-collector:0.89.0
```

## Deploying to openshift cluster having instana-agent running
or deploy to a running openshift cluster:
```shell script
quarkus build -Dquarkus.kubernetes.deploy=true -Dquarkus.kubernetes-client.trust-certs=true
```
I used https://github.com/instana/opentelemetry-demo to test out components

## Packaging and running the application

The application can be packaged using:
```shell script
./mvnw package
```
It produces the `quarkus-run.jar` file in the `target/quarkus-app/` directory.
Be aware that it’s not an _über-jar_ as the dependencies are copied into the `target/quarkus-app/lib/` directory.

The application is now runnable using `java -jar target/quarkus-app/quarkus-run.jar`.

If you want to build an _über-jar_, execute the following command:
```shell script
./mvnw package -Dquarkus.package.type=uber-jar
```

The application, packaged as an _über-jar_, is now runnable using `java -jar target/*-runner.jar`.

## Creating a native executable

You can create a native executable using: 
```shell script
./mvnw package -Pnative
```

Or, if you don't have GraalVM installed, you can run the native executable build in a container using: 
```shell script
./mvnw package -Pnative -Dquarkus.native.container-build=true
```

You can then execute your native executable with: `./target/hotel-1.0.0-SNAPSHOT-runner`

If you want to learn more about building native executables, please consult https://quarkus.io/guides/maven-tooling.

## Related Guides

- Scheduler ([guide](https://quarkus.io/guides/scheduler)): Schedule jobs and tasks
- RESTEasy Reactive ([guide](https://quarkus.io/guides/resteasy-reactive)): A Jakarta REST implementation utilizing build time processing and Vert.x. This extension is not compatible with the quarkus-resteasy extension, or any of the extensions that depend on it.
- OpenShift ([guide](https://quarkus.io/guides/deploying-to-openshift)): Generate OpenShift resources from annotations
- OpenTelemetry ([guide](https://quarkus.io/guides/opentelemetry)): Use OpenTelemetry to trace services

## Provided Code

### RESTEasy Reactive

Easily start your Reactive RESTful Web Services

[Related guide section...](https://quarkus.io/guides/getting-started-reactive#reactive-jax-rs-resources)
