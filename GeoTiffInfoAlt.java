// GeoTiffInfoAlt.java
// use proj4j libs to examine a geotiff file

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

// javac -cp "lib/*" GeoTiffInfoAlt.java filename
// java -cp ".:lib/*" GeoTiffInfoAlt filename

public class GeoTiffInfoAlt
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
        if (args.length < 1) {
            System.out.println("Usage: java GeoTiffAltitudeLookup <GeoTIFF file>");
            return;
        }

        String inputFilePath = args[0];

        long t0, t1;
        
        t0 = System.nanoTime();                            
        if (inputFilePath.endsWith(".dt2")) {
            readDted(inputFilePath);
        }
        else {
            readGeoTiff(inputFilePath);
        }
        t1 = System.nanoTime();
        System.out.println("GeoTiff took " + ((t1 - t0)/1_000_000) + " ms");                
    }

    private static void readDted(String inputFilePath)
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

        }
        catch (Exception e) {
            e.printStackTrace();
        }
        
        return;
    }

    private static void readGeoTiff(String inputFilePath)
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
        
    private static String extractVerticalDatum(List<Integer> geoKeyDirectory)
    {
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
                    System.out.println("Default vertical epsg code "+epsgCode);
                    return new String("EPSG:" + epsgCode);
                }
            }
        }

        System.out.println("No Vertical Datum Tag");
        
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

} // GeoTiffInfoAlt

