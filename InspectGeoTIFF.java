// InspectGeoTIFF.java
// use Proj4j and nga.mil libs to
// monkey with GeoTIFF DEMs

import java.io.File;
import java.io.IOException;
import java.util.List;

import mil.nga.tiff.TIFFImage;
import mil.nga.tiff.TiffReader;
import mil.nga.tiff.FileDirectory;
import mil.nga.tiff.FileDirectoryEntry;
import mil.nga.tiff.FieldTagType;

import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateReferenceSystem;

public class InspectGeoTIFF {

    public static void main(String[] args) {
        if (args.length < 1) {
            System.out.println("Usage: java InspectGeoTIFF <inputFilePath>");
            return;
        }

        String inputFilePath = args[0];

        try {
            // Read the GeoTIFF file
            TIFFImage tiffImage = TiffReader.readTiff(new File(inputFilePath));

            // Iterate through file directories (for multi-page TIFFs)
            for (int i = 0; i < tiffImage.getFileDirectories().size(); i++) {
                FileDirectory directory = tiffImage.getFileDirectories().get(i);

                System.out.println("\n--- GeoTIFF Image " + (i + 1) + " ---");
                System.out.println("Image Width: " + directory.getImageWidth());
                System.out.println("Image Height: " + directory.getImageHeight());

                // Extract ModelPixelScaleTag (33550)
                List<Double> modelPixelScale = getTagValues(directory, 33550);
                if (modelPixelScale != null) {
                    System.out.println("Model Pixel Scale: " + modelPixelScale);
                }

                // Extract ModelTiepointTag (33922)
                List<Double> modelTiepoints = getTagValues(directory, 33922);
                if (modelTiepoints != null) {
                    System.out.println("Model Tie Points: " + modelTiepoints);
                }

                // Extract GeoKeyDirectoryTag (34735)
                List<Integer> geoKeyDirectory = getTagValues(directory, 34735);
                if (geoKeyDirectory != null) {
                    System.out.println("GeoKey Directory: " + geoKeyDirectory);

                    // Determine CRS using the GeoKeyDirectoryTag
                    String horizontalDatum = extractHorizontalDatum(geoKeyDirectory);
                    if (horizontalDatum != null) {
                        System.out.println("Horizontal Datum (EPSG): " + horizontalDatum);
                        describeCRS(horizontalDatum);
                    } else {
                        System.out.println("Horizontal Datum: Not found");
                    }

                    String verticalDatum = extractVerticalDatum(geoKeyDirectory);
                    System.out.println("Vertical Datum: " + (verticalDatum != null ? verticalDatum : "Not found"));
                }
            }

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Extracts tag values from the FileDirectory as a List of Numbers.
     */
    private static <T> List<T> getTagValues(FileDirectory directory, int tag) {
        for (FileDirectoryEntry entry : directory.getEntries()) {
            if (entry.getFieldTag() == FieldTagType.getById(tag)) {
                Object values = entry.getValues();
                if (values instanceof List<?>) {
                    try {
                        return (List<T>) values;
                    } catch (ClassCastException e) {
                        System.err.println("Warning: Incorrect data type for tag " + tag);
                    }
                }
            }
        }
        return null;
    }

    /**
     * Extracts the Horizontal Datum (EPSG code) from the GeoKeyDirectoryTag.
     */
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

    /**
     * Extracts the Vertical Datum (EPSG code) from the GeoKeyDirectoryTag.
     */
    private static String extractVerticalDatum(List<Integer> geoKeyDirectory) {
        final int VERTICAL_CRS_TAG = 4096; // VerticalCSTypeGeoKey

        for (int i = 0; i < geoKeyDirectory.size() - 3; i += 4) {
            int keyID = geoKeyDirectory.get(i);
            int epsgCode = geoKeyDirectory.get(i + 3);
            if (keyID == VERTICAL_CRS_TAG) {
                return "EPSG:" + epsgCode;
            }
        }
        return null;
    }

    /**
     * Uses PROJ4J to describe a Coordinate Reference System (CRS).
     */
    private static void describeCRS(String epsgCode) {
        try {
            CRSFactory crsFactory = new CRSFactory();
            CoordinateReferenceSystem crs = crsFactory.createFromName(epsgCode);
            System.out.println("CRS Name: " + crs.getName());
            System.out.println("CRS Projection: " + crs.getProjection().getName());
            System.out.println("CRS Parameters: " + crs.getProjection().getPROJ4Description());
        } catch (Exception e) {
            System.out.println("Error describing CRS: " + e.getMessage());
        }
    }
}
