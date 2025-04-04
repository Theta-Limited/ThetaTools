// DemDownloader.java
// Bobby Krupczak and ChatGPT
// download a digital elevation model/map
// from OpenTopography.org and write out
// to a tiff file; API Key is fetched
// from env variable OPENTOPOGRAPHY_API_KEY
// run from command-line via:
// java -cp . DemDownloader lat lon length

import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URI;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.IOException;
import java.io.FileOutputStream;
import java.util.function.Consumer;

public class DemDownloader
{
    private String apiKeyStr; // API Key
    private static final String URL_STR = "https://portal.opentopography.org/API/globaldem?";
    private int responseCode;
    private int responseBytes;
    private double s, w, n, e; // Bounding box coordinates
    private String filenameSuffix = ".tiff";
    private String outputFormatStr = "GTiff";
    private static boolean usgs = false; // download "USGS10m 3DEP instead of SRTM
    private static boolean eudtm = false; // download EU_DTM 30m instead of SRTM
    private static boolean cop30 = false; // download cop30 instead of SRTM
    private static boolean srtm = false;  // download SRTM dataset

    public DemDownloader(double lat, double lon, double length) {
        double[] boundingBox = getBoundingBox(lat, lon, length);

	n = boundingBox[0];
        s = boundingBox[1];
        e = boundingBox[2];
        w = boundingBox[3];

	System.out.println(n+","+s+","+e+","+w);

        apiKeyStr = "NeedApiKey";
	// read API key from environment variable
	apiKeyStr = System.getenv("OPENTOPOGRAPHY_API_KEY");
	if (apiKeyStr.equals("")) {
	    apiKeyStr = "GiveMeApiKey";
        }
    }

    // Method to download DEM with blocking
    // 
    public boolean syncDownload() throws Exception
    {
	boolean b = false;
        
        String srtmRequestURLStr = URL_STR +
                "demtype=SRTMGL1" + 
                "&south=" + s +
                "&north=" + n +
                "&west=" + w +
                "&east=" + e +
                "&outputFormat=" + outputFormatStr +
                "&API_Key=" + apiKeyStr;
	System.out.println("SRTM urlStr is "+srtmRequestURLStr);

        // USGS 3DEP 10m
        String usgsRequestURLStr = "https://portal.opentopography.org/API/usgsdem?";
        usgsRequestURLStr += "datasetName=USGS10m"+
                "&south=" + s +
                "&north=" + n +
                "&west=" + w +
                "&east=" + e +
                "&outputFormat=" + outputFormatStr +
                "&API_Key=" + apiKeyStr;
        System.out.println("USGS10m urlStr is "+usgsRequestURLStr);        

        // cop30 30m
        String cop30RequestURLStr = URL_STR +
                "demtype=COP30" +
                "&south=" + s +
                "&north=" + n +
                "&west=" + w +
                "&east=" + e +
                "&outputFormat=" + outputFormatStr +
                "&API_Key=" + apiKeyStr;
        System.out.println("COP30 urlStr is "+cop30RequestURLStr);

        // EU DTM
        String eudtmRequestURLStr = URL_STR +
                "demtype=EU_DTM"+
                "&south=" + s +
                "&north=" + n +
                "&west=" + w +
                "&east=" + e +
                "&outputFormat=" + outputFormatStr +
                "&API_Key=" + apiKeyStr;
        System.out.println("EUDTM urlStr is "+eudtmRequestURLStr);

        URL url;
        URI uri;

        if (usgs == true) {
            System.out.println("Fetching USGS10m data");
            uri = new URI(usgsRequestURLStr);
            filenameSuffix = ".3dep";
        }
        else if (eudtm == true) {
            System.out.println("Fetching EU DTM data");
            uri = new URI(eudtmRequestURLStr);
            filenameSuffix = ".eudtm";
        }
        else if (cop30 == true) {
            System.out.println("Fetching COP30 data");
            uri = new URI(cop30RequestURLStr);
            filenameSuffix = ".cop30";
        }
        else if (srtm == true) {
            System.out.println("Fetching SRTM data");
            uri = new URI(srtmRequestURLStr);
            filenameSuffix = ".srtm";
        }
        else {
            System.out.println("You did not specify a dataset to use; assuming SRTM");
            uri = new URI(srtmRequestURLStr);
            filenameSuffix = ".srtm";
        }
        url = uri.toURL();

        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");

	int responseCode = connection.getResponseCode();
	if (responseCode != HttpURLConnection.HTTP_OK) {
	    System.out.println("Request failed, error code "+responseCode);
	    return false;
	}

        // read and write out the data to file
        String filename = "DEM_LatLon_"+s+"_"+w+"_"+n+"_"+e+filenameSuffix;

	try {
	    InputStream inputStream = connection.getInputStream();
	    FileOutputStream outputStream = new FileOutputStream(filename);

	    byte[] buffer = new byte[4096];
	    int bytesRead = 0;
	    int totalBytes = 0;

	    while ((bytesRead = inputStream.read(buffer)) != -1) {
		outputStream.write(buffer,0,bytesRead);
		totalBytes += bytesRead;
	    }
	    outputStream.close();
	    System.out.println("Wrote "+totalBytes+" bytes to "+filename);
	    b = true;
	}
	catch (Exception e) {
	    System.out.println("Write to fail failed "+e);
	    b = false;
	}

        connection.disconnect();

	return true;
	
    } // download

    // down a DEM async or in background
    // callback will indicate success or error
    // pass an object that implements a callback metho
    // onCallback() method
    
    public void asyncDownload(Consumer<String> consumer)
    {
	Thread aThread = new Thread(new Runnable() {
	   @Override
	   public void run()
	   {
	       boolean b;
	       
	       // call the sync download from within our thread
	       //
	       try {
		   b = syncDownload();
                   consumer.accept("Download returned "+b);
	       }
	       catch (Exception e) {
	           consumer.accept(e.toString());
	       }
	   } // run
	});

	aThread.start();
	System.out.println("asyncDownloading started");	

    } // downloaAdsync

    // calculate bounding box; return [n,s,e,w]
    private double[] getBoundingBox(double centerLat, double centerLon, double length)
    {
        final double metersInDegreeLatitude = 111320; // Approximate meters in one degree of latitude

        // Calculate deltas
        double deltaLat = (length / 2) / metersInDegreeLatitude;
        double deltaLon = (length / 2) / (metersInDegreeLatitude * Math.cos(Math.toRadians(centerLat)));

        // Calculate bounding box
        double north = centerLat + deltaLat;
        double south = centerLat - deltaLat;
        double east = centerLon + deltaLon;
        double west = centerLon - deltaLon;

        return new double[]{truncateDouble(north,6), truncateDouble(south,6), truncateDouble(east,6), truncateDouble(west,6)};
    }
    
    // Method to calculate the bounding box
    // private double[] getBoundingBox(double centerLat, double centerLon, double length) {
    //     double d = Math.sqrt(2.0) * (length / 2.0);
    //     double[] sw = calculateCoordinate(centerLat, centerLon, 225.0, d);
    //     double[] ne = calculateCoordinate(centerLat, centerLon, 45.0, d);

    //     return new double[]{truncateDouble(sw[0], 6), truncateDouble(sw[1], 6), 
    //                         truncateDouble(ne[0], 6), truncateDouble(ne[1], 6)};
    // }

    // // Helper method to calculate new coordinate; ChatGPT 
    // private double[] calculateCoordinate(double lat, double lon, double bearing, double distance) {
    //     double radius = 6371e3; // Earth's radius in meters
    //     double angularDistance = distance / radius;

    //     double latRad = Math.toRadians(lat);
    //     double lonRad = Math.toRadians(lon);
    //     bearing = Math.toRadians(bearing);

    //     double newLat = Math.asin(Math.sin(latRad) * Math.cos(angularDistance) +
    //                               Math.cos(latRad) * Math.sin(angularDistance) * Math.cos(bearing));
    //     double newLon = lonRad + Math.atan2(Math.sin(bearing) * Math.sin(angularDistance) * Math.cos(latRad),
    //                                         Math.cos(angularDistance) - Math.sin(latRad) * Math.sin(newLat));

    //     return new double[]{Math.toDegrees(newLat), Math.toDegrees(newLon)};
    // }

    public static double truncateDouble(double val, int precision) {
        double scale = Math.pow(10, precision);
        return Math.round(val * scale) / scale;
    }


    public static void main(String[] args) throws Exception
    {
        int index = 0;
        
	if (args.length < 3) {
	    System.out.println("java DemDownloader [-srtm|-usgs|-eudtm|-cop30] lat lon length");
	    System.exit(-1);
	}

        // see if we have optional dataset arg
        // chatgpt!
        // all dataset choices are false; setting this option
        // enables one to be true
        
        if (args.length > 0 && args[0].equals("-usgs")) {
            usgs = true;
            index = 1; // move to next arg
        }
        if (args.length > 0 && args[0].equals("-eudtm")) {
            eudtm = true;
            index = 1; // move to next arg
        }
        if (args.length > 0 && args[0].equals("-cop30")) {
            cop30 = true;
            index = 1; // move to next arg
        }
        if (args.length > 0 && args[0].equals("-srtm")) {
            srtm = true;
            index = 1; // move to next arg
        }
        
	Double lat = Double.parseDouble(args[index]);
	Double lon = Double.parseDouble(args[index+1]);
	Double len = Double.parseDouble(args[index+2]);
	    
	System.out.println("Fetching DEM at ("+lat+","+lon+") "+len+" hxw meters");

	DemDownloader aDownloader = new DemDownloader(lat,lon,len);

	try {
	    aDownloader.asyncDownload(new Consumer<String>() {
		    @Override
		    public void accept(String s) {
			System.out.println(s);
		    }
	     });
	} catch (Exception e) {
	    System.out.println("Download failed "+e);
	}
	
    } // main

} // DemDownloader

