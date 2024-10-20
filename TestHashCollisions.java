// TestHashCollisions.java
// Bobby Krupczak

// load an image, construct json object of pertinent metadata
// sort json object, get hash

// javac -cp .:./metadata-extractor-2.16.0.jar:./json-20210307.jar:./core-0.2.5-SNAPSHOT.jar TestHashCollisions.java
// java -cp .:./metadata-extractor-2.16.0.jar:./json-20210307.jar:./core-0.2.5-SNAPSHOT.jar TestHashCollisions dir

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
import com.openathena.core.DroneImageFactory;
import com.openathena.core.LRUCache;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;

public class TestHashCollisions
{
    public static LRUCache<String,JSONObject> cache = new LRUCache<>(10000);
    public static int numFiles = 0;
    private static MessageDigest digest = null;    
	
    public static JSONObject sortJsonObject(JSONObject jsonObject)
    {
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
	DroneImage droneImage;

	numFiles++;

        //System.out.println("Processing "+aFile.getPath());	    
	
	try {
	    droneImage = DroneImageFactory.create(aFile.getPath());
	    JSONObject o = new JSONObject();

   	    //System.out.println("Created drone image for "+aFile.getPath());	    
	    
            // o.put("Name",droneImage.getImageFilename());
	    // don't put in filename so we don't factor that into hash
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
	    sortedJsonObject.put("Name","");	    

   	    //System.out.println("Created drone json object for "+aFile.getPath());
	    
	    // now calculate hash over sorted object
	    String hashStr = getObjectsHash(sortedJsonObject.toString());
	    //String hashStr = getSHA256Hash(sortedJsonObject.toString());

            System.out.println("Processing file hash is: " + aFile.getCanonicalPath()+" "+hashStr);

	    JSONObject i = cache.get(hashStr);

	    if ((i != null) && (validateImage(i,o) == false)) {
		System.out.println("Cache collision for "+aFile.getPath()+", "+hashStr);
		//System.exit(-1);
	    }
	    else {
		cache.put(hashStr,o);
	    }

	    // check cache

	} catch (Exception e) {
	    System.out.println("Skipping processing image "+e);
	}

	return;
    }

    public static boolean validateImage(JSONObject i, JSONObject o)
    {
	String lat,lon,alt,az,pitch;

	try {
	    lat = o.getString("GPS Latitude");
	    lon = o.getString("GPS Longitude");
	    alt = o.getString("GPS Altitude");
	    az = o.getString("drone:GimbalYawDegree");
	    pitch = o.getString("drone:GimbalPitchDegree");

	    if (lat.equals(i.getString("GPS Latitude")) == false) {
		System.out.println("Drone cache image failed to match latitude");
		return false;		
    	    }
	    if (lon.equals(i.getString("GPS Longitude")) == false) {
		System.out.println("Drone cache image failed to match longitude");
		return false;		
    	    }
	    if (alt.equals(i.getString("GPS Altitude")) == false) {
		System.out.println("Drone cache image failed to match altitude");
		return false;		
    	    }
	    if (az.equals(i.getString("drone:GimbalYawDegree")) == false) {
		System.out.println("Drone cache image failed to match az");
		return false;		
    	    }
	    if (pitch.equals(i.getString("drone:GimbalPitchDegree")) == false) {
		System.out.println("Drone cache image failed to match pitch");
		return false;		
    	    }
	}
	catch (Exception e) {
	    System.out.println("Drone image missing needed metadata for validation");
	    return false;
	}

	System.out.println("Images are validated and equivalent");
	
	return true;
    }

    // pass a toString() version of sorted json object; get back
    // a string hash

    // use Objects hash function to compute hash over sorted JSON object string
    // computation time good and tests indicate no collisions
    // return string of hash
    
    public static String getObjectsHash(String oStr)
    {
	int anInt = Objects.hash(oStr);
	return String.valueOf(anInt);
    }
    
    
    public static String getSHA256Hash(String oStr)
    {
        try {
	    if (digest == null) {
		digest = MessageDigest.getInstance("SHA-256");	    		
	    }
	    
            // Compute the hash (returns a byte array)
            byte[] hashBytes = digest.digest(oStr.getBytes(StandardCharsets.UTF_8));

            // Convert the byte array to a hex string for easier storage
            StringBuilder hexString = new StringBuilder();
            for (byte b : hashBytes) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();  // Return the hex string representation of the hash

        } catch (NoSuchAlgorithmException e) {
	    System.out.println("getSHA256Hash: exception "+e);
            throw new RuntimeException(e);
        }
    }

}
