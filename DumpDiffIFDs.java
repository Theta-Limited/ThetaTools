import mil.nga.tiff.*;
import java.io.File;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * DumpDiffIFDs
 *  - Lists all IFDs (including subIFDs) in a TIFF/GeoTIFF.
 *  - Prints dimensions, quick georef presence, and every tag id with a brief value preview.
 *
 * Usage:
 *   javac -cp "lib/*" DumpDiffIFDs.java
 *   java  -cp ".:lib/*" DumpDiffIFDs your.tif
 */
public class DumpDiffIFDs {

  // Relevant GeoTIFF tag IDs
  static final int TAG_ModelPixelScale     = 33550;
  static final int TAG_ModelTiepoint       = 33922;
  static final int TAG_ModelTransformation = 34264;
  static final int TAG_GeoKeyDirectory     = 34735;
  static final int TAG_GDAL_NODATA         = 42113;
  static final int TAG_GDAL_METADATA       = 42112; // optional XML with <GeoTransform>...

  public static void main(String[] args) throws Exception {
    if (args.length < 1) {
      System.err.println("Usage: java -cp .:lib/* DumpDiffIFDs <file.tif>");
      System.exit(1);
    }
    var img = TiffReader.readTiff(new File(args[0]));
    List<FileDirectory> dirs = collectAllDirectories(img);

    int idx = 0;
    for (FileDirectory d : dirs) {
      int w = toInt(d.getImageWidth());
      int h = toInt(invokeNumberGetterFallback(d, "getImageHeight", "getImageLength"));
      System.out.printf("IFD #%d: %dx%d%n", idx++, w, h);

      // quick georef presence
      boolean hasScaleTie = (findEntryByTag(d, TAG_ModelPixelScale) != null) &&
                            (findEntryByTag(d, TAG_ModelTiepoint)  != null);
      boolean hasMT  = findEntryByTag(d, TAG_ModelTransformation) != null;
      boolean hasGKD = findEntryByTag(d, TAG_GeoKeyDirectory) != null;
      boolean hasGMD = findEntryByTag(d, TAG_GDAL_METADATA) != null;

      System.out.printf("  Georef: %s%s%s%s%n",
        hasScaleTie ? "Scale+Tiepoint " : "",
        hasMT ? "ModelTransformation " : "",
        hasGKD ? "GeoKeyDirectory " : "",
        hasGMD ? "GDAL_METADATA " : (hasScaleTie||hasMT||hasGKD ? "" : "none"));

      // list all entries (id + short preview)
      Set<FileDirectoryEntry> entries = d.getEntries();
      if (entries == null || entries.isEmpty()) {
        System.out.println("  <no entries exposed by this library>");
      } else {
        for (FileDirectoryEntry e : entries) {
          Integer tagId = getTagId(e);
          Object v = e.getValues();
          String preview = previewValue(v);
          System.out.printf("    tag %s -> %s%n", tagId == null ? "?" : tagId.toString(), preview);

          // small bonus: if this is GDAL_METADATA or ImageDescription, try to show a <GeoTransform> line if present
          if (tagId != null && (tagId == TAG_GDAL_METADATA || tagId == 270 /*ImageDescription*/)) {
            String s = toAscii(v);
            if (s != null) {
              String gt = extractGeoTransformLine(s);
              if (gt != null) System.out.println("      GeoTransform: " + gt);
            }
          }
        }
      }
      System.out.println();
    }
  }

  /* ----------------- helpers ----------------- */

  static List<FileDirectory> collectAllDirectories(Object tiffImage) {
    List<FileDirectory> out = new ArrayList<>();
    try {
      Method mRoot = tiffImage.getClass().getMethod("getFileDirectory");
      Object root = mRoot.invoke(tiffImage);
      if (root instanceof FileDirectory) out.add((FileDirectory) root);
    } catch (Exception ignore) {}

    try {
      Method mList = tiffImage.getClass().getMethod("getFileDirectories");
      Object v = mList.invoke(tiffImage);
      if (v instanceof Collection<?>)
        for (Object o : (Collection<?>) v)
          if (o instanceof FileDirectory) out.add((FileDirectory) o);
    } catch (Exception ignore) {}

    // recurse into subIFDs if API exposes them
    Set<FileDirectory> seen = new HashSet<>(out);
    Deque<FileDirectory> stack = new ArrayDeque<>(out);
    while (!stack.isEmpty()) {
      FileDirectory d = stack.pop();
      for (FileDirectory sub : readSubIFDs(d)) {
        if (seen.add(sub)) { out.add(sub); stack.push(sub); }
      }
    }
    return out;
  }

  static List<FileDirectory> readSubIFDs(FileDirectory d) {
    List<FileDirectory> out = new ArrayList<>();
    for (String name : new String[]{"getSubIFDs","getSubFileDirectories","getSubDirectories"}) {
      try {
        Method m = d.getClass().getMethod(name);
        Object v = m.invoke(d);
        if (v instanceof Collection<?>)
          for (Object o : (Collection<?>) v)
            if (o instanceof FileDirectory) out.add((FileDirectory) o);
      } catch (Exception ignore) {}
    }
    return out;
  }

  static Integer getTagId(FileDirectoryEntry e) {
    try {
      // int getTag()
      try {
        var m = e.getClass().getMethod("getTag");
        Object v = m.invoke(e);
        if (v instanceof Number) return ((Number) v).intValue();
        if (v != null) {
          try { var mm=v.getClass().getMethod("getId");    Object id=mm.invoke(v); if (id instanceof Number) return ((Number) id).intValue(); } catch (NoSuchMethodException ignore) {}
          try { var mm=v.getClass().getMethod("getValue"); Object id=mm.invoke(v); if (id instanceof Number) return ((Number) id).intValue(); } catch (NoSuchMethodException ignore) {}
          try { var mm=v.getClass().getMethod("ordinal");  Object id=mm.invoke(v); if (id instanceof Number) return ((Number) id).intValue(); } catch (NoSuchMethodException ignore) {}
        }
      } catch (NoSuchMethodException ignore) {}
      // int getTagId()
      try {
        var m = e.getClass().getMethod("getTagId");
        Object v = m.invoke(e);
        if (v instanceof Number) return ((Number) v).intValue();
      } catch (NoSuchMethodException ignore) {}
    } catch (Exception ignore) {}
    return null;
  }

  static FileDirectoryEntry findEntryByTag(FileDirectory d, int tag) {
    Set<FileDirectoryEntry> entries = d.getEntries();
    if (entries == null) return null;
    for (FileDirectoryEntry e : entries) {
      Integer id = getTagId(e);
      if (id != null && id == tag) return e;
    }
    return null;
  }

  static int toInt(Object n) {
    if (n instanceof Number) return ((Number) n).intValue();
    throw new IllegalStateException("Expected Number, got " + (n == null ? "null" : n.getClass()));
  }

  static Number invokeNumberGetterFallback(Object target, String primary, String secondary) throws Exception {
    try { return (Number) target.getClass().getMethod(primary).invoke(target); }
    catch (NoSuchMethodException e) { return (Number) target.getClass().getMethod(secondary).invoke(target); }
  }

  static String toAscii(Object v) {
    if (v == null) return null;
    if (v instanceof byte[]) return new String((byte[]) v, StandardCharsets.UTF_8);
    if (v instanceof char[]) return new String((char[]) v);
    String s = v.toString();
    return s;
  }

  static String previewValue(Object v) {
    if (v == null) return "null";
    if (v instanceof byte[])  return "byte[" + ((byte[]) v).length + "]";
    if (v instanceof short[]) return "short[" + ((short[]) v).length + "]";
    if (v instanceof char[])  return "char[" + ((char[]) v).length + "]";
    if (v instanceof int[])   return "int["  + ((int[]) v).length  + "]";
    if (v instanceof long[])  return "long[" + ((long[]) v).length + "]";
    if (v instanceof float[]) return "float[" + ((float[]) v).length + "]";
    if (v instanceof double[])return "double[" + ((double[]) v).length + "]";
    if (v.getClass().isArray()) return v.getClass().getSimpleName();
    String s = v.toString();
    return s.length() > 80 ? s.substring(0,77) + "..." : s;
  }

  /** Pulls out a single-line GeoTransform payload if present in a metadata string (XML/WKT/JSON blobs). */
  static String extractGeoTransformLine(String s) {
    if (s == null) return null;
    int i0 = s.indexOf("<GeoTransform>");
    int i1 = s.indexOf("</GeoTransform>");
    if (i0 >= 0 && i1 > i0) {
      String body = s.substring(i0 + "<GeoTransform>".length(), i1).trim();
      body = body.replaceAll("\\s+", " ");
      return body;
    }
    return null;
  }
}
