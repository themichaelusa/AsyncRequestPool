import org.xml.sax.SAXException;

import javax.net.ssl.HttpsURLConnection;
import javax.xml.xpath.XPathExpressionException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Properties;
import java.util.concurrent.*;

public class Request {

    // MEMBERS
    private static URL basisURL;
    private static TradeBlockKey auth;
    private FutureTask<HttpsURLConnection> futureConn;
    private ExecutorService threadExecutor;

    private static Properties requestHeaders;
    private static QueryParams queryParams;

    // CONSTANTS
    private static final String MIME_JSON = "application/json";
    private static final String MIME_FORM_URLENCODED = "application/x-www-form-urlencoded";
    private static final int CONNECT_TIMEOUT_DEFAULT = 15000;
    private static final int READ_TIMEOUT_DEFAULT = 15000;

    // CONSTRUCTORS && FINALIZE

    public Request(TradeBlockKey key){
        auth = key;
        requestHeaders = new Properties();
        queryParams = new QueryParams();
        this.threadExecutor = Executors.newSingleThreadExecutor();
    }

    @Override
    protected void finalize(){
        this.threadExecutor.shutdown();
    }

    public void flushParams(){
        requestHeaders = new Properties();
        queryParams = new QueryParams();
    }

    // ASYNC METHODS
    private static class CallableGetRequest implements Callable<HttpsURLConnection> {
        @Override
        public HttpsURLConnection call() throws Exception {
            // establish connection, set properties for get
            HttpsURLConnection conn = generateConn("GET");
            conn.connect();
            return conn;
        }
    }

    private static class CallablePostRequest implements Callable<HttpsURLConnection> {
        @Override
        public HttpsURLConnection call() throws Exception {
            // establish connection, set properties for post
            HttpsURLConnection conn = generateConn("POST");
            conn.connect();
            return conn;
        }
    }

    // HELPER METHODS (STRING MANIPULATION, QUERY ADDITION)

    static URL constructURL(String spec) {
        try {
            return new URL(spec);
        } catch (MalformedURLException e) {
            throw new InternalError(String.format("Cannot parse malformed URL \"%s\".", spec), e);
        }
    }

    static URL constructURL(String spec, Properties query) {
        if (query == null) {
            return constructURL(spec);
        } else if (query.size() == 0) {
            return constructURL(spec);
        } else {
            ArrayList<String> params = new ArrayList<>(query.size());
            for (String name : query.stringPropertyNames()) {
                String value = query.getProperty(name);
                params.add(String.format("%s=%s", QueryParams.urlEncode(name), QueryParams.urlEncode(value)));
            }
            return constructURL(String.join("?", spec, String.join("&", params)));
        }
    }

    // HELPER METHODS (CONNECTION CONFIG)

    private static Properties getAuthHeaders(){
        return auth.getAuthHeaders(basisURL, null);
    }

    private static HttpsURLConnection generateConn(String requestType) throws IOException {
        HttpsURLConnection conn = (HttpsURLConnection) basisURL.openConnection();

        //setup basic parameters for HttpsURLConnection
        conn.setInstanceFollowRedirects(false);
        conn.setAllowUserInteraction(false);
        conn.setUseCaches(false);
        conn.setDoInput(true);
        conn.setConnectTimeout(CONNECT_TIMEOUT_DEFAULT);
        conn.setReadTimeout(READ_TIMEOUT_DEFAULT);

        // add neccessary properties for auth
        if (auth != null) {
            String nonce = NonceSource.generateNonce();
            conn.addRequestProperty(TradeBlockKey.HEADER_NONCE_NAME, nonce);
            conn.addRequestProperty(TradeBlockKey.HEADER_APIKEY_NAME, auth.getAccessKey());
            conn.addRequestProperty(TradeBlockKey.HEADER_SIGNATURE_NAME,
                    auth.getSignature(basisURL, nonce, queryParams));
        }

        /* get auth headers... -->
        Properties authHeaders = getAuthHeaders();
        for (String name : authHeaders.stringPropertyNames()) {
            conn.addRequestProperty(name, authHeaders.getProperty(name));
        }
        */

        // add additionally specified request headers
        for (String name : requestHeaders.stringPropertyNames()) {
            conn.addRequestProperty(name, requestHeaders.getProperty(name));
        }

        String get = "GET";
        String post = "POST";

        if (requestType.equals(get)){
            conn.setRequestMethod(get);
            conn.setDoOutput(false);
            conn.addRequestProperty("Accept", MIME_JSON);
            conn.addRequestProperty("Accept-Encoding", StandardCharsets.UTF_8.name());
        }
        else if (requestType.equals(post)) {
            conn.setRequestMethod(post);
            conn.setDoOutput(true);
            conn.addRequestProperty("Content-Type", MIME_FORM_URLENCODED);
            conn.addRequestProperty("Content-Encoding", StandardCharsets.UTF_8.name());

            // if needed, set FixedLengthStreamingMode for connection
            if (queryParams.size() > 0){
                byte[] data = queryParams.getFormData(StandardCharsets.UTF_8);
                conn.setFixedLengthStreamingMode(data.length);
                OutputStream stream = conn.getOutputStream();
                stream.write(data);
                stream.close();
            }

            //QueryParams params = new QueryParams();
            //byte[] formData = params.getQueryString().getBytes(StandardCharsets.UTF_8);
            //conn.addRequestProperty("Content-Length", String.valueOf(formData.length));
        }

        return conn;
    }

    private FutureTask<HttpsURLConnection> initRequest(String requestType){
        FutureTask<HttpsURLConnection> task = null;

        if (requestType.equals("GET")){
            Callable<HttpsURLConnection> get = new CallableGetRequest();
            task = new FutureTask<>(get);
        }
        else if (requestType.equals("POST")){
            Callable<HttpsURLConnection> post = new CallablePostRequest();
            task = new FutureTask<>(post);
        }

        return task;
    }

    // USER METHODS

    private void executeRequest(String requestType){
        FutureTask<HttpsURLConnection> futureReq = initRequest(requestType);
        this.threadExecutor.execute(futureReq);
        this.futureConn = futureReq;
    }

    public void setQueryParam(String name, String value) {
        synchronized (this) {
            if (value != null) {
                queryParams.setProperty(name, value);
            }
        }
    }

    public void get(String basis){
        try {
            basisURL = new URL(basis);
            executeRequest("GET");
        } catch (MalformedURLException e) {
            throw new InternalError(String.format(
                    "Cannot parse malformed URL \"%s\".", basis), e);
        }
    }

    public void get(String basis, Properties queryString){
        basisURL = constructURL(basis, queryString);
        executeRequest("GET");
    }

    public void post(String basis){
        try {
            basisURL = new URL(basis);
            executeRequest("POST");
        } catch (MalformedURLException e) {
            throw new InternalError(String.format(
                    "Cannot parse malformed URL \"%s\".", basis), e);
        }
    }

    public void post(String basis, Properties queryString){
        basisURL = constructURL(basis, queryString);
        executeRequest("POST");
    }

    public Response fmtResponse(){
        // get output of request FutureTask<HttpsURLConnection>
        Response response;
        try {
            HttpsURLConnection connection = this.futureConn.get();
            Properties authHeaders = auth.getAuthHeaders(basisURL, null);
            response = new Response(connection, getAuthHeaders());
            connection.disconnect();
            flushParams();

        } catch (Exception e) {
            System.err.println("FAILED - there was an exception");
            e.printStackTrace();
            response = new Response();
        }

        return response;
    }

    public static void main(String...args) throws XPathExpressionException, IOException, SAXException {
        //get auth
        TradeBlockKeyStore store = TradeBlockKeyStore.parseXml(Request.class.getResourceAsStream("/keys.xml"));
        TradeBlockKey auth = store.getNewestKey();

        //test demo
        Request demo = new Request(auth);
        //test prod
        Request prod = new Request(auth);

        demo.get("https://demo.tradeblock.com/api/v1.1/user/info");
        Response demo_resp = demo.fmtResponse();

        prod.get("https://tradeblock.com/api/v1.1/user/info");
        Response prod_resp = prod.fmtResponse();
    }
}

