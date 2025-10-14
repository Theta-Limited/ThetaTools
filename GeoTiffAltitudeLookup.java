// GeoTiffAltitudeLookup

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.ArrayList;

import mil.nga.tiff.Rasters;
import mil.nga.tiff.TIFFImage;
import mil.nga.tiff.TiffReader;
import mil.nga.tiff.FileDirectory;
import mil.nga.tiff.FileDirectoryEntry;
import mil.nga.tiff.Rasters;
import mil.nga.tiff.FieldTagType;
import mil.nga.tiff.util.TiffConstants;

import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateReferenceSystem;
import org.locationtech.proj4j.CoordinateTransform;
import org.locationtech.proj4j.CoordinateTransformFactory;
import org.locationtech.proj4j.ProjCoordinate;

import com.agilesrc.dem4j.dted.impl.FileBasedDTED;
import com.agilesrc.dem4j.exceptions.CorruptTerrainException;
import com.agilesrc.dem4j.exceptions.InvalidValueException;
import com.agilesrc.dem4j.Point;
import com.agilesrc.dem4j.dted.DTEDLevelEnum;

//  %javac -cp "lib/*" GeoTiffAltitudeLookup.java
// java -cp ".:lib/*" GeoTiffAltitudeLookup

public class GeoTiffAltitudeLookup
{
    public enum GeoTiffDataType
    {
        // extension, dataset, horiz, vert, description
        
        TIFF("tiff","SRTMGL1","EPSG:4326","EPSG:5773","Shuttle Radar Topography Mission Global 30m"),
        THREEDEP("3dep","3DEP","EPSG:4269","EPSG:5703","USGS 1/3 arc-second Digital Elevation Model 10m"),
        DTED2("dt2","DTED2","EPSG:4326","EPSG:5773","DTED2+ 1 arc sec 30m"),
        EUDTM("eudtm","EU_DTM", "EPSG:3035","EPSG:3855", "Continental Europe Digital Terrain Model 30m"),
        COP30("cop30","COP30","EPSG:4326", "EPSG:3855", "Copernicus Global DSM 30m"),
        OTHER("tiff","SRTMGL1","EPSG:4326","EPSG:5773","Other/unknown");

        private final String extension;
        private final String dataSetName;
        private final String horizDatum;        
        private final String vertDatum;
        private final String description;

        GeoTiffDataType(String extension, String dataSetName, String horizDatum, String vertDatum, String description) {
            this.extension = extension;
            this.description = description;
            this.dataSetName = dataSetName;
            this.horizDatum = horizDatum;
            this.vertDatum = vertDatum;
        }

        public String getExtension() { return extension; }
        public String getDescription() { return description; }
        public String getDataSetName() { return dataSetName; }

        public static String getDescriptionForExtension(String extension) {
            for (GeoTiffDataType t: values()) {
                if (t.getExtension().equalsIgnoreCase(extension)) { return t.getDescription(); }
            }
            return "Other/unknown";
        }
        public static String getDataSetNameForExtension(String extension) {
            for (GeoTiffDataType t: values()) {
                if (t.getExtension().equalsIgnoreCase(extension)) { return t.getDataSetName(); }
            }
            return "SRTMGL1";
        }
    }

    public static void main(String[] args) {
        if (args.length < 3) {
            System.out.println("Usage: java GeoTiffAltitudeLookup <GeoTIFF file> <latitude> <longitude>");
            return;
        }

        String inputFilePath = args[0];
        double latitude = Double.parseDouble(args[1]);
        double longitude = Double.parseDouble(args[2]);

        // System.out.println("Test USA should be true");
        // testUSA(testUSACoordinates);
        // System.out.println("Test USA should be false");        
        // testUSA(testEuropeCoordinates);

        // System.out.println("Test Europe should be true");                
        // testEurope(testEuropeCoordinates);
        // System.out.println("Test Europe should be false");
        // testEurope(testUSACoordinates);        

        System.out.println(latitude+","+longitude+" is in USA? "+isInUSA(latitude,longitude));
        System.out.println(latitude+","+longitude+" is in Europe? "+isInEurope(latitude,longitude));

        long t0, t1;
        if (inputFilePath.endsWith(".dt2")) {
            t0 = System.nanoTime();                
            readDted(inputFilePath,latitude,longitude);
            t1 = System.nanoTime();                            
        }
        else {
            t0 = System.nanoTime();                            
            readGeoTiff(inputFilePath,latitude,longitude);
            t1 = System.nanoTime();                                        
        }
        System.out.println("GeoTiff took " + ((t1 - t0)/1_000_000) + " ms");        
    }

    private static void readDted(String inputFilePath, double latitude, double longitude)
    {
        try {
            File geofile = new File(inputFilePath);
            FileBasedDTED dted = new FileBasedDTED(geofile);
            DTEDLevelEnum dtedLevel = dted.getDTEDLevel();
            System.out.println("DTED level "+dtedLevel);
            if (dtedLevel.equals(DTEDLevelEnum.DTED0) || dtedLevel.equals(DTEDLevelEnum.DTED1)) {
                System.out.println("DTED2 or DTED3 or higher is required");
                return;
            }

            // can always test this against gdalinfo output
            double n = dted.getNorthWestCorner().getLatitude();
            double w = dted.getNorthWestCorner().getLongitude();
            double s = dted.getSouthEastCorner().getLatitude();
            double e = dted.getSouthEastCorner().getLongitude();
                
            double latSpacing = dted.getLatitudeInterval();
            double lonSpacing = dted.getLongitudeInterval();
            int numRows = dted.getRows();
            int numCols = dted.getColumns();
            //double s = n - (numRows - 1) * latSpacing;
            //double e = w + (numCols - 1) * lonSpacing;
            
            System.out.println("dted: n: "+n);
            System.out.println("dted: w: "+w);
            System.out.println("dted: s: "+s);
            System.out.println("dted: e: "+e);

            System.out.println("dted: resolution "+dted.getResolution().getRows()+","+dted.getResolution().getColumns()+" "+dted.getResolution().getSpacing()+" degrees");

            // elevation is emg96 already
            Point point = new Point(latitude, longitude);
            double altitude = dted.getElevation(point).getElevation();
            System.out.printf("Altitude at (%.6f, %.6f) is %.6f meters.%n", latitude, longitude, altitude);

        }
        catch (InvalidValueException e) {
            System.out.println(latitude+","+longitude+" is not in this elevation model");
        }
        catch (Exception e) {
            e.printStackTrace();
        }
        
        return;
    }

    private static void readGeoTiff(String inputFilePath, double latitude, double longitude)
    {

        try {
            // Read the GeoTIFF file
            TIFFImage tiffImage = TiffReader.readTiff(new File(inputFilePath));
            FileDirectory directory = tiffImage.getFileDirectories().get(0);
            Rasters rasters = directory.readRasters();
            double n,s,e,w; // bounding box wgs84 coordinates

            // Extract ModelPixelScaleTag (33550)
            List<Double> modelPixelScale = getTagValues(directory, 33550, Double.class);
            if (modelPixelScale == null || modelPixelScale.size() < 2) {
                System.out.println("ModelPixelScaleTag not found or invalid.");
                return;
            }

            double pixelScaleX = modelPixelScale.get(0);
            double pixelScaleY = modelPixelScale.get(1);
            int width = directory.getImageWidth().intValue();
            int height = directory.getImageHeight().intValue();

            // Extract ModelTiepointTag (33922)
            List<Double> modelTiepoints = getTagValues(directory, 33922, Double.class);
            if (modelTiepoints == null || modelTiepoints.size() < 6) {
                System.out.println("ModelTiepointTag not found or invalid.");
                return;
            }

            double tiePointX = modelTiepoints.get(3);
            double tiePointY = modelTiepoints.get(4);
            double tiePointZ = modelTiepoints.get(5);

            System.out.printf("Model Pixel Scale: (%.6f, %.6f)%n", pixelScaleX, pixelScaleY);
            System.out.printf("Model Tie Point: (%.6f, %.6f, %.6f)%n", tiePointX, tiePointY, tiePointZ);
            System.out.println("Size is "+width+","+height);

            // Extract CRS information
            List<Integer> geoKeyDirectory = getTagValues(directory, 34735, Integer.class);
            String horizontalCRS = extractHorizontalDatum(geoKeyDirectory);
            String verticalCRS = extractVerticalDatum(geoKeyDirectory);
            System.out.println("Horizontal Datum: " + (horizontalCRS != null ? horizontalCRS : "Unknown"));
            System.out.println("Vertical Datum is "+ verticalCRS);

            // two ways to output the bounding box coordinates; center of pixel
            // or edge of pixel

            // tiepoint is center-of-pixel-assumption method
            // this approach seems closest to our downloader bounding box code
            // and to gdalinfo
            double ulX = tiePointX - pixelScaleX / 2.0;
            double ulY = tiePointY + pixelScaleY / 2.0;
            double lrX = tiePointX + (width - 0.5) * pixelScaleX;
            double lrY = tiePointY - (height - 0.5) * pixelScaleY;
            double urX = lrX;
            double urY = ulY;
            double llX = ulX;
            double llY = lrY;

            // tiepoint is edge-of-pixel method
            // double ulX = tiePointX;
            // double ulY = tiePointY;
            // double lrX = tiePointX + width * pixelScaleX;
            // double lrY = tiePointY + height * pixelScaleY;
            // double urX = lrX;
            // double urY = ulY;
            // double llX = ulX;
            // double llY = lrY;
            
            n = ulY;
            s = llY;
            w = ulX;
            e = lrX;

            // OA DEM filenaming scheme is
            // DEM_LatLon_+s+_+w+_+n+_e.suffix
            
            System.out.println("Native CRS Corners:");
            System.out.printf("Upper Left (UL):    (%.6f, %.6f)%n", ulX, ulY);
            System.out.printf("Lower Left (LL):    (%.6f, %.6f)%n", llX, llY);            
            System.out.printf("Upper Right (UR):   (%.6f, %.6f)%n", urX, urY);
            System.out.printf("Lower Right (LR):   (%.6f, %.6f)%n", lrX, lrY);

            CRSFactory crsFactory = new CRSFactory();
            CoordinateTransformFactory transformFactory = new CoordinateTransformFactory();
                
            if (horizontalCRS != null && !horizontalCRS.equals("EPSG:4326")) {
                CoordinateReferenceSystem tiffCRS = crsFactory.createFromName(horizontalCRS);
                CoordinateReferenceSystem wgs84CRS = crsFactory.createFromName("EPSG:4326");
                CoordinateTransform transform = transformFactory.createTransform(tiffCRS, wgs84CRS);
                ProjCoordinate ulSrc = new ProjCoordinate(ulX,ulY);
                ProjCoordinate urSrc = new ProjCoordinate(urX,urY);                
                ProjCoordinate llSrc = new ProjCoordinate(llX,llY);
                ProjCoordinate lrSrc = new ProjCoordinate(lrX,lrY);

                ProjCoordinate ulWgs84 = new ProjCoordinate();
                ProjCoordinate urWgs84 = new ProjCoordinate();
                ProjCoordinate llWgs84 = new ProjCoordinate();
                ProjCoordinate lrWgs84 = new ProjCoordinate();

                transform.transform(ulSrc,ulWgs84);
                transform.transform(urSrc,urWgs84);                
                transform.transform(llSrc,llWgs84);
                transform.transform(lrSrc,lrWgs84);

                n = ulWgs84.y;
                s = llWgs84.y;
                w = ulWgs84.x;
                e = lrWgs84.x;

                System.out.printf("%nBounding Box in WGS84 (lat,lon):%n");
                System.out.printf("Upper Left (UL):    (lat: %.6f, lon: %.6f)%n", ulWgs84.y, ulWgs84.x);
                System.out.printf("Upper Right (UR):   (lat: %.6f, lon: %.6f)%n", urWgs84.y, urWgs84.x);
                System.out.printf("Lower Left (LL):    (lat: %.6f, lon: %.6f)%n", llWgs84.y, llWgs84.x);
                System.out.printf("Lower Right (LR):   (lat: %.6f, lon: %.6f)%n", lrWgs84.y, lrWgs84.x);
            }

            System.out.printf("geotiff: n: %.6f%n",n);
            System.out.printf("geotiff: w: %.6f%n",w);
            System.out.printf("geotiff: s: %.6f%n",s);
            System.out.printf("geotiff: e: %.6f%n",e);            

            // prepare coordinate for conversion if needed
            // Convert WGS84 (latitude, longitude) to GeoTIFF's CRS
            ProjCoordinate inputCoord = new ProjCoordinate(longitude, latitude);
            ProjCoordinate tiffCoord = new ProjCoordinate();

            // Only convert input coordinates (WGS84) if the horizontal CRS is not WGS84
            if (horizontalCRS != null && !horizontalCRS.equals("EPSG:4326")) {
                System.out.println("Converting coordinates to GeoTIFF's CRS: " + horizontalCRS);

                CoordinateReferenceSystem sourceCRS = crsFactory.createFromName("EPSG:4326"); // WGS 84
                CoordinateReferenceSystem targetCRS = crsFactory.createFromName(horizontalCRS);
                CoordinateTransform transform = transformFactory.createTransform(sourceCRS, targetCRS);

                transform.transform(inputCoord, tiffCoord);

                System.out.printf("Converted Coordinates: (%.6f, %.6f)%n", tiffCoord.x, tiffCoord.y);
            } else {
                System.out.println("GeoTIFF is already in WGS84. No conversion needed.");
                tiffCoord = inputCoord;
            }


            // Calculate pixel indices in the raster
            int pixelX = (int) Math.round((tiffCoord.x - tiePointX) / pixelScaleX);
            int pixelY = (int) Math.round((tiePointY - tiffCoord.y) / pixelScaleY);

            System.out.printf("Raster Pixel Indices: (X: %d, Y: %d)%n", pixelX, pixelY);

            // Get the altitude from the raster data
            if (pixelX >= 0 && pixelX < rasters.getWidth() && pixelY >= 0 && pixelY < rasters.getHeight()) {
                Number[] altitude = rasters.getPixel(pixelX, pixelY);
                System.out.printf("Altitude at (%.6f, %.6f) is %.6f meters.%n", latitude, longitude, altitude[0].doubleValue());
                //System.out.println("WGS84 Alt is "+orthometricToWgs84(latitude,longitude,altitude[0].doubleValue()));
                double iAltitude = getInterpolatedAltitude(rasters,pixelX,pixelY);
                if (!Double.isNaN(iAltitude)) {
                    System.out.printf("interpolatedAltitude at (%.6f, %.6f) is %.6f meters.%n", latitude, longitude, iAltitude);                    
                }
                else {
                    System.out.println("Failed to interpolate altitude");
                }
            } else {
                System.out.println("Coordinates are outside the raster bounds.");
            }

        } catch (IOException e) {
            e.printStackTrace();
        }
    } // read geotiff

    /**
     * Extracts tag values from the FileDirectory as a List of Numbers.  Pass in the expected
     * type that we want so we can avoid compiler and runtime warnings
     */
    private static <T> List<T> getTagValues(FileDirectory directory, int tag, Class<T> type) {

        for (FileDirectoryEntry entry : directory.getEntries()) {
            
            if (entry.getFieldTag() == FieldTagType.getById(tag)) {
                
                Object values = entry.getValues();
                
                if (values instanceof List<?>) {

                    List<?>rawList = (List<?>)values;
                    List<T> castedValues = new ArrayList<>();

                    for (Object item: rawList) {

                        if (type.isInstance(item)) {
                            castedValues.add(type.cast(item));
                        }
                    }

                    return castedValues;
                    
                    // try {
                    //     List<?>rawList = (List<?>)values;
                    //     @SuppressWarnings("unchcecked")
                    //     List<T> castedValues = (List<T>)rawList;
                    //     return castedValues;
                    // } catch (ClassCastException e) {
                    //     System.err.println("Warning: Incorrect data type for tag " + tag);
                    // }
                }
            }
        }
        
        return null;
    }

    // EPSG: 4269 is NAD83 
    // EPSG: 4326 is WGS84 
    
    private static String extractHorizontalDatum(List<Integer> geoKeyDirectory) {
        final int GEOGRAPHIC_CRS_TAG = 2048; // GeographicTypeGeoKey
        final int PROJECTED_CRS_TAG = 3072;  // ProjectedCSTypeGeoKey

        for (int i = 0; i < geoKeyDirectory.size() - 3; i += 4) {
            int keyID = geoKeyDirectory.get(i);
            int epsgCode = geoKeyDirectory.get(i + 3);
            if (keyID == GEOGRAPHIC_CRS_TAG || keyID == PROJECTED_CRS_TAG) {
                return "EPSG:" + epsgCode;
            }
        }
        return null;
    }

    // NAVD88 [EPSG: 5703] is orthometric height H or AMSL
    // WGS84 = EGM96 + offset
    // h = ellipsoidal height
    // N = geoid height or offset
    // h = H + N
    // EPSG:3855 is EGM2008 or orthometric or AMSL height
    // EPSG:5773 is EGM96 or orthometric or AMSL height
    // EPSG:4979 is WGS84 ellipsoidal height
    // EPSG:5703 is NAVD88 orthometric height
        
    private static String extractVerticalDatum(List<Integer> geoKeyDirectory) {
        final int VERTICAL_CRS_TAG = 2059; // VerticalCSTypeGeoKey

        for (int i = 0; i < geoKeyDirectory.size() - 3; i += 4) {
            int keyID = geoKeyDirectory.get(i);
            int epsgCode = geoKeyDirectory.get(i + 3);
            if (keyID == VERTICAL_CRS_TAG) {
                switch (epsgCode) {
                case 4979:
                    return "EPSG:4979";
                case 5703:
                    return "EPSG:5703";
                case 5773:
                    return "EPSG:5773";
                default:
                    return new String("EPSG:" + epsgCode);
                }
            }
        }
        return "EPSG:0"; // unknown vertical datum
    }

    // given a lat, lon, and and altitude, convert from EGM96/orthometric
    // to WGS84 altitude
    // proj4j does not include a getGeoidHeight() function; we can get it from our
    // own code

    // private static double orthometricToWgs84(double lat, double lon, double orthmetricAlt)
    // {
    //     double geoidHeight = getGeoidHeight(lat,lon);
    //     double altWgs84 = orthmetricAlt + geoidHeight;

    //     System.out.println("Geoid offset is "+geoidHeight);

    //     return altWgs84;
    // }

    /**
     * Retrieves the altitude at a given coordinate using bilinear interpolation.
     */
    private static double getInterpolatedAltitude(Rasters rasters, double x, double y)
    {
        // Get the integer (pixel) coordinates of the four surrounding pixels
        int x1 = (int) Math.floor(x);
        int y1 = (int) Math.floor(y);
        int x2 = x1 + 1;
        int y2 = y1 + 1;

        // Check for out-of-bounds conditions
        if (x1 < 0 || y1 < 0 || x2 >= rasters.getWidth() || y2 >= rasters.getHeight()) {
            System.out.println("Interpolation out of raster bounds.");
            return Double.NaN;
        }

        // Get the altitudes at the four surrounding pixels
        Number[] aN;
        aN = rasters.getPixel(x1, y1); // Top-left q11
        double q11 = aN[0].doubleValue();
        aN = rasters.getPixel(x2, y1); // Top-right q21
        double q21 = aN[0].doubleValue();        
        aN = rasters.getPixel(x1, y2); // Bottom-left q12
        double q12 = aN[0].doubleValue();                
        aN = rasters.getPixel(x2, y2); // Bottom-right q22
        double q22 = aN[0].doubleValue();
        
        // Calculate the fractional distances between pixel centers
        double dx = x - x1;
        double dy = y - y1;

        // Perform bilinear interpolation
        double interpolatedAltitude = q11 * (1 - dx) * (1 - dy) +
            q21 * dx * (1 - dy) +
            q12 * (1 - dx) * dy +
            q22 * dx * dy;

        return interpolatedAltitude;
        
    } // getInterpolatedAltitude

    // Define bounding boxes for different regions of the continental US, Alaska, and Hawaii
    private static final double[][] USA_BOUNDING_BOXES = {
        // West Coast
        {32.0, -125.0, 49.0, -115.0}, // latMin, lonMin, latMax, lonMax

        // Southwest
        {25.0, -115.0, 37.0, -102.0},

        // Example bounding box for the Southwest (including Texas)
        {25.0, -115.0, 37.0, -93.0}, // Southwest (Texas, New Mexico, Arizona)

        // Midwest
        {36.0, -102.0, 49.0, -89.0},

        // Corrected Midwest bounding box to include Colorado
        {36.0, -109.0, 49.0, -102.0}, // Midwest (including Colorado)

        // Southeast
        {24.396308, -89.0, 36.5, -75.0},

        // Northeast
        {36.5, -89.0, 49.0, -66.93457},

        // Alaska (Including the Aleutian Islands)
        {51.2, -179.148909, 71.538800, -129.974167},

        // Hawaii
        {18.7763, -178.334698, 28.402123, -154.806773},

        // Puerto Rico
        {17.5, -67.5, 18.5, -65.0},

        // Updated Pacific Northwest bounding box to include all of Washington State and coastal islands
        {45.5, -125.0, 49.5, -116.5} // Covers Washington State, including San Juan Islands and Blaine

    };

    // Define bounding boxes for Europe, Iceland, and the British Isles
    private static final double[][] EUROPE_BOUNDING_BOXES = {
        // Western Europe (France, Benelux, Germany)
        {41.0, -5.0, 51.5, 10.5},

        // Southern Europe (Spain, Portugal, Italy, Greece)
        {35.0, -10.0, 45.0, 20.0},

        // Northern Europe (Scandinavia)
        {55.0, 5.0, 71.0, 30.0},

        // Eastern Europe (Poland, Czechia, Hungary, Romania)
        {45.0, 10.5, 56.0, 30.0},

        // Southeast Europe (Balkans)
        {39.0, 13.0, 48.0, 30.0},

        // Iceland
        {63.0, -25.0, 67.0, -13.0},

        // British Isles (United Kingdom, Ireland, and surrounding islands)
        {49.9, -14.0, 61.0, 2.0},

        // Bounding box for Greece including all major islands
        {34.0, 19.0, 42.0, 30.0}, // Covers mainland Greece and islands including Crete and Rhodes

        // Bounding box to fully encompass Cyprus
        {34.5, 32.0, 35.7, 34.0}, // Covers Cyprus and surrounding coastal areas

        // Bounding box to fully encompass the Low Countries (Netherlands, Belgium, Luxembourg)
        {49.4, 2.5, 53.7, 7.3}, // Covers Netherlands, Belgium, and Luxembourg

        // Bounding box to fully encompass the Baltic States
        {54.0, 20.0, 59.7, 28.2} // Covers Estonia, Latvia, and Lithuania
    };

    /**
     * Checks if a given latitude and longitude is within any of the bounding boxes.
     *
     * @param latitude  The latitude to check.
     * @param longitude The longitude to check.
     * @return True if the coordinate is within the US (continental, Alaska, Hawaii).
     */
    public static boolean isInUSA(double latitude, double longitude)
    {
        for (double[] box : USA_BOUNDING_BOXES) {
            double latMin = box[0];
            double lonMin = box[1];
            double latMax = box[2];
            double lonMax = box[3];
            if (latitude >= latMin && latitude <= latMax &&
                longitude >= lonMin && longitude <= lonMax) {
                return true;
            }
        }
        return false;
    }

    public static boolean isInEurope(double latitude, double longitude)
    {
        for (double[] box : EUROPE_BOUNDING_BOXES) {
            double latMin = box[0];
            double lonMin = box[1];
            double latMax = box[2];
            double lonMax = box[3];
            if (latitude >= latMin && latitude <= latMax &&
                longitude >= lonMin && longitude <= lonMax) {
                return true;
            }
        }
        return false;
    }
    
    public static void testEurope(Object[][] listOfCoordinates)
    {

        for (Object[] coords : listOfCoordinates) {
            boolean aBool = isInEurope((double)coords[0], (double)coords[1]);
            System.out.printf("Coordinates (%.4f, %.4f) (%s) are in Europe: %b%n",
                              (double)coords[0], (double)coords[1], (String)coords[2], aBool);
        }
    }
    
    public static void testUSA(Object[][] listOfCoordinates)
    {
        for (Object[] coords : listOfCoordinates) {            
            boolean aBool = isInUSA((double)coords[0], (double)coords[1]);
            System.out.printf("Coordinates (%.4f, %.4f) (%s) are in the US: %b%n",
                              (double)coords[0], (double)coords[1], (String)coords[2], aBool);
        }
    }

    public static Object[][] testUSACoordinates = {
        // Alabama
        {32.3770, -86.3000, "Montgomery, AL"},
        {33.5207, -86.8025, "Birmingham, AL"},
        {30.6954, -88.0399, "Mobile, AL"},

        // Alaska
        {58.3019, -134.4197, "Juneau, AK"},
        {61.2181, -149.9003, "Anchorage, AK"},
        {64.8378, -147.7164, "Fairbanks, AK"},

        // Arizona
        {33.4484, -112.0740, "Phoenix, AZ"},
        {32.2226, -110.9747, "Tucson, AZ"},
        {34.0489, -111.0937, "Prescott, AZ"},

        // California
        {38.5816, -121.4944, "Sacramento, CA"},
        {34.0522, -118.2437, "Los Angeles, CA"},
        {37.7749, -122.4194, "San Francisco, CA"},

        // Florida
        {30.4383, -84.2807, "Tallahassee, FL"},
        {25.7617, -80.1918, "Miami, FL"},
        {27.9944, -81.7603, "Lakeland, FL"},

        // Hawaii
        {21.3069, -157.8583, "Honolulu, HI"},
        {19.8968, -155.5828, "Hilo, HI"},
        {20.7984, -156.3319, "Lahaina, HI"},

        // New York
        {40.7128, -74.0060, "New York City, NY"},
        {42.6526, -73.7562, "Albany, NY"},
        {43.1610, -77.6109, "Rochester, NY"},

        // Texas
        {30.2672, -97.7431, "Austin, TX"},
        {32.7767, -96.7970, "Dallas, TX"},
        {29.7604, -95.3698, "Houston, TX"},

        // Washington
        {47.6062, -122.3321, "Seattle, WA"},
        {46.6021, -120.5059, "Yakima, WA"},
        {48.7519, -122.4787, "Bellingham, WA"},

        // Puerto Rico
        {18.4655, -66.1057, "San Juan, PR"},
        {18.3360, -65.6401, "Fajardo, PR"},
        {18.4261, -66.0614, "Bayamón, PR"},

        // Colorado
        {39.7392, -104.9903, "Denver, CO"},
        {38.8339, -104.8214, "Colorado Springs, CO"},
        {40.0150, -105.2705, "Boulder, CO"},

        // Georgia
        {33.7490, -84.3880, "Atlanta, GA"},
        {32.0809, -81.0912, "Savannah, GA"},
        {34.0736, -84.6770, "Kennesaw, GA"},

        // Illinois
        {39.7817, -89.6501, "Springfield, IL"},
        {41.8781, -87.6298, "Chicago, IL"},
        {40.6331, -89.3985, "Peoria, IL"},

        // Virginia
        {37.5407, -77.4360, "Richmond, VA"},
        {38.0293, -78.4767, "Charlottesville, VA"},
        {36.8529, -75.9780, "Virginia Beach, VA"},

        // Pennsylvania
        {40.2737, -76.8844, "Harrisburg, PA"},
        {39.9526, -75.1652, "Philadelphia, PA"},
        {40.4406, -79.9959, "Pittsburgh, PA"},

        // Massachusetts
        {42.3601, -71.0589, "Boston, MA"},
        {42.2626, -71.8023, "Worcester, MA"},
        {41.6362, -70.9342, "New Bedford, MA"},

        // Outliers (Additional True Coordinates)
        {24.396308, -81.6934, "Key West, FL (southernmost)"},
        {71.2906, -156.7886, "Utqiaġvik (Barrow), AK (northernmost)"},
        {32.7157, -117.1611, "San Diego, CA (southwest corner)"},
        {49.384358, -123.1216, "Blaine, WA (northern border)"},
        {44.4759, -73.2121, "Burlington, VT (northeast border)"}
    };

    public static Object[][] testEuropeCoordinates = {
        // Austria
        {48.2082, 16.3738, "Vienna, Austria"},
        {47.0707, 15.4395, "Graz, Austria"},
        {48.3069, 14.2858, "Linz, Austria"},

        // Belgium
        {50.8503, 4.3517, "Brussels, Belgium"},
        {51.2194, 4.4025, "Antwerp, Belgium"},
        {50.6326, 5.5797, "Liège, Belgium"},

        // Czech Republic
        {50.0755, 14.4378, "Prague, Czech Republic"},
        {49.1951, 16.6068, "Brno, Czech Republic"},
        {50.2116, 15.8328, "Hradec Králové, Czech Republic"},

        // Denmark
        {55.6761, 12.5683, "Copenhagen, Denmark"},
        {56.1629, 10.2039, "Aarhus, Denmark"},
        {55.4038, 10.4024, "Odense, Denmark"},

        // France
        {48.8566, 2.3522, "Paris, France"},
        {43.7102, 7.2620, "Nice, France"},
        {45.7640, 4.8357, "Lyon, France"},

        // Germany
        {52.5200, 13.4050, "Berlin, Germany"},
        {48.1351, 11.5820, "Munich, Germany"},
        {50.9375, 6.9603, "Cologne, Germany"},

        // Greece
        {37.9838, 23.7275, "Athens, Greece"},
        {40.6401, 22.9444, "Thessaloniki, Greece"},
        {39.6243, 19.9217, "Corfu, Greece"},

        // Hungary
        {47.4979, 19.0402, "Budapest, Hungary"},
        {46.2530, 20.1414, "Szeged, Hungary"},
        {47.1625, 19.0402, "Kecskemét, Hungary"},

        // Iceland
        {64.1355, -21.8954, "Reykjavik, Iceland"},
        {65.6835, -18.1106, "Akureyri, Iceland"},
        {63.8423, -22.4338, "Keflavik, Iceland"},

        // Ireland
        {53.3498, -6.2603, "Dublin, Ireland"},
        {51.8985, -8.4756, "Cork, Ireland"},
        {54.5973, -5.9301, "Belfast, Northern Ireland"},

        // Italy
        {41.9028, 12.4964, "Rome, Italy"},
        {45.4642, 9.1900, "Milan, Italy"},
        {43.7696, 11.2558, "Florence, Italy"},

        // Netherlands
        {52.3676, 4.9041, "Amsterdam, Netherlands"},
        {51.9225, 4.4792, "Rotterdam, Netherlands"},
        {52.0929, 5.1045, "Utrecht, Netherlands"},

        // Norway
        {59.9139, 10.7522, "Oslo, Norway"},
        {63.4305, 10.3951, "Trondheim, Norway"},
        {69.6492, 18.9553, "Tromsø, Norway"},

        // Poland
        {52.2297, 21.0122, "Warsaw, Poland"},
        {50.0647, 19.9450, "Kraków, Poland"},
        {51.1079, 17.0385, "Wrocław, Poland"},

        // Portugal
        {38.7169, -9.1399, "Lisbon, Portugal"},
        {41.1579, -8.6291, "Porto, Portugal"},
        {37.0179, -7.9308, "Faro, Portugal"},

        // Spain
        {40.4168, -3.7038, "Madrid, Spain"},
        {41.3851, 2.1734, "Barcelona, Spain"},
        {37.3891, -5.9845, "Seville, Spain"},

        // Sweden
        {59.3293, 18.0686, "Stockholm, Sweden"},
        {55.6050, 13.0038, "Malmö, Sweden"},
        {57.7089, 11.9746, "Gothenburg, Sweden"},

        // United Kingdom
        {51.5074, -0.1278, "London, UK"},
        {55.9533, -3.1883, "Edinburgh, Scotland"},
        {53.4808, -2.2426, "Manchester, UK"},

        // Switzerland
        {46.2044, 6.1432, "Geneva, Switzerland"},
        {47.3769, 8.5417, "Zurich, Switzerland"},
        {46.9481, 7.4474, "Bern, Switzerland"},

        // Estonia
        {59.4366, 24.7535, "Tallinn, Estonia"},       // Capital and largest city
        {58.3776, 26.7290, "Tartu, Estonia"},         // University city
        {58.9395, 23.5418, "Haapsalu, Estonia"},      // Coastal town

        // Latvia
        {56.9496, 24.1052, "Riga, Latvia"},           // Capital and largest city
        {56.5112, 21.0118, "Liepaja, Latvia"},        // Western coastal city
        {55.8750, 26.5362, "Daugavpils, Latvia"},     // Eastern Latvia

        // Lithuania
        {54.6872, 25.2797, "Vilnius, Lithuania"},     // Capital city
        {55.7033, 21.1443, "Klaipeda, Lithuania"},    // Baltic Sea port city
        {55.8980, 23.9036, "Šiauliai, Lithuania"},     // Northern Lithuania

        // Outliers (Additional True Coordinates)
        {69.6492, 18.9553, "Tromsø, Norway (northernmost)"},
        {35.8989, 14.5146, "Valletta, Malta (southernmost)"},
        {43.7384, 7.4246, "Monaco (tiny city-state)"},
        {55.6759, 12.5655, "Copenhagen, Denmark (eastern Europe)"},
        {48.5734, 7.7521, "Strasbourg, France (near German border)"},
        {60.1695, 24.9354, "Helsinki, Finland"}
    };


} // GeoTiffAltitudeLookup


// %ls -l DEM_LatLon_27.932627_-82.076305_28.067373_-81.923695.*
// 7553837 Mar 15 21:28 DEM_LatLon_27.932627_-82.076305_28.067373_-81.923695.3dep
// 1086727 Mar 15 21:25 DEM_LatLon_27.932627_-82.076305_28.067373_-81.923695.cop30
// 162003 Mar 15 21:18 DEM_LatLon_27.932627_-82.076305_28.067373_-81.923695.srtm

// %echo "-82.0 28.0" | gdaltransform -s_srs EPSG:4326 -t_srs EPSG:4269
// -82.0000072221613 28.000001666702 0

// lookup coordinates lat,lon 28.0, -82.0
// gdallocationinfo -geoloc -valonly DEMfile -82.0 28.0
// %gdallocationinfo -geoloc -valonly DEM_LatLon_27.932627_-82.076305_28.067373_-81.923695.3dep -82.0 28.0
// 43.2363090515137
// %gdallocationinfo -geoloc -valonly DEM_LatLon_27.932627_-82.076305_28.067373_-81.923695.3dep -82.0000072221613 28.000001666702 
// 43.2363090515137

// %gdallocationinfo -geoloc -valonly DEM_LatLon_27.932627_-82.076305_28.067373_-81.923695.cop30 -82.0 28.0
// 43.183479309082
// %gdallocationinfo -geoloc -valonly DEM_LatLon_27.932627_-82.076305_28.067373_-81.923695.srtm -82.0 28.0
// 46
// %java -cp "lib/*" GeoTiffAltitudeLookup.java DEM_LatLon_27.932627_-82.076305_28.067373_-81.923695.3dep 28.0 -82.0
// Altitude at (28.000000, -82.000000) is 43.160492 meters.
// interpolatedAltitude at (28.000000, -82.000000) is 43.160492 meters.
// %java -cp "lib/*" GeoTiffAltitudeLookup.java DEM_LatLon_27.932627_-82.076305_28.067373_-81.923695.cop30 28.0 -82.0
// Altitude at (28.000000, -82.000000) is 43.183479 meters.
// interpolatedAltitude at (28.000000, -82.000000) is 43.183479 meters.
// %java -cp "lib/*" GeoTiffAltitudeLookup.java DEM_LatLon_27.932627_-82.076305_28.067373_-81.923695.srtm 28.0 -82.0
// Altitude at (28.000000, -82.000000) is 46.000000 meters.
// interpolatedAltitude at (28.000000, -82.000000) is 46.000000 meters.

// 3dep with converted lat,lon to 3dep's EPSG:4269
// %java -cp "lib/*" GeoTiffAltitudeLookup.java DEM_LatLon_27.932627_-82.076305_28.067373_-81.923695.3dep 28.000001666702 -82.0000072221613
// Altitude at (28.000002, -82.000007) is 43.160492 meters.
// interpolatedAltitude at (28.000002, -82.000007) is 43.160492 meters.
    
// .cop30
// java -cp "lib/*" GeoTiffAltitudeLookup.java Paris.cop30 48.85661400 2.35222190
// Altitude at (48.856614, 2.352222) is 47.38 meters.
// interpolatedAltitude at (48.856614, 2.352222) is 47.38 meters.
// %gdallocationinfo -geoloc -valonly Paris.cop30 2.35222190 48.85661400
// 47.3755493164062

// EUDTM
// gdallocationinfo -geoloc -valonly Paris_EU_DTM_EPSG.tiff 2.35222190 48.85661400
// %echo "2.35222190 48.85661400" | gdaltransform -s_srs EPSG:4326 -t_srs EPSG:3035
// 3760773.6205571 2889486.18485711 0
// %gdallocationinfo -geoloc -valonly Paris_EU_DTM_EPSG.tiff 3760773.6205571 2889486.18485711
// 44.7000007629395
// java -cp "lib/*" GeoTiffAltitudeLookup.java Paris_EU_DTM_EPSG.tiff 48.85661400 2.35222190 
// 48.856614,2.3522219 is in USA? false
// 48.856614,2.3522219 is in Europe? true
// Altitude at (48.856614, 2.352222) is 44.700001 meters.
// interpolatedAltitude at (48.856614, 2.352222) is 44.700001 meters.

// DTED2
// %gdallocationinfo -geoloc -valonly s55_w069_1arc_v3.dt2 -68.0 -54.0
// 77
// %java -cp "lib/*" GeoTiffAltitudeLookup.java s55_w069_1arc_v3.dt2 -54.0 -68.0
// Altitude at (-54.000000, -68.000000) is 77.000000 meters.
