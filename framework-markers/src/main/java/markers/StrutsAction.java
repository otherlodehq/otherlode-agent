package markers;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

public class StrutsAction {
    public String execute() {
        return "success";
    }

    public InputStream getStream() {
        return new ByteArrayInputStream("ok".getBytes());
    }
}
