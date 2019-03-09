package com.example;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestOperations;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;

/**
 * @author Ben Hale
 */
@RestController
class GreetingRestController {

    @RequestMapping(method = RequestMethod.GET, value = "hi/{name}")
    Map<String, String> hi(@PathVariable String name, @RequestHeader(value = "X-CNJ-Name", required = false) Optional<String> cn) {
        String resolvedName = cn.orElse(name);
        return Collections.singletonMap("greeting", resolvedName);
    }
}
