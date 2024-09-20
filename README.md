# ThetaTools
Various tools and documentation created and/or used by Theta Informatics LLC.

## [Cursor on Target Technical Implementation Guide PDF](https://github.com/Theta-Limited/ThetaTools/raw/main/CursorOnTarget_Technical_Implementation_Guide.pdf)

The file CursorOnTarget_Technical_Implementation_Guide.pdf in this repo contains significant developer resources for the Cursor on Target (CoT) protocol, initially published by the MITRE corporation. This protocol has since become widely integrated through [ATAK](https://en.wikipedia.org/wiki/Android_Team_Awareness_Kit) and compatible platforms.

This document also contains an example of OpenAthena's Cursor on Target message syntax.

Theta published this guide due to the general lack of availability of organized information on the CoT protocol. This resource is intended to make developer's experience making software easier for interoperability.


## [CursorOnTargetSender.java](https://github.com/Theta-Limited/ThetaTools/blob/main/CursorOnTargetSender.java)

The file `CursorOnTargetSender.java` in this repo contains a reference implementation of a simple CoT sending program written in Java which requires no external dependencies. It is provided under the permissive [Creative Commons 0 open source license](https://github.com/Theta-Limited/ThetaTools/blob/main/LICENSE), providing as broad rights as possible for use including in commercial products. This is provided to improve the ease by which developers may implement the protocol in their own software.

## [CoT_Listener_to_csv_file.py](https://github.com/Theta-Limited/ThetaTools/blob/main/CoT_Listener_to_csv_file.py)

The file `CoT_Listener_to_csv_file.py` is a simple Python script which will dump incoming Cursor on Target messages from the local network and save to a comma separated values (CSV) file compatible with most spreadsheet software.

First [install Python](https://www.python.org/downloads/), then run the following command in Command Prompt, PowerShell, or the Terminal to begin capturing CoT messages from OpenAthena:

```
python3 CoT_Listener_to_csv_file.py
```

If the above command does not work, change `python3` to just `python`.

## [DemDownloader.java](https://github.com/Theta-Limited/ThetaTools/blob/main/DemDownloader.java)

The file `DemDownloader.java` in this repo contains a reference implementation of a simple GeoTiff Digital Elevation Model downloader in Java which uses the [OpenTopography.org API](https://opentopography.org/developers).

The OpenTopography API provides Digital Elevation Model data for customized areas on-demand via internet. is hosted by UCSD's San Diego Super Computer Center with assistance from the National Science Foundation. It is not affiliated with Theta or the OpenAthena project.

## [renameDroneImage.sh](https://github.com/Theta-Limited/ThetaTools/blob/main/renameDroneImage.sh)

The file `renameDroneImage.sh` in this repo contains a BASH script which renames drone images within a directory to more descriptive filenames. The renamed filename format is `make-model-date-hash.jpg`. This script makes organization of drone images from a wide variety of makes and models easier to manage.
