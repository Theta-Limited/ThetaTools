// ReadMaxarDtm.java
// Bobby Krupczak
// with ChatGPT help
// javac -cp "lib/*" MaxarDtmReader.java
// java -cp ".:lib/*" MaxarDtmReader 

/* MaxarDtmReader.java – robust to mil.nga.tiff variants without relying on numeric tag IDs */

// todo: add tweak to make coordinates match precisely

import mil.nga.tiff.FileDirectory;
import mil.nga.tiff.FileDirectoryEntry;
import mil.nga.tiff.TiffReader;
import mil.nga.tiff.Rasters;

import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateReferenceSystem;
import org.locationtech.proj4j.CoordinateTransform;
import org.locationtech.proj4j.CoordinateTransformFactory;
import org.locationtech.proj4j.ProjCoordinate;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MaxarDtmReader implements AutoCloseable {

    // GeoKey IDs (we parse content; we don't rely on tag IDs)
    private static final int KEY_GeographicTypeGeoKey  = 2048;
    private static final int KEY_ProjectedCSTypeGeoKey = 3072;

    private final FileDirectory dir;
    private final Rasters rasters;

    private final int width, height;

    // Affine: x = a0 + a1*col + a2*row; y = b0 + b1*col + b2*row
    private double a0, a1, a2, b0, b1, b2;
    private double inv00, inv01, inv10, inv11;

    private boolean georeferenced;
    private Double noData;

    private String dataEpsg;
    private CoordinateReferenceSystem dataCRS, wgs84;
    private CoordinateTransform dataToWgs, wgsToData;

    private final Method mGetPixelSampleDouble; // (x,y,band)->double
    private final Method mGetFirstPixelSample;  // (x,y)->double
    private final Method mGetPixelSample; // (x,y,band)->Number
    private final Method mGetPixel;       // (x,y)->Object (e.g., Number[] or array)

    private String horizontalCRS; // e.g., "EPSG:32616"
    private String verticalCRS;   // e.g., "ELLIPSOID (WGS84 G1674)" or "EPSG:5703"

    private final CRSFactory crsFactory = new CRSFactory();
    private final CoordinateTransformFactory ctf = new CoordinateTransformFactory();
    // Tiny cache so we don’t rebuild transforms for repeated calls
    private final Map<String, CoordinateTransform> reprojCache = new java.util.HashMap<>();
    
    // corners of the bounding box calculated from the GeoTiff or DTED, not
    // from the filename 
    private double n,s,e,w;
    
    public MaxarDtmReader(File tiffFile) throws Exception
    {
        var tiff = TiffReader.readTiff(tiffFile);

        // Gather all IFDs (root + subIFDs if exposed)
        List<FileDirectory> all = collectAllDirectories(tiff);

        // Debug summary (content-signature based)
        System.err.println("[MaxarDtmReader] IFD summary:");
        for (FileDirectory d : all) {
            try {
                int w = toInt(d.getImageWidth());
                int h = toInt(invokeNumberGetterFallback(d, "getImageHeight", "getImageLength"));
                boolean has = hasAnyGeorefSignature(d);
                System.err.printf("  - %dx%d  georef: %s%n", w, h, has ? "present" : "none");
            } catch (Exception ex) {
                System.err.println("  - <error summarizing IFD>");
            }
        }

        // Pick best: prefer georef; else largest area
        FileDirectory picked = null;
        long bestArea = -1;
        boolean bestHas = false;
        for (FileDirectory d : all) {
            int w = toInt(d.getImageWidth());
            int h = toInt(invokeNumberGetterFallback(d, "getImageHeight", "getImageLength"));
            long area = (long) w * (long) h;
            boolean has = hasAnyGeorefSignature(d);
            if (picked == null || (has && !bestHas) || (has == bestHas && area > bestArea)) {
                picked = d; bestArea = area; bestHas = has;
            }
        }
        if (picked == null) picked = tiff.getFileDirectory();
        this.dir = picked;

        this.width  = toInt(dir.getImageWidth());
        this.height = toInt(invokeNumberGetterFallback(dir, "getImageHeight", "getImageLength"));

        // Build affine: getters → signatures → GDAL_METADATA
        AffineParams ap = tryBuildAffineRobust(dir);
        if (ap != null) {
            setAffine(ap.a0, ap.a1, ap.a2, ap.b0, ap.b1, ap.b2);
            this.georeferenced = true;
        } else {
            setAffine(0,1,0, 0,0,-1);
            this.georeferenced = false;
            System.err.println("[MaxarDtmReader] No georeferencing found; operating in pixel space.");
        }

        this.noData = tryReadNoDataRobust(dir);
        this.rasters = dir.readRasters();

        // Horizontal & vertical CRS/datum detection
        this.horizontalCRS = determineHorizontalCRS(dir);
        this.verticalCRS   = determineVerticalCRS(dir);

        // Raster access methods
        Method g1 = null, g2 = null, g3 = null, g4 = null;
        for (Method m : Rasters.class.getMethods()) {
            if (m.getName().equals("getPixelSampleDouble") && m.getParameterCount() == 3) g1 = m;
            if (m.getName().equals("getFirstPixelSample")  && m.getParameterCount() == 2) g2 = m;
            if (m.getName().equals("getPixelSample")       && m.getParameterCount() == 3) g3 = m; // returns Number
            if (m.getName().equals("getPixel")             && m.getParameterCount() == 2) g4 = m; // returns pixel samples            
        }
        this.mGetPixelSampleDouble = g1;
        this.mGetFirstPixelSample  = g2;
        this.mGetPixelSample       = g3;
        this.mGetPixel             = g4;
        // CRS (EPSG)
        // if (georeferenced) {
        //     this.dataEpsg = detectEpsgRobust(dir);
        //     if (this.dataEpsg == null) this.dataEpsg = "EPSG:4326";
        //     enableCrs(this.dataEpsg);
        // }

        if (georeferenced) {
            // Prefer the horizontal CRS we parsed earlier
            this.dataEpsg = (this.horizontalCRS != null) ? this.horizontalCRS : detectEpsgRobust(dir);
            if (this.dataEpsg != null) {
                enableCrs(this.dataEpsg);
            } else {
                System.err.println("[MaxarDtmReader] Warning: horiz CRS not found; will initialize transforms lazily on first query.");
            }
        }

        // determine corners
        
    }

    /* =================== Public API =================== */
    
    public String getHorizontalCRS() { return horizontalCRS; }
    public String getVerticalCRS()   { return verticalCRS;   }
    public int getWidth()  { return width; }
    public int getHeight() { return height; }
    public boolean isGeoreferenced() { return georeferenced; }
    public String getDataEpsg() { return dataEpsg; }
    public Optional<Double> getNoData() { return Optional.ofNullable(noData); }

    public Bounds getBoundsDataCRS() {
        double[] p00 = worldFromPixel(0, 0);
        double[] p10 = worldFromPixel(width, 0);
        double[] p01 = worldFromPixel(0, height);
        double[] p11 = worldFromPixel(width, height);
        double minX = Math.min(Math.min(p00[0], p10[0]), Math.min(p01[0], p11[0]));
        double maxX = Math.max(Math.max(p00[0], p10[0]), Math.max(p01[0], p11[0]));
        double minY = Math.min(Math.min(p00[1], p10[1]), Math.min(p01[1], p11[1]));
        double maxY = Math.max(Math.max(p00[1], p10[1]), Math.max(p01[1], p11[1]));
        return new Bounds(minX, minY, maxX, maxY);
    }

    public Bounds getBoundsWGS84() {
        requireGeoref("Bounds in WGS84");
        Bounds b = getBoundsDataCRS();
        ProjCoordinate p1 = transform(dataToWgs, b.minX, b.minY);
        ProjCoordinate p2 = transform(dataToWgs, b.minX, b.maxY);
        ProjCoordinate p3 = transform(dataToWgs, b.maxX, b.minY);
        ProjCoordinate p4 = transform(dataToWgs, b.maxX, b.maxY);
        double minLon = Math.min(Math.min(p1.x, p2.x), Math.min(p3.x, p4.x));
        double maxLon = Math.max(Math.max(p1.x, p2.x), Math.max(p3.x, p4.x));
        double minLat = Math.min(Math.min(p1.y, p2.y), Math.min(p3.y, p4.y));
        double maxLat = Math.max(Math.max(p1.y, p2.y), Math.max(p3.y, p4.y));
        return new Bounds(minLon, minLat, maxLon, maxLat);
    }

    public Optional<Double> getElevationProjectedBilinear(double x, double y) {
        double[] rc = pixelFromWorld(x, y);
        double col = rc[0], row = rc[1];
        if (col < 0 || row < 0 || col > (width-1) || row > (height-1)) return Optional.empty();

        int c0 = (int)Math.floor(col), r0 = (int)Math.floor(row);
        int c1 = Math.min(c0+1, width-1), r1 = Math.min(r0+1, height-1);
        double dc = col - c0, dr = row - r0;

        Double z00 = sample(c0, r0), z10 = sample(c1, r0), z01 = sample(c0, r1), z11 = sample(c1, r1);
        if (z00==null || z10==null || z01==null || z11==null) return Optional.empty();

        double z0 = z00*(1-dc) + z10*dc;
        double z1 = z01*(1-dc) + z11*dc;
        return Optional.of(z0*(1-dr) + z1*dr);
    }

    // public Optional<Double> getElevationAtLatLon(double lat, double lon)
    // {
    //     requireGeoref("Elevation at lon/lat");
    //     ProjCoordinate p = transform(wgsToData, lon, lat);
    //     return getElevationProjectedBilinear(p.x, p.y);
    // }

    /**
     * Elevation query for inputs in WGS84 (lat, lon) decimal degrees.
     * Reprojects to the DTM's native CRS (whatever it is) before sampling.
     */
    public Optional<Double> getElevationAtLatLon(double latDeg, double lonDeg) {
        requireGeoref("Elevation (lat,lon in WGS84)");

        // Ensure we know/enable the data CRS
        if (this.dataCRS == null) {
            // Try, in order: previously detected EPSG, parsed horizontalCRS, or parse now
            String epsg = (this.dataEpsg != null) ? this.dataEpsg
                : (this.horizontalCRS != null ? this.horizontalCRS : determineHorizontalCRS(this.dir));
            if (epsg == null || epsg.isBlank()) {
                throw new IllegalStateException("Data CRS unknown; cannot reproject from WGS84.");
            }
            enableCrs(epsg); // sets dataCRS, wgs84, dataToWgs, wgsToData, dataEpsg
        }

        // Ensure WGS84 and the transform WGS84→data exist (or refresh if needed)
        if (this.wgs84 == null) {
            this.wgs84 = new CRSFactory().createFromName("EPSG:4326");
        }
        if (this.wgsToData == null) {
            this.wgsToData = new CoordinateTransformFactory().createTransform(this.wgs84, this.dataCRS);
        }

        // proj4j expects (lon, lat) when transforming geographic coords
        ProjCoordinate p = transform(this.wgsToData, lonDeg, latDeg);
        return getElevationProjectedBilinear(p.x, p.y);
    }
    
    @Override public void close() {}

    /* =================== Internals =================== */

    private void setAffine(double A0,double A1,double A2,double B0,double B1,double B2) {
        this.a0=A0; this.a1=A1; this.a2=A2; this.b0=B0; this.b1=B1; this.b2=B2;
        double det = a1*b2 - a2*b1;
        if (Math.abs(det) < 1e-12) throw new IllegalStateException("Non-invertible geotransform (det≈0)");
        this.inv00 =  b2/det; this.inv01 = -a2/det;
        this.inv10 = -b1/det; this.inv11 =  a1/det;
    }

    private void enableCrs(String epsg) {
        CRSFactory cf = new CRSFactory();
        this.dataCRS = cf.createFromName(epsg);
        this.wgs84   = cf.createFromName("EPSG:4326");
        CoordinateTransformFactory ctf = new CoordinateTransformFactory();
        this.dataToWgs = ctf.createTransform(dataCRS, wgs84);
        this.wgsToData = ctf.createTransform(wgs84, dataCRS);
        this.dataEpsg = epsg;
    }

    private void requireGeoref(String op) {
        if (!georeferenced) throw new IllegalStateException(op + " requires georeferencing.");
    }

    private static int toInt(Object n) {
        if (n instanceof Number) return ((Number)n).intValue();
        throw new IllegalStateException("Expected Number, got " + (n==null?"null":n.getClass()));
    }

    private static Number invokeNumberGetterFallback(Object target, String primary, String secondary) throws Exception {
        try { return (Number) target.getClass().getMethod(primary).invoke(target); }
        catch (NoSuchMethodException e) { return (Number) target.getClass().getMethod(secondary).invoke(target); }
    }

    private static List<FileDirectory> collectAllDirectories(Object tiffImage) {
        List<FileDirectory> out = new ArrayList<>();
        try { var m=tiffImage.getClass().getMethod("getFileDirectory"); Object v=m.invoke(tiffImage); if (v instanceof FileDirectory) out.add((FileDirectory)v); } catch (Exception ignore) {}
        try { var m=tiffImage.getClass().getMethod("getFileDirectories"); Object v=m.invoke(tiffImage);
              if (v instanceof Collection<?>) for (Object o:(Collection<?>)v) if (o instanceof FileDirectory) out.add((FileDirectory)o);
        } catch (Exception ignore) {}
        // subIFDs (best-effort)
        Set<FileDirectory> seen = new HashSet<>(out);
        Deque<FileDirectory> st = new ArrayDeque<>(out);
        while (!st.isEmpty()) {
            FileDirectory d = st.pop();
            for (String name : new String[]{"getSubIFDs","getSubFileDirectories","getSubDirectories"}) {
                try {
                    var m = d.getClass().getMethod(name);
                    Object v = m.invoke(d);
                    if (v instanceof Collection<?>)
                        for (Object o : (Collection<?>) v)
                            if (o instanceof FileDirectory && seen.add((FileDirectory)o)) { out.add((FileDirectory)o); st.push((FileDirectory)o); }
                } catch (Exception ignore) {}
            }
        }
        return out;
    }

    /* ===== Robust georef detection (handles List<?> etc.) ===== */

    private static boolean hasAnyGeorefSignature(FileDirectory d) {
        if (tryGetModelPixelScale(d) != null && tryGetModelTiepoint(d) != null) return true;
        if (tryGetModelTransformation(d) != null) return true;
        if (findDouble16Matrix(d) != null) return true;
        if (extractGeoTransformFromGdalMetadata(d) != null) return true;
        return false;
    }

    // small helper near your other utils
    private static boolean near(double v, double tgt) { return Math.abs(v - tgt) < 1e-9; }

    /**
     * Build the north-up affine transform.
     * Order (Option A):
     *   1) Typed getters: ModelPixelScale + ModelTiepoint (corner-based if i/j are 0; auto-fix if 0.5/0.5)
     *   2) GDAL_METADATA <GeoTransform> (corner-based; matches gdalinfo)
     *   3) Typed getter: ModelTransformation (4x4)  -> adjust center→corner
     *   4) Entry scan: ModelTransformation (double[16]) -> adjust center→corner
     *   5) Entry scan: Scale + Tiepoint arrays (auto-fix if 0.5/0.5)
     */
    private static AffineParams tryBuildAffineRobust(FileDirectory d) {
        // 1) Typed getters: Scale + Tiepoint
        double[] scale = tryGetModelPixelScale(d);
        double[] tie   = tryGetModelTiepoint(d);
        if (scale != null && scale.length >= 2 && tie != null && tie.length >= 6) {
            // tiepoint: [i, j, k, x, y, z]
            double sx = scale[0], sy = scale[1];
            double i = tie[0], j = tie[1], x = tie[3], y = tie[4];

            // Corner origin from scale+tiepoint
            double originX = x - i * sx;
            double originY = y + j * sy;

            // If writer used pixel-center indices (0.5,0.5), nudge to pixel-corner to match GDAL
            if (near(i, 0.5) && near(j, 0.5)) {
                originX -= 0.5 * sx;
                originY -= 0.5 * (-sy); // because we’ll set b2 negative below
            }

            // North-up affine (a2=b1=0; y pixel size negative)
            return new AffineParams(originX, sx, 0.0, originY, 0.0, -sy);
        }

        // 2) PREFERRED: GDAL_METADATA <GeoTransform> (corner-based)
        double[] gt = extractGeoTransformFromGdalMetadata(d);
        if (gt != null && gt.length == 6) {
            // GT = [a0, a1, a2, b0, b1, b2] already corner-based
            return new AffineParams(gt[0], gt[1], gt[2], gt[3], gt[4], gt[5]);
        }

        // 3) Typed getter: ModelTransformation (4x4) (often pixel-centers)
        double[] mt = tryGetModelTransformation(d);
        if (mt != null && mt.length == 16) {
            double a1 = mt[0], a2 = mt[1], a0 = mt[3];
            double b1 = mt[4], b2 = mt[5], b0 = mt[7];

            // Adjust center→corner (matches GDAL)
            double adjA0 = a0 - 0.5 * a1 - 0.5 * a2;
            double adjB0 = b0 - 0.5 * b1 - 0.5 * b2;
            return new AffineParams(adjA0, a1, a2, adjB0, b1, b2);
        }

        // 4) Entry scan: double[16] matrix (ModelTransformation signature)
        double[] mt2 = findDouble16Matrix(d);
        if (mt2 != null && mt2.length == 16) {
            double a1 = mt2[0], a2 = mt2[1], a0 = mt2[3];
            double b1 = mt2[4], b2 = mt2[5], b0 = mt2[7];

            double adjA0 = a0 - 0.5 * a1 - 0.5 * a2;
            double adjB0 = b0 - 0.5 * b1 - 0.5 * b2;
            return new AffineParams(adjA0, a1, a2, adjB0, b1, b2);
        }

        // 5) Entry scan: Scale + Tiepoint arrays
        double[] s2 = findScaleArray(d);
        double[] t2 = findTiepointArray(d);
        if (s2 != null && s2.length >= 2 && t2 != null && t2.length >= 6) {
            double sx = s2[0], sy = s2[1];
            double i = t2[0], j = t2[1], x = t2[3], y = t2[4];
            double originX = x - i * sx;
            double originY = y + j * sy;

            if (near(i, 0.5) && near(j, 0.5)) {
                originX -= 0.5 * sx;
                originY -= 0.5 * (-sy);
            }
            return new AffineParams(originX, sx, 0.0, originY, 0.0, -sy);
        }

        return null;
    }
    
    private static double[] tryGetModelPixelScale(FileDirectory d) { return getDoubleArrayViaGetter(d, "getModelPixelScale"); }
    private static double[] tryGetModelTiepoint(FileDirectory d)   { return getDoubleArrayViaGetter(d, "getModelTiepoint"); }
    private static double[] tryGetModelTransformation(FileDirectory d){ return getDoubleArrayViaGetter(d, "getModelTransformation"); }

    private static double[] getDoubleArrayViaGetter(FileDirectory d, String method) {
        try {
            var m = d.getClass().getMethod(method);
            Object v = m.invoke(d);
            return toDoubleArray(v);
        } catch (NoSuchMethodException ignore) {
        } catch (Exception e) {
            // ignore
        }
        return null;
    }

    private static Double tryReadNoDataRobust(FileDirectory d) {
        for (FileDirectoryEntry e : safeEntries(d)) {
            Object v = e.getValues();
            if (v instanceof Number) {
                double val = ((Number) v).doubleValue();
                if (val == -32767.0) return -32767.0;
            } else if (v instanceof Number[]) {
                Number[] nn = (Number[]) v;
                if (nn.length == 1) {
                    try { return nn[0].doubleValue(); } catch (Exception ignore) {}
                }
            } else if (v instanceof byte[] || v instanceof char[] || v instanceof String || v instanceof List<?>) {
                String s = toAscii(v).trim();
                if (s.equals("-32767") || s.equals("-32767.000000")) return -32767.0;
                try { return Double.valueOf(s); } catch (Exception ignore) {}
            }
        }
        return null;
    }

    private static String detectEpsgRobust(FileDirectory d) {
        // 1) Typed GeoKeyDirectory getter if present
        Object gk = getObjectViaGetter(d, "getGeoKeyDirectory");
        Integer epsg = parseGeoKeyDirectoryToEpsg(gk);
        if (epsg != null) return "EPSG:" + epsg;

        // 2) Scan entries for a vector that looks like a GeoKeyDirectory header (1,1,0,numKeys)
        for (FileDirectoryEntry e : safeEntries(d)) {
            epsg = parseGeoKeyDirectoryToEpsg(e.getValues());
            if (epsg != null) return "EPSG:" + epsg;
        }
        return null;
    }

    private static Object getObjectViaGetter(FileDirectory d, String method) {
        try { var m=d.getClass().getMethod(method); return m.invoke(d); }
        catch (NoSuchMethodException ignore) { return null; }
        catch (Exception e) { return null; }
    }

    private static Integer parseGeoKeyDirectoryToEpsg(Object v) {
        if (v == null) return null;
        int[] shorts = toUInt16Array(v);
        if (shorts == null || shorts.length < 4) return null;
        if (!(shorts[0]==1 && shorts[1]==1 && shorts[2]==0)) return null; // GeoKeyDirectory header
        int numKeys = shorts[3];
        int idx = 4;
        for (int k=0; k<numKeys && (idx+3)<shorts.length; k++, idx+=4) {
            int keyId   = shorts[idx];
            int tiffTag = shorts[idx+1]; // 0 => inline
            int count   = shorts[idx+2];
            int value   = shorts[idx+3];
            if ((keyId == KEY_ProjectedCSTypeGeoKey || keyId == KEY_GeographicTypeGeoKey) && tiffTag==0 && count==1) {
                return value;
            }
        }
        return null;
    }

    /* ---- content-signature scanners (handle List etc.) ---- */

    private static double[] findDouble16Matrix(FileDirectory d) {
        for (FileDirectoryEntry e : safeEntries(d)) {
            double[] m = toDoubleArray(e.getValues());
            if (m != null && m.length == 16) {
                // usually last row ~ [0,0,0,1], but be tolerant
                boolean okLast = (nearZero(m[12]) && nearZero(m[13]) && nearZero(m[14]) && nearOne(m[15]));
                if (okLast) return m;
                // or accept anyway if it "looks" like geotransform (common pattern: a1,a2,a0, b1,b2,b0,...)
                return m;
            }
        }
        return null;
    }

    private static double[] findScaleArray(FileDirectory d) {
        for (FileDirectoryEntry e : safeEntries(d)) {
            double[] a = toDoubleArray(e.getValues());
            if (a != null && a.length >= 2 && a.length <= 4) return a;
        }
        return null;
    }

    private static double[] findTiepointArray(FileDirectory d) {
        for (FileDirectoryEntry e : safeEntries(d)) {
            double[] a = toDoubleArray(e.getValues());
            if (a != null && a.length >= 6 && (a.length % 6 == 0)) return a;
        }
        return null;
    }

    private static double[] extractGeoTransformFromGdalMetadata(FileDirectory d) {
        String xml = null;
        for (FileDirectoryEntry e : safeEntries(d)) {
            Object v = e.getValues();
            if (v instanceof byte[] || v instanceof char[] || v instanceof String || v instanceof List<?>) {
                String s = toAscii(v);
                if (s.contains("<GDALMetadata>") && s.contains("<GeoTransform>")) {
                    xml = s; break;
                }
            }
        }
        if (xml == null) return null;
        int i0 = xml.indexOf("<GeoTransform>");
        int i1 = xml.indexOf("</GeoTransform>");
        if (i0 < 0 || i1 <= i0) return null;
        String body = xml.substring(i0 + 13, i1).trim();
        String[] toks = body.split("[,\\s]+");
        if (toks.length < 6) return null;
        double[] gt = new double[6];
        for (int i=0;i<6;i++) gt[i] = Double.parseDouble(toks[i]);
        return gt;
    }

    private static Set<FileDirectoryEntry> safeEntries(FileDirectory d) {
        Set<FileDirectoryEntry> s = d.getEntries();
        return (s == null) ? Collections.emptySet() : s;
        }

    private static String toAscii(Object v) {
        if (v == null) return "";
        if (v instanceof String) return (String) v;
        if (v instanceof byte[]) return new String((byte[]) v, StandardCharsets.UTF_8);
        if (v instanceof char[]) return new String((char[]) v);
        if (v instanceof List<?>) {
            // join list elements with commas (useful for metadata strings wrapped in a 1-element list)
            StringBuilder sb = new StringBuilder();
            boolean first = true;
            for (Object o : (List<?>) v) {
                if (!first) sb.append(',');
                sb.append(String.valueOf(o));
                first = false;
            }
            return sb.toString();
        }
        return String.valueOf(v);
    }

    private static double[] toDoubleArray(Object o) {
        if (o == null) return null;
        if (o instanceof double[]) return (double[]) o;
        if (o instanceof float[])  { float[] f=(float[])o; double[] d=new double[f.length]; for(int i=0;i<f.length;i++) d[i]=f[i]; return d; }
        if (o instanceof Number[]) { Number[] n=(Number[])o; double[] d=new double[n.length]; for(int i=0;i<n.length;i++) d[i]=n[i].doubleValue(); return d; }
        if (o instanceof short[])  { short[] s=(short[])o; double[] d=new double[s.length]; for(int i=0;i<s.length;i++) d[i]=s[i]; return d; }
        if (o instanceof char[])   { char[]  c=(char[]) o; double[] d=new double[c.length];  for(int i=0;i<c.length;i++)  d[i]=c[i]; return d; }
        if (o instanceof int[])    { int[]   a=(int[])   o; double[] d=new double[a.length]; for(int i=0;i<a.length;i++) d[i]=a[i]; return d; }
        if (o instanceof long[])   { long[]  a=(long[])  o; double[] d=new double[a.length]; for(int i=0;i<a.length;i++) d[i]=a[i]; return d; }
        if (o instanceof List<?>) {
            List<?> lst = (List<?>) o;
            double[] d = new double[lst.size()];
            for (int i=0;i<lst.size();i++) {
                Object v = lst.get(i);
                if (v instanceof Number) d[i] = ((Number) v).doubleValue();
                else {
                    try { d[i] = Double.parseDouble(String.valueOf(v)); }
                    catch (Exception ex) { return null; }
                }
            }
            return d;
        }
        if (o instanceof Object[]) {
            Object[] arr = (Object[]) o;
            double[] d = new double[arr.length];
            for (int i=0;i<arr.length;i++) {
                Object v = arr[i];
                if (v instanceof Number) d[i] = ((Number) v).doubleValue();
                else {
                    try { d[i] = Double.parseDouble(String.valueOf(v)); }
                    catch (Exception ex) { return null; }
                }
            }
            return d;
        }
        // Strings that look like "[a, b, c]"
        String s = String.valueOf(o).trim();
        if (s.startsWith("[") && s.endsWith("]")) {
            s = s.substring(1, s.length()-1);
            String[] toks = s.split("[,\\s]+");
            double[] d = new double[toks.length];
            try {
                for (int i=0;i<toks.length;i++) d[i] = Double.parseDouble(toks[i]);
                return d;
            } catch (Exception ignore) {}
        }
        return null;
    }

    private static int[] toUInt16Array(Object v) {
        if (v == null) return null;
        if (v instanceof int[])   return (int[]) v;
        if (v instanceof short[]) { short[] s=(short[])v; int[] out=new int[s.length]; for (int i=0;i<s.length;i++) out[i]=s[i]&0xFFFF; return out; }
        if (v instanceof char[])  { char[]  c=(char[]) v; int[] out=new int[c.length]; for (int i=0;i<c.length;i++)  out[i]=c[i]&0xFFFF; return out; }
        if (v instanceof long[])  { long[]  a=(long[]) v; int[] out=new int[a.length]; for (int i=0;i<a.length;i++)  out[i]=(int)(a[i]&0xFFFF); return out; }
        if (v instanceof Number[]) { Number[] n=(Number[])v; int[] out=new int[n.length]; for(int i=0;i<n.length;i++) out[i]=n[i].intValue() & 0xFFFF; return out; }
        if (v instanceof List<?>) {
            List<?> lst = (List<?>) v;
            int[] out=new int[lst.size()];
            for (int i=0;i<lst.size();i++) {
                Object o = lst.get(i);
                if (o instanceof Number) out[i] = ((Number)o).intValue() & 0xFFFF;
                else {
                    try { out[i] = Integer.parseInt(String.valueOf(o)) & 0xFFFF; }
                    catch (Exception ex) { return null; }
                }
            }
            return out;
        }
        // strings like "[1, 1, 0, 8, ...]"
        String s = String.valueOf(v).trim();
        if (s.startsWith("[") && s.endsWith("]")) {
            s = s.substring(1, s.length()-1);
            String[] toks = s.split("[,\\s]+");
            int[] out = new int[toks.length];
            try { for (int i=0;i<toks.length;i++) out[i] = Integer.parseInt(toks[i]) & 0xFFFF; return out; }
            catch (Exception ignore) {}
        }
        return null;
    }

    private static boolean nearZero(double v) { return Math.abs(v) < 1e-10; }
    private static boolean nearOne (double v) { return Math.abs(v-1.0) < 1e-10; }

    private Double sample(int col, int row) {
        try {
            // Try the safest direct methods first
            if (mGetPixelSampleDouble != null) {
                try {
                    Object ret = mGetPixelSampleDouble.invoke(rasters, col, row, 0);
                    double v = ((Number) ret).doubleValue();
                    if (Double.isNaN(v)) return null;
                    if (noData != null && Double.compare(v, noData) == 0) return null;
                    return v;
                } catch (java.lang.reflect.InvocationTargetException ite) {
                    // if it's a sample-index error, retry swapped order
                    Throwable cause = ite.getCause();
                    if (cause != null && cause.getClass().getName().endsWith("TiffException")
                        && cause.getMessage() != null
                        && cause.getMessage().toLowerCase().contains("sample out of bounds")) {
                        Object ret = mGetPixelSampleDouble.invoke(rasters, 0, col, row);
                        double v = ((Number) ret).doubleValue();
                        if (Double.isNaN(v)) return null;
                        if (noData != null && Double.compare(v, noData) == 0) return null;
                        return v;
                    } else {
                        throw ite;
                    }
                }
            }

            if (mGetPixelSample != null) {
                try {
                    Object ret = mGetPixelSample.invoke(rasters, col, row, 0); // assume (x,y,sample)
                    double v = ((Number) ret).doubleValue();
                    if (Double.isNaN(v)) return null;
                    if (noData != null && Double.compare(v, noData) == 0) return null;
                    return v;
                } catch (java.lang.reflect.InvocationTargetException ite) {
                    // retry as (sample,x,y)
                    Throwable cause = ite.getCause();
                    if (cause != null && cause.getClass().getName().endsWith("TiffException")
                        && cause.getMessage() != null
                        && cause.getMessage().toLowerCase().contains("sample out of bounds")) {
                        Object ret = mGetPixelSample.invoke(rasters, 0, col, row);
                        double v = ((Number) ret).doubleValue();
                        if (Double.isNaN(v)) return null;
                        if (noData != null && Double.compare(v, noData) == 0) return null;
                        return v;
                    } else {
                        throw ite;
                    }
                }
            }

            if (mGetFirstPixelSample != null) {
                Object ret = mGetFirstPixelSample.invoke(rasters, col, row); // (x,y)
                double v = ((Number) ret).doubleValue();
                if (Double.isNaN(v)) return null;
                if (noData != null && Double.compare(v, noData) == 0) return null;
                return v;
            }

            if (mGetPixel != null) {
                Object px = mGetPixel.invoke(rasters, col, row); // (x,y)
                if (px == null) return null;
                Number first = null;
                if (px instanceof Number[]) {
                    Number[] arr = (Number[]) px;
                    if (arr.length > 0) first = arr[0];
                } else if (px.getClass().isArray()) {
                    Object f = java.lang.reflect.Array.getLength(px) > 0 ? java.lang.reflect.Array.get(px, 0) : null;
                    if (f instanceof Number) first = (Number) f;
                } else if (px instanceof Number) {
                    first = (Number) px;
                }
                if (first == null) return null;
                double v = first.doubleValue();
                if (Double.isNaN(v)) return null;
                if (noData != null && Double.compare(v, noData) == 0) return null;
                return v;
            }

            throw new IllegalStateException("No compatible pixel-sample method on Rasters.");

        } catch (Exception e) {
            throw new RuntimeException("Raster sample read failed", e);
        }
    }
    
    private double[] worldFromPixel(double col, double row) {
        return new double[] { a0 + a1*col + a2*row, b0 + b1*col + b2*row };
    }
    private double[] pixelFromWorld(double x, double y) {
        double dx = x - a0, dy = y - b0;
        double col = inv00*dx + inv01*dy;
        double row = inv10*dx + inv11*dy;
        return new double[] { col, row };
    }

    /** Build or fetch a transform from an input CRS name to the raster's data CRS. */
    private CoordinateTransform transformFromTo(String srcCrsName, CoordinateReferenceSystem dstCRS) {
        String key = srcCrsName + "→" + dstCRS.getName();
        CoordinateTransform ct = reprojCache.get(key);
        if (ct != null) return ct;

        CoordinateReferenceSystem src = crsFactory.createFromName(srcCrsName);
        CoordinateTransform xform = ctf.createTransform(src, dstCRS);
        reprojCache.put(key, xform);
        return xform;
    }

    /* ===== Helpers / types ===== */

    private static class AffineParams { final double a0,a1,a2,b0,b1,b2;
        AffineParams(double a0,double a1,double a2,double b0,double b1,double b2){
            this.a0=a0; this.a1=a1; this.a2=a2; this.b0=b0; this.b1=b1; this.b2=b2; } }

    public static class Bounds {
        public final double minX, minY, maxX, maxY;
        public Bounds(double minX,double minY,double maxX,double maxY){ this.minX=minX; this.minY=minY; this.maxX=maxX; this.maxY=maxY; }
        public String toString(){ return String.format("minX=%.6f, minY=%.6f, maxX=%.6f, maxY=%.6f",minX,minY,maxX,maxY); }
    }

    /* ===== Optional filename seeding (kept) ===== */

    public void seedFromFilenameIfNeeded(String filename, double pxX, double pxY) {
        if (this.georeferenced) return;
        DmsLatLon ll = parseDmsFromName(filename);
        if (ll == null) return;
        int utmZone = (int)Math.floor((ll.lon + 180.0)/6.0) + 1;
        boolean north = ll.lat >= 0;
        String epsg = String.format("EPSG:%d", (north?32600:32700)+utmZone);
        enableCrs(epsg);
        ProjCoordinate ctr = transform(wgsToData, ll.lon, ll.lat);
        double ox = ctr.x - (width*0.5)*pxX;
        double oy = ctr.y + (height*0.5)*pxY;
        setAffine(ox, pxX, 0.0, oy, 0.0, -pxY);
        this.georeferenced = true;
        this.dataEpsg = epsg;
    }

    private static class DmsLatLon { final double lat, lon; DmsLatLon(double lat,double lon){this.lat=lat; this.lon=lon;} }
    private static DmsLatLon parseDmsFromName(String name) {
        Pattern p = Pattern.compile("(?i)(\\d{2})(\\d{2})(\\d{2})([WE])[^\\d]*(\\d{2})(\\d{2})(\\d{2})([NS])");
        Matcher m = p.matcher(name);
        if (!m.find()) return null;
        int lonD=Integer.parseInt(m.group(1)), lonM=Integer.parseInt(m.group(2)), lonS=Integer.parseInt(m.group(3));
        int latD=Integer.parseInt(m.group(5)), latM=Integer.parseInt(m.group(6)), latS=Integer.parseInt(m.group(7));
        char lonH=m.group(4).toUpperCase().charAt(0), latH=m.group(8).toUpperCase().charAt(0);
        double lon = lonD + lonM/60.0 + lonS/3600.0; if (lonH=='W') lon=-lon;
        double lat = latD + latM/60.0 + latS/3600.0; if (latH=='S') lat=-lat;
        return new DmsLatLon(lat,lon);
    }

    private static ProjCoordinate transform(CoordinateTransform ct, double x, double y) {
        ProjCoordinate src = new ProjCoordinate(x,y), dst = new ProjCoordinate();
        ct.transform(src, dst); return dst;
    }

    /** Returns something like "EPSG:32616" or null if unknown. */
    // 326XX is WGS84 UTM in Northern hemisphere
    // 327XX is WGS84 UTM in Southern hemisphere
    
    static String determineHorizontalCRS(FileDirectory d) {
        // Prefer GeoKeyDirectory → ProjectedCSType(3072) or GeographicType(2048)
        int[] gk = readGeoKeyDirectoryU16(d);
        if (looksLikeGeoKeyDirectory(gk)) {
            int numKeys = gk[3], idx = 4;
            for (int k = 0; k < numKeys && (idx + 3) < gk.length; k++, idx += 4) {
                int keyId = gk[idx];
                int tiffTag = gk[idx + 1];
                int count  = gk[idx + 2];
                int value  = gk[idx + 3];
                if ((keyId == 3072 /* ProjectedCSType */ || keyId == 2048 /* GeographicType */)
                    && tiffTag == 0 && count == 1) {
                    return "EPSG:" + value;
                }
            }
        }

        // Fallback: some files stash EPSG in <GDALMetadata>
        String gdal = findGdalMetadata(d);
        if (gdal != null) {
            // quick scan for "EPSG:nnnnn"
            java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("EPSG\\s*:\\s*(\\d{3,6})").matcher(gdal);
            if (m.find()) return "EPSG:" + m.group(1);
        }
        return null;
    }

    /** Returns a descriptive vertical datum/CRS:
     *  e.g., "EPSG:5703" (NAVD88), "EPSG:5773" (EGM96), "EPSG:3855" (EGM2008),
     *  or "ELLIPSOID (WGS84 G1674)" when no vertical GeoKeys but ellipsoidal is indicated.
     *  Returns null if truly unknown.
     */
    static String determineVerticalCRS(FileDirectory d)
    {
        // 1) GeoKeyDirectory vertical keys
        int[] gk = readGeoKeyDirectoryU16(d);
        if (looksLikeGeoKeyDirectory(gk)) {
            int numKeys = gk[3], idx = 4;
            for (int k = 0; k < numKeys && (idx + 3) < gk.length; k++, idx += 4) {
                int keyId = gk[idx];
                int tiffTag = gk[idx + 1];
                int count  = gk[idx + 2];
                int value  = gk[idx + 3];

                // VerticalCSTypeGeoKey (GeoTIFF spec ID: 4096)
                if (keyId == 4096 && tiffTag == 0 && count == 1) {
                    System.out.println("determineVerticalCRS: has vertical metadata "+value);
                    // Common mappings
                    if (value == 5703) return "EPSG:5703"; // NAVD88
                    if (value == 5773) return "EPSG:5773"; // EGM96 geoid
                    if (value == 3855) return "EPSG:3855"; // EGM2008 geoid
                    if (value == 4979) return "EPSG:4979"; // WGS84 3D (ellipsoidal)
                    return "EPSG:" + value;               // some other vertical CRS
                }
                // VerticalDatumGeoKey (4098) / VerticalUnitsGeoKey (4099) can hint too,
                // but if 4096 exists it’s definitive, so we prefer that.
            }
        }

        // 2) GDAL metadata hints (Maxar/Vricon typically: R3DM_DATUM_REALIZATION=G1674)
        String gdal = findGdalMetadata(d);
        if (gdal != null) {
            System.out.println("determineVerticalCRS: gdal metadata is "+gdal);
            // Vertical datum named explicitly?
            if (gdal.toLowerCase().contains("vertical-datum")) {
                // Try to capture "ELLIPSOID", "EGM96", "EGM2008", etc.
                java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("vertical[-_ ]?datum[^>]*>\\s*([^<\\s]+)", java.util.regex.Pattern.CASE_INSENSITIVE)
                    .matcher(gdal);
                if (m.find()) {
                    String v = m.group(1).toUpperCase();
                    System.out.println("determineVerticalCRS: found "+v);
                    if (v.contains("EGM2008")) return "EPSG:3855";
                    if (v.contains("EGM96"))  return "EPSG:5773";
                    if (v.contains("NAVD88")) return "EPSG:5703";
                    if (v.contains("ELLIPSOID")) {
                        // Look for WGS84 realization
                        String realize = findMetadataItem(gdal, "R3DM_DATUM_REALIZATION");
                        if (realize == null) realize = findMetadataItem(gdal, "DATUM_REALIZATION");
                        if (realize != null && !realize.isEmpty())
                            return "ELLIPSOID (WGS84 " + realize + ")";
                        return "ELLIPSOID (WGS84)";
                    }
                }
            }
            // Maxar-style: R3DM_DATUM_REALIZATION = G1674 → ellipsoidal HAE on WGS84 G1674
            String realize = findMetadataItem(gdal, "R3DM_DATUM_REALIZATION");
            if (realize != null && !realize.isEmpty()) {
                System.out.println("determineVerticalCRS: G1674 is WGS84");
                return "ELLIPSOID (WGS84 " + realize + ")";
            }
        }

        // 3) As a last resort infer
        return inferVerticalType(d);
    }

    /** Simple vertical types we’ll report and/or use for conversion decisions. */
    enum VerticalType { HAE_WGS84, EGM96, EGM2008, NAVD88, UNKNOWN }

    /** Best-effort vertical inference:
     *  1) Prefer GeoKeyDirectory VerticalCSType (4096).
     *  2) Else check GDALMetadata for hints (e.g., vertical-datum, R3DM_DATUM_REALIZATION).
     *  3) Else vendor heuristics: Maxar/Vricon => HAE_WGS84; OT/USGS => UNKNOWN (likely orthometric).
     */
    static String inferVerticalType(FileDirectory d)
    {
        // 1) GeoKeyDirectory vertical code if present
        int[] gk = readGeoKeyDirectoryU16(d);
        if (looksLikeGeoKeyDirectory(gk)) {
            int numKeys = gk[3], idx = 4;
            for (int k = 0; k < numKeys && (idx + 3) < gk.length; k++, idx += 4) {
                int keyId = gk[idx], tiffTag = gk[idx+1], count = gk[idx+2], value = gk[idx+3];
                if (keyId == 4096 && tiffTag == 0 && count == 1) { // VerticalCSTypeGeoKey
                    switch (value) {
                    case 5703: return "EPSG:5703";   // NAVD88 height
                    case 5773: return "EPSG:5773";   // EGM96 height
                    case 3855: return "EPSG:3855";   // EGM2008 height
                    case 4979: return "EPSG:4979";   // WGS84 ellipsoidal (3D)
                    default:   return "EPSG:unknown";      // unknown
                    }
                }
            }
        }

        // 2) GDAL metadata hints
        String gdal = findGdalMetadata(d);
        if (gdal != null) {
            System.out.println("InferVerticalDatum: gdal metadata is "+gdal);
            String lower = gdal.toLowerCase();
            // if (lower.contains("vertical-datum")) {
            //     if (lower.contains("egm2008")) return VerticalType.EGM2008;
            //     if (lower.contains("egm96"))   return VerticalType.EGM96;
            //     if (lower.contains("navd88"))  return VerticalType.NAVD88;
            //     if (lower.contains("ellipsoid")) return VerticalType.HAE_WGS84;
            // }
            // Maxar/Vricon convention: R3DM_DATUM_REALIZATION=G1674 ⇒ WGS84 realization ⇒ HAE
            //if (gdal.contains("R3DM_DATUM_REALIZATION")) return VerticalType.HAE_WGS84;
        }

        // if we get here, couldnt find any metadata nor strong clue like maxor gdal metadata
        // return 0 and thus go by gtype for OpenTopography geotiff files
        return "EPSG:0";
    }

    /* ================= helpers used above ================= */

    // Read GeoKeyDirectory (UInt16 array) via typed getter or by scanning entries.
    private static int[] readGeoKeyDirectoryU16(FileDirectory d) {
        Object gk = getObjectViaGetter(d, "getGeoKeyDirectory");
        int[] s = toUInt16Array(gk);
        if (looksLikeGeoKeyDirectory(s)) return s;
        // Fallback: scan entries for something that looks like the GeoKeyDirectory vector
        for (FileDirectoryEntry e : safeEntries(d)) {
            s = toUInt16Array(e.getValues());
            if (looksLikeGeoKeyDirectory(s)) return s;
        }
        return null;
    }

    // Extract <GDALMetadata> XML string if present.
    private static String findGdalMetadata(FileDirectory d) {
        for (FileDirectoryEntry e : safeEntries(d)) {
            String s = toAscii(e.getValues());
            if (s != null && s.contains("<GDALMetadata>")) return s;
        }
        return null;
    }

    // Pull the value of an <Item name="...">...</Item> in GDAL metadata (simple string search).
    private static String findMetadataItem(String gdalXml, String name) {
        if (gdalXml == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern
            .compile("<Item\\s+name=\""+java.util.regex.Pattern.quote(name)+"\"[^>]*>(.*?)</Item>",
                     java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.DOTALL)
            .matcher(gdalXml);
        return m.find() ? m.group(1).trim() : null;
    }

    private static boolean looksLikeGeoKeyDirectory(int[] s) {
        return s != null && s.length >= 4 && s[0]==1 && s[1]==1 && s[2]==0;
    }
 
    /* =================== CLI =================== */

    public static void main(String[] args) throws Exception
    {
        if (args.length < 1) {
            System.err.println("Usage: java MaxarDtmReader <dtm.tif> [lat lon]...");
            System.exit(1);
        }
        File f = new File(args[0]);
        try (MaxarDtmReader dtm = new MaxarDtmReader(f)) {
            System.out.println("Size: " + dtm.getWidth() + " x " + dtm.getHeight());
            System.out.println("Georeferenced: " + dtm.isGeoreferenced());
            System.out.println("Data CRS: " + (dtm.getDataEpsg()==null?"(unknown)":dtm.getDataEpsg()));
            System.out.println("Horizontal CRS: "+dtm.getHorizontalCRS());
            System.out.println("Vertical CRS: "+dtm.getVerticalCRS());            
            System.out.println("Bounds (data/pixel): " + dtm.getBoundsDataCRS());
            if (dtm.isGeoreferenced()) {
                System.out.println("Bounds (WGS84): " + dtm.getBoundsWGS84());
            } else {
                System.out.println("Bounds (WGS84): [unavailable]");
            }

            int i=1;
            while (i+1 < args.length) {
                double lat = Double.parseDouble(args[i]), lon = Double.parseDouble(args[i+1]);
                i += 2;
                try {
                    //var ez = dtm.getAltitudeAtLatLon(lat,lon);
                    var ez = dtm.getElevationAtLatLon(lat,lon);
                    System.out.printf("Elev @ (%.6f, %.6f): %s%n", lat, lon,
                        ez.map(z -> String.format("%.3f m", z)).orElse("NoData / OOB"));
                } catch (IllegalStateException ex) {
                    System.out.println("Lon/lat query not available (image unreferenced).");
                }
            }
        }
    }
}
