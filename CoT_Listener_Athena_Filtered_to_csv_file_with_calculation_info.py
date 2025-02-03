#!/usr/bin/python3

# Copyright 2024 Theta Informatics LLC
# Provided under MIT license software license
# Modification and Redistribution permitted while above notice is preserved

import sys
import socket
import struct
import xml.etree.ElementTree as ET
import csv
from datetime import datetime, timezone

# Multicast group details
MCAST_GRP = '239.2.3.1'
MCAST_PORT = 6969

# Setup the socket for multicast
sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)
sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
sock.bind(('', MCAST_PORT))

mreq = struct.pack("4sl", socket.inet_aton(MCAST_GRP), socket.INADDR_ANY)
sock.setsockopt(socket.IPPROTO_IP, socket.IP_ADD_MEMBERSHIP, mreq)

# Get the current time in ISO8601 format with timezone
current_time = datetime.now(timezone.utc).isoformat()
# Replace colons and periods to make it a legal filename on Windows
file_safe_time = current_time.replace(':', '-').replace('.', '-')

# CSV file to store the data
csv_file = f'OA-CoT-Capture-{file_safe_time}.csv'

# Column names for the CSV file
oa_core_fieldnames = ['EXIF DateTime', 'Processed DateTime', 'lat', 'lon', 'hae', 'ce', 'droneLatitude', 'droneLongitude', 'droneElevationHAE', 'cameraRollAngleDeg', 'cameraSlantAngleDeg', 'raySlantAngleDeg', 'make', 'model', 'isCameraModelRecognized', 'lensType', 'focalLength', 'digitalZoomRatio', 'gimbalPitchDegree', 'gimbalYawDegree', 'imageWidth', 'imageHeight', 'f_x', 'f_y', 'azimuthOffsetUserCorrection', 'imageSelectedProportionX', 'imageSelectedProportionY', 'yawOffsetDegSelectedPoint', 'pitchOffsetDegSelectedPoint', 'slantRange']
oa_android_debug_fieldnames = ['EXIF DateTime', 'Processed DateTime', 'lat', 'lon', 'hae', 'ce', 'droneLatitude', 'droneLongitude', 'droneElevationHAE', 'cameraRollAngleDeg', 'cameraSlantAngleDeg', 'raySlantAngleDeg', 'make', 'model', 'isCameraModelRecognized', 'lensType', 'k1', 'k2', 'k3', 'p1', 'p2', 'focalLength', 'digitalZoomRatio', 'imageWidth', 'imageLength', 'f_x', 'f_y', 'azimuthOffsetUserCorrection', 'imageSelectedProportionX', 'imageSelectedProportionY', 'yawOffsetDegSelectedPoint', 'pitchOffsetDegSelectedPoint', 'slantRange']
fieldnames = None

isAndroid = False
isCore = False

try:
    if sys.argv[1] is None:
        sys.exit("FATAL ERROR: please type core or android after this script's name to select correct recording mode")
except IndexError:
        sys.exit("FATAL ERROR: please type core or android after this script's name to select correct recording mode")

if 'android' in sys.argv[1].strip().lower():
    isAndroid = True
    fieldnames = oa_android_debug_fieldnames
elif 'core' in sys.argv[1].strip().lower():
    isCore = True
    fieldnames = oa_core_fieldnames
else:
    sys.ext("FATAL ERROR: recording mode \"" + sys.argv[1].strip() + "\" was not recognized")

try:
    with open(csv_file, 'w', newline='', buffering=1) as file:
        # Initialize CSV writer with line buffering
        # TODO NEED A CLI ARGUMENT FOR CORE VS ANDROID FIELDNAMES
        writer = csv.DictWriter(file, fieldnames=fieldnames)
        writer.writeheader()

        try:
            while True:
                data, _ = sock.recvfrom(2048)  # Buffer size of 2048 bytes
                try:
                    data = data.decode('utf-8')
                except UnicodeDecodeError:
                    print("Received data could not be decoded as UTF-8")
                    continue  # Skip to the next iteration of the loop if decoding fails

                if not data.strip():
                    print("CoT message was empty")
                    continue  # Skip to next iteration of loop if empty

                print("Received CoT:")
                print(data)

                # Parse XML data
                try:
                    root = ET.fromstring(data)
                except ET.ParseError as e:
                    print(f"Failed to parse XML: {e}")
                    continue  # Skip if XML is malformed

                # Extract data fields
                event = root
                point = root.find('point')

                # Check if the openAthenaCalculationInfo field is present
                calc_info = root.find('.//openAthenaCalculationInfo')

                # Skip this message if openAthenaCalculationInfo is not found
                # CoT came from a different program than OpenAthena Android (debug)
                if calc_info is None:
                    print("openAthenaCalculationInfo was missing, skipping this message...")
                    continue

                if event is not None and point is not None:
                    data_row = {
                        'EXIF DateTime': event.get('time'),
                        'Processed DateTime': event.get('start'),
                        'lat': point.get('lat'),
                        'lon': point.get('lon'),
                        'hae': point.get('hae'),
                        'ce': point.get('ce'),
                    }

                    uid = event.get('uid')


                    if isAndroid and not 'openathena' in uid.lower():
                        # skip this CoT message if it was sent from a program other than OpenAthena (and its variants)
                        print("CoT not from openathena, skipping this message...")
                        continue
                    elif isCore and not 'openathenacore' in uid.lower():
                        # skip this CoT message if it was sent from a program other than OpenAthena Core
                        print("CoT not from openathena, skipping this message...")
                        continue

                    # Extract all attributes from openAthenaCalculationInfo
                    for attr in fieldnames[6:]:  # Skip exif time, processeddatetime, lat, lon, hae, ce which are already handled
                        # TODO better error handling if some fields are missing
                        data_row[attr] = calc_info.get(attr)

                    # Write to CSV
                    writer.writerow(data_row)
                    file.flush()  # Ensure data is written to disk immediately

        except KeyboardInterrupt:
            print("Stopped by User")
        finally:
            sock.close()

    print("CSV file has been written with multicast UDP data.")

except Exception as e:
    print(f"An error occurred: {e}")
