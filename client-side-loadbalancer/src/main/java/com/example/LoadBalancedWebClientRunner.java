package com.example;

import com.fasterxml.jackson.databind.JsonNode;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Collections;
import java.util.Map;

@Component
class LoadBalancedWebClientRunner implements ApplicationRunner {

    private final Log log = LogFactory.getLog(getClass());

    private final RestTemplate client;

    LoadBalancedWebClientRunner(@LoadBalanced RestTemplate client) {
        this.client = client;
    }

    // <1>
    @Override
    public void run(ApplicationArguments args) throws Exception {

        Map<String, String> variables = Collections.singletonMap("name",
                "Cloud Natives!");

        // <2>
        ResponseEntity<JsonNode> forEntity = this.client.getForEntity("http://greetings-service/hi/{name}", JsonNode.class, variables);
        JsonNode body = forEntity.getBody();
        log.info(body.get("greeting").asText());
    }
}
