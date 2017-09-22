package tault;

import io.opentracing.ActiveSpan;
import io.opentracing.Span;
import io.opentracing.SpanContext;
import io.opentracing.Tracer;
import io.opentracing.contrib.spring.web.client.HttpHeadersCarrier;
import io.opentracing.propagation.TextMapExtractAdapter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

import javax.servlet.http.HttpServletRequest;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;

import static io.opentracing.propagation.Format.Builtin.HTTP_HEADERS;

@RestController
public class HomeController {

    @Qualifier("zipkinTracer")
    @Autowired
    private Tracer tracer;

    @Autowired
    private RestTemplate restTemplate;

    @RequestMapping("/world")
    public String world(HttpServletRequest request) {
        HttpHeaders httpHeaders = new HttpHeaders();
        HttpHeadersCarrier carrier = new HttpHeadersCarrier(httpHeaders);
        carrier.put("foo","bar");

        ActiveSpan serverSpan = tracer.activeSpan();

        Span span = tracer.buildSpan("contacting faulty")
                .asChildOf(serverSpan.context())
                .startManual();

        HttpEntity<String> entity = new HttpEntity<>(httpHeaders);

        ResponseEntity<String> response = restTemplate.exchange("http://localhost:8080/hello", HttpMethod.GET, entity, String.class);
        span.finish();
        return response.getBody();
    }

    @RequestMapping("/hello")
    public String hello(HttpServletRequest request) {

        Map<String, String> httpHeaders = getHeaders(request);

        TextMapExtractAdapter carrier = new TextMapExtractAdapter(httpHeaders);
        SpanContext parentSpan = tracer.extract(HTTP_HEADERS, carrier);

        Span child = tracer.buildSpan("tault-operation")
                .asChildOf(tracer.activeSpan())
                .startManual();
        String message = "Hello from Taulty!";
        String faultMessage = "with delay";
        // lift this up into a global interceptor so we don't have to change the application
        try {
            if (parentSpan != null) {
                for (Map.Entry<String, String> entry : carrier) {
                    if (entry.getKey().equalsIgnoreCase("X-B3-Service") && entry.getValue().equalsIgnoreCase("tault")) {
                        Thread.sleep(2000);
                        message = message.concat(faultMessage);

                    }
                }
            }
            return message;

        } catch (Exception exception) {
            return "we had an exception";
        } finally {
            child.finish();
        }
    }

    private Map<String, String> getHeaders(HttpServletRequest request) {
        Map<String, String> httpHeaders = new HashMap<>();
        Enumeration headerNames = request.getHeaderNames();
        while (headerNames.hasMoreElements()) {
            String key = (String) headerNames.nextElement();
            String value = request.getHeader(key);
            httpHeaders.put(key, value);
            System.out.println("Key : " + key);
            System.out.println("Value : " + value);
        }
        return httpHeaders;
    }

}