package dev.ramar.gql;

import dev.ramar.json.*;

import dev.ramar.utils.ExecutorCaller;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

public class GQLClient implements ExecutorCaller
{
    /* ExecutorCaller
    --===---------------
    */
    protected final ExecutorService executor;
    public ExecutorService executor()
    { return this.executor; }


    protected final String endpoint;
    protected final Map<String, String> headers = new HashMap<>();

    public GQLClient(String endpoint)
    {
        this(endpoint, Executors.newFixedThreadPool(12));
    }

    public GQLClient(String endpoint, ExecutorService executor)
    {
        this.endpoint = endpoint;
        this.executor = executor;
        this.headers.put("Content-Type", "application/json");
        this.headers.put("Accept", "application/json");
    }

    public void setHeader(String key, String value)
    {
        headers.put(key, value);
    }


    /* Internal GQL Query Calling
    --===---------------------------
    */

    public Callable<JObject> call(String query)
    {
        return () -> executeQuery(this.buildRequestBody(query));
    }

    public Callable<JObject> call(String query, JObject variables)
    {
        return () -> executeQuery(this.buildRequestBody(query, variables));
    }
    public Callable<JObject> call(String query, Map<String, Object> variables)
    {
        return () -> executeQuery(this.buildRequestBody(query, variables));
    }

    /// used for subclasses to add in headers whenever `.executeQuery()` is called
    /// great for any changing headers over time, for example Authorization headers
    /// extensions should call super.extraExecuteHeaders() and put into the returned tree
    /// and return `base`, see `AuthGQLClient::extraExecuteHeaders` for an example
    protected Map<String, String> extraExecuteHeaders()
    { return new TreeMap<>(); }


    private HttpClient client = HttpClient.newHttpClient();

    private JObject executeQuery(String payload)
    {
        return this.executeQuery(payload, 0);
    }
    private JObject executeQuery(String payload, int retries)
    {
        try
        {
            // Create client (reusable — can be static if desired)

            // Build request
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .POST(HttpRequest.BodyPublishers.ofString(payload));

            // Add headers
            for (Map.Entry<String, String> e : headers.entrySet())
                builder.header(e.getKey(), e.getValue());

            for (Map.Entry<String, String> e : this.extraExecuteHeaders().entrySet())
                builder.header(e.getKey(), e.getValue());


            HttpRequest request = builder.build();

            // Send and get response (synchronously)
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());

            int code = response.statusCode();

            // No exceptions are thrown — you get full control

            JToken token = JToken.JITFromStream(response.body());
            // Optionally attach status to a wrapper (simple version)
            JObject wrapper = new JObject();
            wrapper.put("status", code);
            wrapper.put("response", token);

            return wrapper;
        }
        catch(InterruptedException ex)
        {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted during .executeQuery(): " + ex.getMessage(), ex);
        }
        catch (IOException ex)
        {
            /// received a GOAWAY once. apparently this should handle it.
            if( ex.getMessage().contains("GOAWAY") )
            {
                if( retries > 0 )
                    throw new UncheckedIOException("GOAWAY handling failure.", ex);

                System.out.println("GOAWAY received. retrying with a new HttpClient");
                this.client = HttpClient.newHttpClient();
                return this.executeQuery(payload, retries + 1);
            }
            throw new UncheckedIOException(ex);
        }
    }


    /* API Call Handling
    --===------------------
    */

    public <T> T sync(Callable<T> task)
    {
        try
        {
            return task.call();
        }
        catch (Exception ex)
        {
            throw new RuntimeException(ex);
        }
    }

    public <T> Future<T> async(Callable<T> task)
    {
        return executor.submit(task);
    }

    public <T> T block(Callable<T> task)
    {
        try
        {
            return executor.submit(task).get();
        }
        catch (Exception ex)
        {
            throw new RuntimeException(ex);
        }
    }



    /* Request Body Building
    --===----------------------
    */

    public String buildRequestBody(String query, JObject variables)
    {
        JToken obj = new JObject()
            .with("query", query)
        ;

        if( variables != null && !variables.isEmpty() )
            obj.with("variables", variables);

        return obj.toPrettyString();
    }


    public String buildRequestBody(String query, Map<String, Object> variables)
    {
        return this.buildRequestBody(query, JObject.From(variables));
    }


    public String buildRequestBody(String query)
    {
        return new JObject()
            .with("query", query)
            .toPrettyString()
        ;
    }

}