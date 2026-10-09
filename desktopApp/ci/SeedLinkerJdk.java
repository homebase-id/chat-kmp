import hydraulic.diskcache.LocalDiskCache;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

// Conveyor finds its linker JDK through Foojay's oracle_open_jdk index, which no longer lists JDK 21+.
// Pre-answer that lookup in Conveyor's own disk cache so `make` never asks Foojay.
// ponytail: mirrors LinkerJDKTask's private cache key; if Conveyor changes it this is a no-op and Foojay is asked again.
// Usage: java -cp '<conveyor>/lib/app/*' SeedLinkerJdk.java <cache-dir> <foojay-query> <jdk-url>
public class SeedLinkerJdk {
    public static void main(String[] args) throws Exception {
        var url = args[2];
        var key = "Foojay API request for oracle_open_jdk using query " + args[1];
        try (var cache = new LocalDiskCache(Path.of(args[0]), new LocalDiskCache.Configuration()).open()) {
            // Written in the reader too: a failed Foojay lookup leaves an empty entry behind.
            String seeded = cache.compute(key, dir -> {}, dir -> {
                try {
                    var response = dir.resolve("response.txt");
                    if (Files.notExists(response)) {
                        Files.writeString(response, url + "\n" + url.substring(url.lastIndexOf('/') + 1));
                    }
                    return Files.readString(response);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
            System.out.println("Linker JDK for " + args[1] + ":\n" + seeded);
        }
    }
}
