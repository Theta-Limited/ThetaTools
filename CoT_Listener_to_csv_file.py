#!/usr/bin/python3

# Copyright 2024 Theta Informatics LLC
# Provided under MIT license software license
# Modification and Redistribution permitted while above notice is preserved

import socket
import struct
import xml.etree.ElementTree as ET
import csv
from datetime import datetime
import pytz

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
current_time = datetime.now(pytz.utc).isoformat()
# Replace colons and periods to make it a legal filename on Windows
file_safe_time = current_time.replace(':', '-').replace('.', '-')


# CSV file to store the data
csv_file = f'OA-CoT-Capture-{file_safe_time}.csv'
fieldnames = ['lat', 'lon', 'hae', 'droneLatitude', 'droneLongitude', 'droneElevationHAE', 'cameraRollAngleDeg', 'cameraSlantAngleDeg', 'raySlantAngleDeg', 'make', 'model', 'isCameraModelRecognized', 'lensType', 'k1', 'k2', 'k3', 'p1', 'p2', 'focalLength', 'digitalZoomRatio', 'imageWidth', 'imageLength', 'f_x', 'f_y', 'azimuthOffsetUserCorrection', 'imageSelectedProportionX', 'imageSelectedProportionY', 'yawOffsetDegSelectedPoint', 'pitchOffsetDegSelectedPoint', 'slantRange']

with open(csv_file, 'w', newline='') as file:
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

            print("Received CoT:")
            print(data)

            # Parse XML data
            root = ET.fromstring(data)

            # Extract data fields
            point = root.find('point')

            # Check if the openAthenaCalculationInfo field is present
            calc_info = root.find('.//openAthenaCalculationInfo')

            # Skip this message if openAthenaCalculationInfo is not found
            # CoT came from a different program than OpenAthena Android (debug)
            # Remove next two lines if desirable to capture all CoT
            if calc_info is None:
                continue

            if point is not None and calc_info is not None:
                data_row = {
                    'lat': point.get('lat'),
                    'lon': point.get('lon'),
                    'hae': point.get('hae'),
                }

                # Extract all attributes from openAthenaCalculationInfo
                # remove this loop if desirable to capture all CoT
                for attr in fieldnames[3:]:  # Skip lat, lon, hae which are already handled
                    data_row[attr] = calc_info.get(attr)

                # Write to CSV
                writer.writerow(data_row)
    except KeyboardInterrupt:
        print("Stopped by User")
    finally:
        sock.close()

print("CSV file has been written with multicast UDP data.")
