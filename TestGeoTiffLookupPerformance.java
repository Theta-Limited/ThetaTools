// TestGeoTiffLookupPerformance.java

import java.io.InputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import com.openathena.core.DEMParser;

public class TestGeoTiffLookupPerformance
{
    public static void main(String[] args) throws Exception
    {
        if (args.length < 4) {
            System.out.println("Usage: java TestGeoTiffLookupPerformance <GeoTIFF file> <latitude> <longitude> <iterations>");
            return;
        }

        String inputFilePath = args[0];
        double latitude = Double.parseDouble(args[1]);
        double longitude = Double.parseDouble(args[2]);
        int i;
        File aFile = new File(inputFilePath);
        DEMParser aParser = new DEMParser(aFile);
        double alt=0.0;
        int iterations = Integer.parseInt(args[3]);

        // warm up helps JIT optimizations
        for (i = 0; i<1000; i++) {
            alt = aParser.getAltFromLatLon(latitude,longitude);            
        }

        // do X lookups of lat,lon from DEMParser
        long startTime = System.nanoTime();
        for (i=0; i<iterations; i++) {
            alt = aParser.getAltFromLatLon(latitude,longitude);
        }
        long endTime = System.nanoTime();
        long elapsedNanos = endTime - startTime;
        double elapsedMillis = elapsedNanos / 1_000_000.0;

        System.out.println("Elapsed time for "+iterations+" calls is "+elapsedMillis + " ms");
    }

} // TestGeoTiffLookupPerformance

// %java -cp "lib/*" TestGeoTiffLookupPerformance.java blah.xxx -54.0 -68.0
