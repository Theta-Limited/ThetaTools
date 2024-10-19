// TestHashCollisions.java
// Bobby Krupczak

// load an image, construct json object of pertinent metadata
// sort json object, get hash

// javac -cp .:./metadata-extractor-2.16.0.jar:./json-20210307.jar:./core-0.2.5-SNAPSHOT.jar TestHashCollisions.java

import org.json.JSONObject;

import java.util.Iterator;
import java.util.SortedMap;
import java.util.TreeMap;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Directory;
import com.drew.metadata.Metadata;
import com.drew.metadata.Tag;

import java.util.Objects;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import com.openathena.core.DroneImage;
import com.openathena.core.LRUCache;

public class TestHashCollisions
{
    public static LRUCache<Integer,DroneImage> cache = new LRUCache<>(10000);
    public static int numFiles = 0;
	
    public static JSONObject sortJsonObject(JSONObject jsonObject) {
        // Create a TreeMap to store the key-value pairs in sorted order
        TreeMap<String, Object> sortedMap = new TreeMap<>();

        // Iterate through the keys of the JSONObject and put them in the TreeMap
        for (String key : jsonObject.keySet()) {
            sortedMap.put(key, jsonObject.get(key));
        }

        // Create a new JSONObject from the sorted map (leaving the original unchanged)
        return new JSONObject(sortedMap);
    }
    

    public static void main(String[] args)
    {
	if (args.length == 0) {
	    System.out.println("Pass the directory path as argument");
	    return;
	}

	File directory = new File(args[0]);

	if (directory.exists() && directory.isDirectory()) {
	    processDirectory(directory);
	}

	System.out.println("Tested "+numFiles+" image files");
    }

    public static void processDirectory(File directory)
    {
	// Get all files and subdirectories in the current directory
        File[] files = directory.listFiles();
        
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    // Recursively process subdirectories
                    processDirectory(file);
                } else if (file.isFile() && isJpegFile(file)) {
                    // Process JPEG files
                    try {
                        System.out.println("Processing file: " + file.getCanonicalPath());
			getMetadataHash(file);
                    } catch (Exception e) {
                        System.out.println("Error processing file: "+e);
                    }
                }
            }
        }
    }

    public static boolean isJpegFile(File file) {
        String fileName = file.getName().toLowerCase();
        return fileName.endsWith(".jpg") || fileName.endsWith(".jpeg");
    }
    
    // given a file, get metadata hash of sorted json object
    public static void getMetadataHash(File aFile)
    {
	try {
	    DroneImage droneImage = new DroneImage(aFile.getPath());
	    JSONObject o = new JSONObject();
	    
            o.put("Name",droneImage.getImageFilename());
            o.put("GPS Latitude",Double.toString(droneImage.getLatitude()));
            o.put("GPS Longitude",Double.toString(droneImage.getLongitude()));
            o.put("Focal Length",Double.toString(droneImage.getFocalLength()));
            o.put("ImageWidth",Integer.toString(droneImage.getWidth()));
            o.put("ImageHeight",Integer.toString(droneImage.getHeight()));
            o.put("Camera:Roll",Double.toString(droneImage.getRoll()));
            o.put("Digital Zoom Ratio",Double.toString(droneImage.getZoom()));
            o.put("GPS Altitude",Double.toString(droneImage.getAltitude()));
            o.put("drone:GimbalPitchDegree",Double.toString(droneImage.getGimbalPitchDegree()));
            o.put("drone:GimbalYawDegree",Double.toString(droneImage.getGimbalYawDegree()));
            o.put("tiff:Make",droneImage.getCameraMake());
            o.put("tiff:Model",droneImage.getCameraModel());
            o.put("Date/Time Original",droneImage.getExifDateTime());

	    JSONObject sortedJsonObject = sortJsonObject(o);
	    sortedJsonObject.put("xprop","0.0");
	    sortedJsonObject.put("yprop","0.0");
	
	    // now calculate hash over sorted object
	    int anInt = Objects.hash(sortedJsonObject.toString());
	    DroneImage anotherImage = cache.get(Integer.valueOf(anInt));

	    if (anotherImage != null) {
		System.out.println("Cache collision for "+anInt);
		System.out.println(anotherImage.getImageFilename()+","+aFile.getPath());
		System.out.println("Processed "+numFiles+" image files");
		System.exit(-1);
	    }
	    else {
		cache.put(Integer.valueOf(anInt),droneImage);
	    }

	    // check cache

	} catch (Exception e) {
	    System.out.println("Skipping processing image "+e);
	}

	return;
    }

    

}
