package routing;

import cnj.CloudFoundryService;
import lombok.Data;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.cloudfoundry.operations.CloudFoundryOperations;
import org.cloudfoundry.operations.applications.ApplicationManifest;
import org.cloudfoundry.operations.applications.DeleteApplicationRequest;
import org.cloudfoundry.operations.routes.DeleteOrphanedRoutesRequest;
import org.cloudfoundry.operations.services.BindRouteServiceInstanceRequest;
import org.cloudfoundry.operations.services.CreateUserProvidedServiceInstanceRequest;
import org.cloudfoundry.operations.services.DeleteServiceInstanceRequest;
import org.cloudfoundry.operations.services.UnbindRouteServiceInstanceRequest;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.reactivestreams.Publisher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.junit4.SpringRunner;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.io.File;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@RunWith(SpringRunner.class)
@SpringBootTest(classes = RoutingIT.Config.class)
public class RoutingIT {

		@SpringBootApplication
		public static class Config {

				@Bean
				RouteServiceDeployer routeServiceDeployer(
					CloudFoundryService cfs,
					CloudFoundryOperations cops) {
						return new RouteServiceDeployer(cfs, cops);
				}
		}

		@Autowired
		private RouteServiceDeployer routeServiceDeployer;

		@Autowired
		private CloudFoundryOperations cloudFoundryOperations;

		@Test
		public void deploy() {

				Function<String, Mono<Boolean>> appExists = input -> this.cloudFoundryOperations.applications().list().filter(si -> si.getName().equals(input)).hasElements();
				Function<String, Mono<Boolean>> svcExists = input -> this.cloudFoundryOperations.services().listInstances().filter(si -> si.getName().equals(input)).hasElements();

				DeployResult deployResult = this.routeServiceDeployer.deploy();

				Publisher<Boolean> just = Flux
					.just(
						appExists.apply(deployResult.getDownstreamServiceAppName()),
						svcExists.apply(deployResult.getRouteServiceName()),
						svcExists.apply(deployResult.getRoutingEurekaServiceName()))
					.flatMap(m -> m.flatMap(Mono::just));

				Flux<Boolean> results = Flux
					.from(deployResult.getResults())
					.thenMany(just);

				StepVerifier
					.create(results)
					.expectNextMatches(x -> x)
					.expectNextMatches(x -> x)
					.expectNextMatches(x -> x)
					.verifyComplete();
		}
}

class RouteServiceDeployer {

		private final Log log = LogFactory.getLog(getClass());

		private final File root = new File("..");

		private final File routeServiceManifest = new File(this.root, "route-service/manifest.yml");
		private final File routingEurekaServiceManifest = new File(this.root, "routing-eureka-service/manifest.yml");
		private final File downstreamServiceManifest = new File(this.root, "downstream-service/manifest.yml");

		private final Map<File, ApplicationManifest> manifests;
		private final Map<File, String> applicationNames;

		private final CloudFoundryOperations cloudfoundry;
		private final CloudFoundryService cloudFoundryService;

		RouteServiceDeployer(CloudFoundryService cloudFoundryService, CloudFoundryOperations cloudfoundry) {
				this.cloudFoundryService = cloudFoundryService;

				this.manifests = Stream
					.of(this.downstreamServiceManifest, this.routeServiceManifest, this.routingEurekaServiceManifest)
					.collect(Collectors.toConcurrentMap(x -> x, x -> cloudFoundryService.getManifestFor(x.toPath())));

				this.cloudfoundry = cloudfoundry;

				this.applicationNames = this.manifests
					.entrySet()
					.stream()
					.collect(Collectors.toConcurrentMap(Map.Entry::getKey, x -> x.getValue().getName()));

		}

		private void error(String msg, Throwable throwable) {
				log.warn("oh the humanity! " + msg);
				if (null != throwable) {
						log.error(throwable.getMessage());
				}
		}

		public DeployResult deploy() {

				// unbind route service if it exists
				String routeServiceAppName = this.manifests.get(this.routeServiceManifest).getName();
				String downstreamServiceAppName = this.manifests.get(this.downstreamServiceManifest).getName();
				String routingEurekaServiceAppName = this.manifests.get(this.routingEurekaServiceManifest).getName();

				Flux<String> unbindRouteService = cloudFoundryService
					.findRoutesForApplication(downstreamServiceAppName)
					.flatMap(route ->
						cloudfoundry
							.services()
							.unbindRoute(UnbindRouteServiceInstanceRequest
								.builder()
								.serviceInstanceName(routeServiceAppName)
								.domainName(route.getDomain())
								.hostname(route.getHost())
								.path(route.getPath())
								.build()
							)
							.thenMany(Mono.just(routeServiceAppName))
					)
					.doOnError(ex -> error("something went wrong in unbinding the route service " + routeServiceAppName, ex))
					.doOnComplete(() -> log.info("unbound route service " + routeServiceAppName));

				Flux<String> deleteApplications = Flux
					.just(this.downstreamServiceManifest, this.routeServiceManifest, this.routingEurekaServiceManifest)
					.map(this.applicationNames::get)
					.flatMap(name -> cloudfoundry
						.applications()
						.delete(DeleteApplicationRequest.builder().name(name).build())
						.onErrorResume(IllegalArgumentException.class, ex -> {
								error(String.format("can't delete application %s", name), ex);
								return Mono.empty();
						})
						.then(Mono.just(name)))
					.doOnComplete(() -> Stream
						.of(this.downstreamServiceManifest, this.routeServiceManifest, this.routingEurekaServiceManifest)
						.map(this.manifests::get)
						.forEach(m -> log.info("deleted application " + m.getName())));

				Flux<String> deleteServices = Flux
					.just(this.routingEurekaServiceManifest, this.routeServiceManifest)
					.map(this.applicationNames::get)
					.flatMap(name -> cloudfoundry
						.services()
						.deleteInstance(DeleteServiceInstanceRequest.builder().name(name).build())
						.onErrorResume(IllegalArgumentException.class, ex -> {
								error(String.format("can't delete service %s", name), ex);
								return Mono.empty();
						})
						.then(Mono.just(name)))
					.doOnComplete(() -> Stream
						.of(this.routingEurekaServiceManifest)
						.map(this.manifests::get)
						.forEach(m -> log.info("deleted service " + m.getName())));

				Flux<String> pushAndCreateEurekaBackingService = Flux
					.just((this.routingEurekaServiceManifest))
					.map(this.manifests::get)
					.flatMap(cloudFoundryService::pushApplicationWithManifest)
					.flatMap(cloudFoundryService::createBackingService)
					.doOnComplete(() -> Stream.of(this.routingEurekaServiceManifest)
						.map(this.manifests::get)
						.forEach(m -> log.info("pushed and created backing service for " + m.getName())));

				Flux<String> pushApplications = Flux
					.just(this.routeServiceManifest, this.downstreamServiceManifest)
					.map(this.manifests::get)
					.flatMap(cloudFoundryService::pushApplicationWithManifest)
					.doOnComplete(() -> Stream
						.of(this.routeServiceManifest, this.downstreamServiceManifest)
						.map(this.manifests::get)
						.forEach(m -> log.info("pushed " + m.getName()))
					);

				Flux<String> createBackingRouteService = cloudFoundryService
					.urlForApplication(routeServiceAppName, true)
					.flatMapMany(url ->
						cloudfoundry
							.services()
							.createUserProvidedInstance(
								CreateUserProvidedServiceInstanceRequest
									.builder()
									.name(routeServiceAppName)
									.routeServiceUrl(url)
									.build()
							)
							.thenMany(Mono.just(routeServiceAppName))
					)
					.doOnError(ex -> error("something went wrong in creating the backing route service " + routeServiceAppName, ex))
					.doOnComplete(() -> log.info("CUPS for " + routeServiceAppName));

				Flux<String> bindRouteService = cloudFoundryService
					.findRoutesForApplication(downstreamServiceAppName)
					.flatMap(route ->
						cloudfoundry
							.services()
							.bindRoute(BindRouteServiceInstanceRequest
								.builder()
								.serviceInstanceName(routeServiceAppName)
								.domainName(route.getDomain())
								.hostname(route.getHost())
								.path(route.getPath())
								.build())
							.thenMany(Mono.just(routeServiceAppName))
					)
					.doOnError(ex -> error("something went wrong in binding the route service " + routeServiceAppName, ex))
					.doOnComplete(() -> log.info("bound route service " + routeServiceAppName));

				Mono<Boolean> deleteOrphanedRoutes =
					cloudfoundry
						.routes()
						.deleteOrphanedRoutes(DeleteOrphanedRoutesRequest.builder().build())
						.then(Mono.just(true));

				Publisher<Boolean> deployPublisher = Flux
					.from(unbindRouteService)
					.thenMany(deleteApplications)
					.thenMany(deleteOrphanedRoutes)
					.thenMany(deleteServices)
					.thenMany(pushAndCreateEurekaBackingService)
					.thenMany(pushApplications)
					.thenMany(createBackingRouteService)
					.thenMany(bindRouteService)
					.thenMany(Mono.just(true))
					.doOnComplete(() -> log.info("..done!"))
					.doOnError(e -> error("could not reset and deploy the applications and services", e));

				return new DeployResult(routeServiceAppName, downstreamServiceAppName, routingEurekaServiceAppName, deployPublisher);
		}
}


@Data
class DeployResult {

		private final Publisher<Boolean> results;
		private final String routeServiceName, routingEurekaServiceName, downstreamServiceAppName;

		public DeployResult(String routeServiceName, String downstreamServiceAppName,
																						String routingEurekaServiceName,
																						Publisher<Boolean> results) {
				this.routeServiceName = routeServiceName;
				this.routingEurekaServiceName = routingEurekaServiceName;
				this.downstreamServiceAppName = downstreamServiceAppName;
				this.results = results;
		}


}