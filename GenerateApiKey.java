// GenerateApiKey.java
// Bobby Krupczak

// javac -cp ./core-0.2.5-SNAPSHOT.jar GenerateApiKey.java
// java -cp .:./core-0.2.5-SNAPSHOT.jar GenerateApiKey 

import org.json.JSONObject;

import com.openathena.core.ApiKey;

public class GenerateApiKey
{
    public static void usage()
    {
	System.out.println("GenerateApiKey: [user|admin] organization contact-email");
	System.exit(-1);
    }
    
    public static void main(String[] args)
    {
	if (args.length == 0) {
	    usage();
	}

	String permission = args[0];
	if ((args[0].equals("user") == false) &&
	    (args[0].equals("admin") == false)) {
	    usage();
	}

	String org = args[1];

	String email = args[2];
	if (email.indexOf('@') == -1) {
	    usage();
	}

	ApiKey key = new ApiKey();
	key.permission = permission;
	key.organization = org;
	key.contact = email;

	System.out.println(key.toJSON().toString());
    }


}
