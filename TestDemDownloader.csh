#!/bin/csh

# periodically, via cron, test our OPENTOPOGRAPHY_API_KEY

source /krupczak/rdk/.cshrc.cron
echo "Key is $OPENTOPOGRAPHY_API_KEY" 
cd /krupczak/rdk/repos/ThetaTools
/bin/java DemDownloader -usgs 33.836673 -84.521890 1000
