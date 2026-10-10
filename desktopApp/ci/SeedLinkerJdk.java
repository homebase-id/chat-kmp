import hydraulic.diskcache.LocalDiskCache;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

// Foojay's oracle_open_jdk index lacks JDK 21+, so pre-answer Conveyor's linker-JDK lookup in its disk cache.
// ponytail: KEY mirrors Conveyor's private LinkerJDKTask key; if it changes this no-ops and Foojay is asked again.
public class SeedLinkerJdk {
    static final String KEY = "Foojay API request for oracle_open_jdk using query "
            + "version=21&operating_system=linux&architecture=x64&latest=available";
    static final String URL = "https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.10%2B7/"
            + "OpenJDK21U-jdk_x64_linux_hotspot_21.0.10_7.tar.gz";

    public static void main(String[] args) throws Exception {
        try (var cache = new LocalDiskCache(Path.of(args[0]), new LocalDiskCache.Configuration()).open()) {
            // Written in the reader: a failed Foojay lookup leaves an empty entry that compute() won't rebuild.
            cache.compute(KEY, dir -> {}, dir -> {
                try {
                    var response = dir.resolve("response.txt");
                    if (Files.notExists(response)) {
                        Files.writeString(response, URL + "\n" + URL.substring(URL.lastIndexOf('/') + 1));
                    }
                    return null;
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }
    }
}
