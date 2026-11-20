package dev.ramar.gql;

import java.io.*;

import java.net.*;

import java.nio.charset.StandardCharsets;

import java.nio.file.*;

import java.util.*;


import dev.ramar.json.*;
import dev.ramar.json.parsers.StringParser;

/**
 * authenticated GraphQL client with token caching and refresh.
 *
 * Config JSON type: gql:AuthConfig
 * Secrets JSON type: gql:AuthSecrets
 */
public class AuthGQLClient extends GQLClient
{

    private final String username;
    private final String password;
    private final String tokenURL;

    private final Path secretsPath;


    protected AuthGQLClient(String endpoint, String username, String password, String tokenURL, Path secretsPath)
    {
        super(endpoint);

        this.username = username;
        this.password = password;
        this.tokenURL = tokenURL;

        if( secretsPath == null )
            throw new IllegalArgumentException("secretsPath is null");
        this.secretsPath = secretsPath;

        this.validateConfig();
    }



    public static AuthGQLClient FromProps(Properties props, Path secretsPath)
    {
        String endpoint = props.getProperty("target_url");
        String username = props.getProperty("username");
        String password = props.getProperty("password");
        String tokenURL = props.getProperty("token_url");

        return new AuthGQLClient(endpoint, username, password, tokenURL, secretsPath);
    }


    public static AuthGQLClient FromJSON(Path configPath, Path secretsPath)
    {
        if( configPath == null )
            throw new IllegalArgumentException("configPath is null");

        if( secretsPath == null )
            throw new IllegalArgumentException("secretsPath is null");

        if( !Files.exists(configPath) )
            throw new IllegalArgumentException("Config file missing: " + configPath);

        try( InputStream is = Files.newInputStream(configPath) )
        {
            JToken tok = JToken.JITFromStream(is);
            JObject cfg = tok.asObject();

            return FromJSON(cfg, secretsPath);
        }
        catch( Exception ex )
        {
            throw new RuntimeException("Unable to read config JSON: " + configPath, ex);
        }
    }


    public static AuthGQLClient FromJSON(JObject config, Path secretsPath)
    {
        String endpoint = config.get("target_url").tryString();
        String username = config.get("username").tryString();
        String password = config.get("password").tryString();
        String tokenURL = config.get("token_url").tryString();


        return new AuthGQLClient(endpoint, username, password, tokenURL, secretsPath);
    }


    protected Map<String, String> extraExecuteHeaders()
    {
        Map<String, String> base = super.extraExecuteHeaders();

        base.put("Authorization", "Bearer " + this.Token());

        return base;
    }


    /**
     * Returns a valid bearer token. Refreshes if missing or expired.
     */
    protected String Token()
    {
        try
        {
            String t = this.readToken();
            if( t != null )
                return t;

            if( this.authenticate() )
                return this.readToken();

            throw new RuntimeException("Token authentication failed");
        }
        catch( Exception ex )
        {
            throw new RuntimeException("Unable to acquire token", ex);
        }
    }


    private void validateConfig()
    {
        if( this.username == null || this.username.isBlank() )
            throw new IllegalArgumentException("Config missing: username");

        if( this.password == null || this.password.isBlank() )
            throw new IllegalArgumentException("Config missing: password");

        if( this.tokenURL == null || this.tokenURL.isBlank() )
            throw new IllegalArgumentException("Config missing: token_url");
    }


    private String readToken() throws IOException
    {
        if( !Files.exists(this.secretsPath) )
            return null;

        JToken rootTok;
        try( InputStream is = Files.newInputStream(this.secretsPath) )
        {
            rootTok = JToken.JITFromStream(is);
        }

        if( rootTok == null )
            return null;

        JObject root = rootTok.asObject();

        String tok = root.get("token").tryString();
        long exp = root.get("expires_at").tryLong(-1);

        if( tok == null || exp <= 0 )
            return null;

        long now = System.currentTimeMillis() / 1000L;
        if( exp <= now )
            return null;

        return tok;
    }


    private boolean authenticate()
    {
        try
        {
            String payload = JObject.Create()
                .with("username", this.username)
                .with("password", this.password)
                .toString()
            ;

            URL url = new URL(this.tokenURL);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");

            try( OutputStream os = conn.getOutputStream() )
            {
                os.write(payload.getBytes(StandardCharsets.UTF_8));
            }

            int code = conn.getResponseCode();
            if( code != 200 )
                throw new RuntimeException("Token request failed: " + code);

            JToken response;
            try( InputStream is = conn.getInputStream() )
            {
                response = JToken.JITFromStream(is);
            }

            String token = response.get("token").tryString();
            long expires = response.get("expires_at").tryLong(-1);

            if( token == null || token.isBlank() || expires <= 0 )
                throw new RuntimeException("Invalid token response");

            JToken secrets = JObject.Create()
                .with("token", token)
                .with("expires_at", expires)
            ;

            Path parent = this.secretsPath.getParent();
            if( parent != null )
                Files.createDirectories(parent);

            try( BufferedWriter w = Files.newBufferedWriter(this.secretsPath, StandardCharsets.UTF_8) )
            {
                w.write(StringParser.WriteValuePretty(secrets));
            }

            return true;
        }
        catch( Exception ex )
        {
            throw new RuntimeException("Authentication failed", ex);
        }
    }
}
