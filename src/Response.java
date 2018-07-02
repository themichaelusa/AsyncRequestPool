import javax.net.ssl.HttpsURLConnection;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public class Response {
    public int code;
    public String message;
    public JSONObjectWrapper bodyStream;
    public JSONObjectWrapper errorStream;
    public Properties authHeaders;

    public Response(){}

    public Response(HttpsURLConnection conn, Properties headers){
        try {
            this.code = conn.getResponseCode();
            this.message = conn.getResponseMessage();
            this.authHeaders = headers;
            InputStream bodStream = conn.getInputStream();
            this.bodyStream = new JSONObjectWrapper(JSONObjectWrapper.wrapStream
                    (bodStream, true));

            InputStream errStream = conn.getInputStream();
            if (errStream != null){
                this.errorStream = new JSONObjectWrapper(JSONObjectWrapper.wrapStream
                        (errStream, true));
            }
        } catch (IOException e) {
            System.err.println("FAILED - Response I/O Exception");
            e.printStackTrace();
        }
    }


}
