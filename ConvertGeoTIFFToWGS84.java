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

public class ConvertGeoTIFFToWGS84 {

    public static void main(String[] args)
    {
        if (args.length < 2) {
            System.out.println("Usage: ConvertGeoTIFFToWGS84 <inputFilePath> <outputFilePath>");
            return;
        }

        String inputFilePath = args[0];
        String outputFilePath = args[1];
        
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

            // Define FieldType array (FLOAT to accommodate latitude, longitude, elevation)
            int samplesPerPixel = rasters.getSamplesPerPixel();
            FieldType[] fieldTypes = new FieldType[samplesPerPixel];
            for (int i = 0; i < samplesPerPixel; i++) {
                fieldTypes[i] = FieldType.FLOAT; // Use FLOAT for coordinate and elevation data
            }

            System.out.println("Samples per pixel is "+samplesPerPixel);

            // Initialize Rasters object with FieldType array
            Rasters outputRasters = new Rasters(rasters.getWidth(), rasters.getHeight(), fieldTypes);

            // Perform manual conversion on each pixel
            for (int y = 0; y < rasters.getHeight(); y++) {
                for (int x = 0; x < rasters.getWidth(); x++) {
                    Number[] sampleValues = rasters.getPixel(x, y);

                    // Convert Number[] to double[]
                    double[] doubleValues = new double[sampleValues.length];
                    for (int i = 0; i < sampleValues.length; i++) {
                        doubleValues[i] = sampleValues[i].doubleValue();
                    }

                    // Calculate easting and northing using the extracted pixel scale and tie point
                    double easting = modelTiePoint[0] + x * pixelScale[0];
                    double northing = modelTiePoint[1] + y * pixelScale[1];

                    System.out.println("Going to convert "+easting+","+northing);

                    // Manually convert from NAD83/NAVD88 to WGS84
                    double[] latLonElev = convertNAD83NAVD88ToWGS84(easting, northing, doubleValues);

                    // Store the converted data into the Rasters object
                    //Number[] convertedValues = new Number[]{
                    //    latLonElev[0], // Latitude
                    //    latLonElev[1], // Longitude
                    //    latLonElev.length > 2 ? latLonElev[2] : 0 // Elevation if present
                    //};
                    //outputRasters.setPixel(x, y, convertedValues);

                    Number[] convertedValues = new Number[] {
                    };

                    System.out.println(x+","+y+":"+latLonElev[0]+","+latLonElev[1]);

                    outputRasters.setPixel(x,y,convertedValues);
                }
            }

            // Create a new FileDirectory with the modified rasters using an empty set of entries
            SortedSet<FileDirectoryEntry> entries = new TreeSet<>();
            FileDirectory outputDirectory = new FileDirectory(entries, outputRasters);
            
            // Create a new TiffImage with the modified FileDirectory
            TIFFImage outputTiff = new TIFFImage(outputDirectory);

            // Write the converted data to a new GeoTIFF file
            TiffWriter.writeTiff(new File(outputFilePath), outputTiff);

            System.out.println("Conversion completed successfully. Output saved to: " + outputFilePath);

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // public static int extractEPSGCode(FileDirectory directory) {
    //     try {
    //         Map<Integer, Object> fields = directory.getFields();
    //         for (Map.Entry<Integer, Object> entry : fields.entrySet()) {
    //             System.out.println("Tag: " + entry.getKey() + " -> Value: " + entry.getValue());
    //             if (entry.getKey() == 2048) { // 2048 is the GeoKeyDirectoryTag in GeoTIFF spec
    //                 return (int) entry.getValue();
    //             }
    //         }
    //     } catch (Exception e) {
    //         System.out.println("Error extracting EPSG code: " + e.getMessage());
    //     }
    //     return -1;
    // }

    public static double[] extractModelPixelScale(FileDirectory directory) {
        double[] pixelScale = {1.0, 1.0, 0.0}; // Default to 1.0 scale
        try {
            for (FileDirectoryEntry entry : directory.getEntries()) {
                // Check if the entry might be the ModelPixelScaleTag (33550)
                if (entry.getFieldType() == FieldType.DOUBLE && entry.getTypeCount() == 3) {
                    Object value = entry.getValues();
                    if (value instanceof double[]) {
                        pixelScale = (double[]) value;
                        System.out.println("Extracted Pixel Scale: " + pixelScale[0] + ", " + pixelScale[1]);
                    }
                }
            }
        } catch (Exception e) {
            System.out.println("Error extracting Pixel Scale: " + e.getMessage());
        }
        return pixelScale;
    }

    public static double[] extractModelTiePoint(FileDirectory directory) {
        double[] modelTiePoint = {0.0, 0.0, 0.0};
        try {
            for (FileDirectoryEntry entry : directory.getEntries()) {
                // Heuristic to identify the ModelTiePointTag (33922)
                if (entry.getFieldType() == FieldType.DOUBLE && entry.getTypeCount() >= 6) {
                    Object value = entry.getValues();
                    if (value instanceof double[]) {
                        modelTiePoint = (double[]) value;
                        System.out.println("Extracted Model Tie Point: " + modelTiePoint[0] + ", " + modelTiePoint[1]);
                    }
                }
            }
        } catch (Exception e) {
            System.out.println("Error extracting Model Tie Point: " + e.getMessage());
        }
        return modelTiePoint;
    }

    public static double[] convertNAD83NAVD88ToWGS84(double easting, double northing, double[] sampleValues) {
        double dX = 1.0;
        double dY = -1.0;
        double dZ = -1.0;

        double lat = northing + dY * 1e-6;
        double lon = easting + dX * 1e-6;

        double elevation = sampleValues.length > 2 ? sampleValues[2] + dZ : Double.NaN;

        return new double[]{lat, lon, elevation};
    }
}
