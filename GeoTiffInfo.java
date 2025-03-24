import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;

import mil.nga.tiff.Rasters;
import mil.nga.tiff.TIFFImage;
import mil.nga.tiff.TiffReader;
import mil.nga.tiff.TiffWriter;
import mil.nga.tiff.FileDirectory;
import mil.nga.tiff.FileDirectoryEntry;
import mil.nga.tiff.FieldType;
import mil.nga.tiff.util.TiffConstants;
import mil.nga.tiff.FieldTagType;

// for EU_DTM dataset from OT
//    Horizontal: ETRS89-extended / LAEA Europe [EPSG: 3035]
//    Vertical: EGM2008 [EPSG: 3855]
//    We need to convert the horizontal to WGS84

// for SRTM dataset from OT
//    Horizontal: WGS84 [EPSG: 4326]
//    Vertical: WGS84 (EGM GEOID)

// for USGS10m dataset from OT
//    Horizontal Coordinates: NAD83 [EPSG: 4269]
//    Vertical Coordinates: NAVD88 [EPSG: 5703], EGM GEOID
//    We need to convert the horizontal to WGS84

public class GeoTiffInfo
{
    static int GEOKEY_PIXELSCALE_TAG = 33550;
    static int GEOKEY_DIRECTORY_TAG = 34735;
    //static int VERTICAL_CRS_TAG = 4096;   
    //static int HORIZONTAL_CRS_TAG = 3072;
    static int PROJECTED_CRS_TAG = 3072;
    static int GEOGRAPHIC_CRS_TAG = 2048;
    static int VERTICAL_CRS_TAG = 4096;
    static int GEOKEY_ASCII_PARAMS_TAG = 34737;
    static int samplesPerPixel = 0;

    public static void main(String[] args)
    {
        if (args.length < 1) {
            System.out.println("Usage: GeoTiffInfo <inputFilePath>");
            return;
        }

        String inputFilePath = args[0];
        
        try {

            // Read the GeoTIFF file using TiffReader
            TIFFImage tiffImage = TiffReader.readTiff(new File(inputFilePath));

            // Get the rasters from the first image directory
            FileDirectory originalDirectory = tiffImage.getFileDirectories().get(0);
            Rasters rasters = originalDirectory.readRasters();

            if (rasters == null) {
                System.out.println("No raster data found in the GeoTIFF.");
                return;
            }

            System.out.println("Read GeoTIFF with dimensions: " 
                    + rasters.getWidth() + "x" + rasters.getHeight());

            // Extract pixel scale and model tie point from metadata
            double[] pixelScale = extractModelPixelScale(originalDirectory);
            double[] modelTiePoint = extractModelTiePoint(originalDirectory);

            System.out.println("Horizontal Datum: "+getHorizontalDatum(originalDirectory));
            //System.out.println("Horziontal Datum: "+extractHorizontalDatum(originalDirectory));
            System.out.println("Vertical Datum: "+getVerticalDatum(originalDirectory));
            System.out.println("Vertical Datum: "+extractVerticalDatum(originalDirectory));

            searchAllMetadata(tiffImage);

            String crs = extractCoordinateSystem(originalDirectory);
            System.out.println("Coordinate system is "+crs);

            // Define FieldType array (FLOAT to accommodate latitude, longitude, elevation)
            samplesPerPixel = rasters.getSamplesPerPixel();
            FieldType[] fieldTypes = new FieldType[samplesPerPixel];
            for (int i = 0; i < samplesPerPixel; i++) {
                fieldTypes[i] = FieldType.FLOAT; // Use FLOAT for coordinate and elevation data
            }

            System.out.println("Samples per pixel is "+samplesPerPixel);

            // inspect each pixel
            for (int y=0; y < rasters.getHeight(); y++) {

                for (int x=0; x < rasters.getWidth(); x++) {

                    Number[] sampleValues = rasters.getPixel(x,y);
                    double[] doubleValues = new double[sampleValues.length];
                    for (int i=0; i< sampleValues.length; i++) {
                        doubleValues[i] = sampleValues[i].doubleValue();
                    }

                    //System.out.println(x+","+y+": "+doubleValues[0]);
                }

            }


        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static double[] extractModelPixelScale(FileDirectory directory)
    {
        // use API to get pixel scale
        List<Double> pixelScaleList = directory.getModelPixelScale();

        if ((pixelScaleList == null) || (pixelScaleList.isEmpty())) {
            System.out.println("extractModelPixelScale: empty/null pixel scale list");
            return null;
        }

        double[] pixelScale = new double[3];
        pixelScale[0] = pixelScaleList.get(0); // x
        pixelScale[1] = pixelScaleList.get(1); // y
        pixelScale[2] = pixelScaleList.get(2); // z

        System.out.println("Pixel Scale is "+String.format("%.15f",pixelScale[0])+","+
                           String.format("%.15f",pixelScale[1])+","+
                           String.format("%.15f",pixelScale[2]));
        return pixelScale;
    }

    public static double[] extractModelTiePoint(FileDirectory directory)
    {
        double[] modelTiePoint = {0.0, 0.0, 0.0};

        List<Double>tiePointList =  directory.getModelTiepoint();

        if ((tiePointList == null) || (tiePointList.isEmpty())) {
            System.out.println("extractModelTiePoint: empty/null tie point list");
            return null;
        }

        double[] tiePoints = new double[6];

        tiePoints[0] = tiePointList.get(0);  // X raster space coordinates
        tiePoints[1] = tiePointList.get(1);  // Y
        tiePoints[2] = tiePointList.get(2);  // Z
        tiePoints[3] = tiePointList.get(3);  // x CRS x
        tiePoints[4] = tiePointList.get(4);  // y CRS y
        tiePoints[5] = tiePointList.get(5);  // z CRS z

        System.out.println("extractModelTiePoint: "+tiePoints[0]+","+tiePoints[1]+","+tiePoints[2]+" -> "+
                           tiePoints[3]+","+tiePoints[4]+","+tiePoints[5]);

        return tiePoints;
    }

    // EPSG:1 is orthometric height
    // EPSG:4326 is WGS 84 horizontal
    // EPSG:4979 (WGS 84 Ellipsoidal height)
    // EPSG:3035 is ETRS89 / LAEA Europe
    // horizontal coordinate system in LAEA Europe is ETRS89, often represented as EPSG:4258.
    // WGS 84 (EPSG:4326)
    // NAD83 (EPSG:4269)
    // ETRS89 (EPSG:4258)
    // Vertical Datum: NAVD88 (EPSG:5703).
         
    public static String extractCoordinateSystem(FileDirectory directory)
    {

        for (FileDirectoryEntry entry : directory.getEntries()) {
            
            if (entry.getFieldTag() == FieldTagType.getById(GeoTiffInfo.GEOKEY_ASCII_PARAMS_TAG)) {
                
                Object value = entry.getValues();
                
                if (value instanceof List) {
                    List<?> valuesList = (List<?>) value;
                
                    for (Object val : valuesList) {

                        System.out.println("Val class "+val.getClass());

                        if (val instanceof String) {
                            System.out.println("extractCoordinateSystem: string "+val);
                        }
                    }
                }
            }
        }
        return "a list of tags";
    }

    // public static int getHorizontalDatum(FileDirectory directory)
    // {
    //     FileDirectoryEntry fde = directory.get(FieldTagType.GeoKeyDirectory);
    //     ArrayList<Integer> geoKeys = (ArrayList<Integer>)fde.getValues();
    //     int numberOfKeys = geoKeys.get(3);
        
    //     for (int i = 0; i < numberOfKeys; i++) {
    //         int index = 4 + i * 4;
    //         int keyId = geoKeys.get(index);
    //         int tiffTagLocation = geoKeys.get(index + 1);
    //         int count = geoKeys.get(index + 2);
    //         int valueOffset = geoKeys.get(index + 3);

    //         if (keyId == 2048) { // GeographicTypeGeoKey
    //             if (tiffTagLocation == 0) {
    //                 // The valueOffset is the value of the key.
    //                 int value = valueOffset;
    //                 System.out.println("Horizontal datum:: "+value);
    //                 return value;
    //             } else {
    //                 // The valueOffset is an offset into the tag specified by tiffTagLocation.
    //                 // Read the value from the specified tag.
    //                 FileDirectoryEntry tagEntry = directory.get(FieldTagType.getById(tiffTagLocation));
    //                 if (tagEntry != null) {
    //                     Object tagValues = tagEntry.getValues();
    //                     if (tagValues instanceof ArrayList) {
    //                         ArrayList<Integer> tagArray = (ArrayList<Integer>) tagValues;
    //                         int arrayIndex = valueOffset / 2; // Convert byte offset to array index
    //                         if (arrayIndex < tagArray.size()) {
    //                             int tagValue = tagArray.get(arrayIndex);
    //                             System.out.println("Horizontal datum: "+tagValue);                                
    //                             return tagValue;
    //                         }
    //                     }
    //                 }
    //             }
    //         }
    //     }
    //     return 0;
    // }

    public static int getVerticalDatum(FileDirectory directory)
    {
        FileDirectoryEntry fde = directory.get(FieldTagType.GeoKeyDirectory);
        ArrayList<Integer> geoKeys = (ArrayList<Integer>)fde.getValues();
        int numberOfKeys = geoKeys.get(3);
        
        for (int i = 0; i < numberOfKeys; i++) {
            int index = 4 + i * 4;
            int keyId = geoKeys.get(index);
            int tiffTagLocation = geoKeys.get(index + 1);
            int count = geoKeys.get(index + 2);
            int valueOffset = geoKeys.get(index + 3);

            if (keyId == 4096) { // VERTICAL_CRS_TAG
                if (tiffTagLocation == 0) {
                    // The valueOffset is the value of the key.
                    int value = valueOffset;
                    System.out.println("getVerticalDatum:: "+value);
                    return value;
                } else {
                    // The valueOffset is an offset into the tag specified by tiffTagLocation.
                    // Read the value from the specified tag.
                    FileDirectoryEntry tagEntry = directory.get(FieldTagType.getById(tiffTagLocation));
                    if (tagEntry != null) {
                        Object tagValues = tagEntry.getValues();
                        if (tagValues instanceof ArrayList) {
                            ArrayList<Integer> tagArray = (ArrayList<Integer>)tagValues;
                            int arrayIndex = valueOffset / 2; // Convert byte offset to array index
                            if (arrayIndex < tagArray.size()) {
                                int tagValue = tagArray.get(arrayIndex);
                                System.out.println("Vertical datum: 4326");                                
                                return tagValue;
                            }
                        }
                    }
                }
            }
        }
        return 0;
    }


    /**
     * Extracts the Horizontal Datum (EPSG code) from a GeoTIFF FileDirectory.
     * Looks for GeographicTypeGeoKey (2048) or ProjectedCSTypeGeoKey (3072).
     * @param directory The FileDirectory to search.
     * @return The horizontal datum as a string or null if not found.
     */

    // Tag: 34736 | Type: ArrayList | Value: [298.257223563, 6378137.0]
    // these values ipmly WGS84 ellipsoid

    public static int getHorizontalDatum(FileDirectory directory) {
        final int GEOKEY_DIRECTORY_TAG = 34735;
        final int GEOGRAPHIC_CRS_TAG = 2048; // GeographicTypeGeoKey
        final int PROJECTED_CRS_TAG = 3072;  // ProjectedCSTypeGeoKey

        for (FileDirectoryEntry entry : directory.getEntries()) {
            if (entry.getFieldTag() == FieldTagType.getById(GEOKEY_DIRECTORY_TAG)) {
                Object value = entry.getValues();
                if (value instanceof List) {
                    List<?> valuesList = (List<?>) value;

                    // Iterate over the values to find GeographicType or ProjectedCSType keys
                    for (int i = 0; i < valuesList.size() - 3; i += 4) {
                        try {
                            int keyID = ((Number) valuesList.get(i)).intValue();
                            int tiffTagLocation = ((Number) valuesList.get(i + 1)).intValue();
                            int count = ((Number) valuesList.get(i + 2)).intValue();
                            int epsgCode = ((Number) valuesList.get(i + 3)).intValue();

                            if (keyID == GEOGRAPHIC_CRS_TAG || keyID == PROJECTED_CRS_TAG) {
                                //return "EPSG:" + epsgCode;
                                return epsgCode;
                            }
                        } catch (ClassCastException e) {
                            System.err.println("Error casting GeoKey values.");
                        }
                    }
                }
            }
        }
        return 0;
    }

    /**
     * Extracts the Vertical Datum (EPSG code) from a GeoTIFF FileDirectory.
     * Looks for VerticalCSTypeGeoKey (4096).
     * @param directory The FileDirectory to search.
     * @return The vertical datum as a string or null if not found.
     */
    public static int extractVerticalDatum(FileDirectory directory) {
        final int GEOKEY_DIRECTORY_TAG = 34735;
        final int VERTICAL_CRS_TAG = 4096; // VerticalCSTypeGeoKey

        for (FileDirectoryEntry entry : directory.getEntries()) {
            if (entry.getFieldTag() == FieldTagType.getById(GEOKEY_DIRECTORY_TAG)) {
                Object value = entry.getValues();
                if (value instanceof List) {
                    List<?> valuesList = (List<?>) value;

                    // Iterate over the values to find the VerticalCSTypeGeoKey
                    for (int i = 0; i < valuesList.size() - 3; i += 4) {
                        try {
                            int keyID = ((Number) valuesList.get(i)).intValue();
                            int tiffTagLocation = ((Number) valuesList.get(i + 1)).intValue();
                            int count = ((Number) valuesList.get(i + 2)).intValue();
                            int epsgCode = ((Number) valuesList.get(i + 3)).intValue();

                            // Check if the KeyID matches the VerticalCSTypeGeoKey
                            if (keyID == VERTICAL_CRS_TAG && epsgCode > 0) {
                                //return "EPSG:" + epsgCode;
                                return epsgCode;
                            }
                        } catch (ClassCastException e) {
                            System.err.println("Error casting GeoKey values for vertical datum.");
                        }
                    }
                }
            }
        }
        return 0;
    }

    public static int searchAllMetadata(TIFFImage tImage)
    {
        for (int i=0; i<tImage.getFileDirectories().size(); i++) {

            FileDirectory aDir = tImage.getFileDirectories().get(i);

            System.out.println("dump: verticalDatum is "+extractVerticalDatum(aDir));
            System.out.println("dump: horizontalDatum is "+getHorizontalDatum(aDir));

            for (FileDirectoryEntry entry: aDir.getEntries()) {
                FieldTagType tag = entry.getFieldTag();
                Object values = entry.getValues();
                System.out.println("Tag: "+tag.getId()+" | Type: "+ values.getClass().getSimpleName() + " | Value: "+values);

            }
            
        }
        return 0;
    }
    
} // GeoTiffInfo


