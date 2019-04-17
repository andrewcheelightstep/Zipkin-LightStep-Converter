package hello;

import com.lightstep.tracer.shared.SpanBuilder;
import io.opentracing.Span;
import com.lightstep.tracer.shared.SpanContext;
import com.lightstep.tracer.jre.JRETracer;
import org.apache.tomcat.util.http.fileupload.IOUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.client.RestTemplate;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.util.*;
import java.util.zip.GZIPInputStream;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.*;

import javax.servlet.http.HttpServletRequest;
import javax.xml.transform.Result;

@RestController
public class HelloController
{

    @Autowired
    private JRETracer tracer;              //Bring in the @Bean-annotated Tracer from TracingConfig

    @Autowired
    RestTemplate restTemplate;

    @RequestMapping("/")
    public String index()
    {
        return "Greetings from Spring Boot!";
    }

    @RequestMapping("/api/v2/spans")
    public String receiveZipkinSpanJSON(@RequestHeader HttpHeaders httpHeaders, @RequestBody byte[] payload)
    {
        //Forward this on to the Zipkin server (if desired)
        HttpEntity<Object> entity = new HttpEntity<Object>(payload,httpHeaders);
        restTemplate.postForObject("http://localhost:9412/api/v2/spans", entity, String.class);

        //Process the request turning the Zipkin span JSON into OT spans for LightStep consumption
        return processRequest(httpHeaders,payload);
    }

    public String processRequest(HttpHeaders httpHeaders, byte[] payload)
    {
        try
        {
            Map<String, String> headerMap = httpHeaders.toSingleValueMap();
            //headerMap.forEach((key, value) -> System.out.println(key + ":" + value));

            //If contents are gzipped, unzip them
            if(headerMap.containsKey("content-encoding"))
            {
                if(headerMap.get("content-encoding").toString().equalsIgnoreCase("gzip"))
                {
                    payload = gUnzipBody(payload);
                }
            }

            //Convert the JSON payload into an array of internal Span representations
            String sJSON = new String(payload);
            List<internalSpan> spans = processRequestBody(sJSON);

            //Write the newly created internal spans into an OpenTracing span
            Iterator<internalSpan> spanItr = spans.iterator();
            while(spanItr.hasNext())
            {
                internalSpan thisSpan = spanItr.next();
                reportSpan(thisSpan);

                //thisSpan.printSpan();
            }

        }catch(Exception e)
        {
            e.printStackTrace();
        }finally
        {
            return "Howdy " + payload;
        }
    }

    //Using the internal span representation, create a LightStep span
    public void reportSpan(internalSpan thisSpan)
    {

        SpanContext lsSpanContext;
        lsSpanContext = new SpanContext(thisSpan.lTraceId, thisSpan.lParentId);

        try
        {
            SpanBuilder lsSpanBuilder = (SpanBuilder) tracer.buildSpan(thisSpan.sOperationName);
            io.opentracing.Span lsSpan;
            if(thisSpan.lParentId == 0)
            {
                lsSpan = lsSpanBuilder
                        .withTraceIdAndSpanId(thisSpan.lTraceId, thisSpan.lSpanId)
                        .withStartTimestamp(thisSpan.lStartTimestamp)
                        .ignoreActiveSpan()
                        .start();
            }else
            {
                lsSpan = lsSpanBuilder
                        .withTraceIdAndSpanId(thisSpan.lTraceId, thisSpan.lSpanId)
                        .withStartTimestamp(thisSpan.lStartTimestamp)
                        .asChildOf(lsSpanContext)
                        .ignoreActiveSpan()
                        .start();
            }
            lsSpan.setTag("span.kind", thisSpan.sSpanKind);
            Map<String,String> Tags = thisSpan.Tags;
            Iterator<String> tagsItr = Tags.keySet().iterator();

            while(tagsItr.hasNext())
            {
                String sKey = tagsItr.next();
                lsSpan.setTag(sKey, Tags.get(sKey).toString());
            }

            lsSpan.finish(thisSpan.lStartTimestamp + thisSpan.lDuration);

        }catch(Exception e)
        {
            e.printStackTrace();
        }
    }

    //Gunzip the JSON payload
    public byte[] gUnzipBody(byte[] payload)
    {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try{
            IOUtils.copy(new GZIPInputStream(new ByteArrayInputStream(payload)), out);
        } catch(IOException e){
            e.printStackTrace();
        }
        System.out.println(out.toString());
        return out.toByteArray();
    }

    //Take the request body and turn it into a bunch of internal spans
    public List<internalSpan> processRequestBody(String payload)
    {

        try
        {
            Object obj = new JSONParser().parse(payload);

            // typecasting obj to JSONObject
            JSONArray ja = (JSONArray) obj;
            List<internalSpan> returnSpans = new ArrayList<internalSpan>();

            //For each span in the JSON report, create an internal representation of the span object and add to an array of them
            Iterator<JSONObject> jsonItr = ja.iterator();
            while(jsonItr.hasNext())
            {
                JSONObject jo = jsonItr.next();
                internalSpan newSpan = createSpan(jo);
                returnSpans.add(newSpan);
            }

            return returnSpans;
        } catch (Exception e)
        {
            e.printStackTrace();
            return null;
        }
    }

    //Create the internal spans from the JSON
    //This method may need to be modified depending on what tags or metadata you want to keep from your Zipkin JSON
    public internalSpan createSpan(JSONObject spanObject)
    {
        internalSpan span = new internalSpan();

        Map localEndpoint = ((Map) spanObject.get("localEndpoint"));
        // iterating localEndpoint Map
        Iterator<Map.Entry> itr1 = localEndpoint.entrySet().iterator();
        while (itr1.hasNext()) {
            Map.Entry pair = itr1.next();
            if(pair.getKey().toString().equalsIgnoreCase("serviceName"))
            {
                span.sServiceName = pair.getValue().toString();
            }
            if(pair.getKey().toString().equalsIgnoreCase("ipv4"))
            {
                span.Tags.put("ipv4", pair.getValue().toString());
            }

        }
        span.sOperationName = spanObject.get("name").toString();
        span.setTraceId(spanObject.get("traceId").toString());
        span.setSpanId(spanObject.get("id").toString());
        span.sSpanKind = (spanObject.get("kind").toString());
        //parentId may not exist
        Object pID = spanObject.get("parentId");
        if(pID != null)
        {
            span.setParentId(pID.toString());
        }

        Map Tags = ((Map) spanObject.get("tags"));
        Iterator<Map.Entry> itr2 = Tags.entrySet().iterator();
        while (itr2.hasNext()) {
            Map.Entry pair = itr2.next();
            span.Tags.put(pair.getKey().toString(), pair.getValue().toString());
        }
        span.lStartTimestamp = Long.parseLong(spanObject.get("timestamp").toString());
        span.lDuration = Long.parseLong(spanObject.get("duration").toString());

        return span;
    }

    //Object for internal span representation. Note that logs have not been accounted for here yet.
    public class internalSpan
    {
        private long lTraceId = 0;
        private long lSpanId = 0;
        private long lParentId = 0;
        public String sOperationName;
        public String sServiceName;
        public String sSpanKind;
        Map<String, String> Tags = new HashMap<>();
        public long lStartTimestamp = 0;
        public long lDuration = 0;

        public void setTraceId(String sTraceId)
        {
            lTraceId = convertToLong(sTraceId);
        }

        public long getTraceId()
        {
            return lTraceId;
        }

        public void setSpanId(String sSpanId)
        {
            lSpanId = convertToLong(sSpanId);
        }

        public long getSpanId()
        {
            return lSpanId;
        }

        public void setParentId(String sParentId)
        {
            lParentId = convertToLong(sParentId);
        }

        public long getParentId()
        {
            return lParentId;
        }

        //Converts JSON String to a long which is what LightStep uses for IDs.
        //This may have to be rewritten because the example app exports IDs as Hex
        //but not sure if your ID formats are the same.
        private long convertToLong(String sInput)
        {
            long lReturn = new BigInteger(sInput, sInput.length()).longValue();

            return lReturn;
        }

        public void printSpan()
        {
            System.out.println("TraceId: " + lTraceId);
            System.out.println("SpanId: " + lSpanId);
            System.out.println("ParentId: " + lParentId);
            System.out.println("OperationName: " + sOperationName);
            System.out.println("ServiceName: " + sServiceName);
            System.out.println("SpanKind: " + sSpanKind);
            System.out.println("StartTime: " + lStartTimestamp);
            System.out.println("Duration: " + lDuration);
            System.out.println("Tags:");
            Iterator<String> tagsItr = Tags.keySet().iterator();
            while(tagsItr.hasNext())
            {
                String sKey = tagsItr.next();
                System.out.println("    " + sKey + ": " + Tags.get(sKey).toString());
            }
        }
    }

}
