// Egm2008.java
// Compile: javac -cp . Egm2008.java
// Run:     java  -cp . Egm2008 33.7757 -84.3963 300.12 /data/geoids egm2008-1
//          args: <lat> <lon> [ellipsoidal_h_m] [geoid_dir] [modelName]
// java Egm2008 33.82687449997223 -84.52415229997222 ./geoids egm2008-5

public class Egm2008
{
  // If you placed Geoid.java in a package, fix the import accordingly:
  // import your.package.Geoid;
  public static void main(String[] args) throws Exception
    {
    if (args.length < 2) {
        //System.err.println("Usage: java EGM2008 <lat> <lon> [geoid_dir] [modelName]");
        //System.err.println("Example: java EGM2008 33.7757 -84.3963 /data/geoids egm2008-1");
        System.err.println("Example: java EGM2008 33.7757 -84.3963 ./geoids/egm2008-1.pgm");        
      System.exit(2);
    }
    double lat = Double.parseDouble(args[0]);
    double lon = Double.parseDouble(args[1]);
    String geoidFile = (args.length >= 3) ? args[2] : ".";
    String model    = (args.length >= 4) ? args[3] : "egm2008-1";

    // cubic=true (smoother), threadsafe=false (set true if sharing across threads)
    Geoid geoid = new Geoid(model, geoidFile, true, false);

    double N = geoid.computeGeoidHeight(lat, lon); // meters: geoid above WGS84 ellipsoid
    System.out.printf("EGM2008 geoid height N = %.3f m at (%.6f, %.6f)%n", N, lat, lon);

    //if (hEllips != null) {
    //  double H = hEllips - N; // Orthometric height (MSL) = ellipsoidal h - geoid undulation
    //  System.out.printf("Orthometric height H (MSL) = %.3f m (H = h - N)%n", H);
    //}
  }
}
