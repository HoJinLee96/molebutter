package cc.ataglace.molebutter.procurement.internal;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
final class FixtureText {
    private FixtureText() {}
    static String read(String path)throws IOException {
        try(var stream=FixtureText.class.getClassLoader().getResourceAsStream(path)){
            if(stream==null)throw new IOException("Missing test fixture: "+path);
            return new String(stream.readAllBytes(),StandardCharsets.UTF_8);
        }
    }
}
