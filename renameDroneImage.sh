#!/bin/bash
# written by chatgpt
# take a drone image and rename to
# make-model-date-hash.jpg

# Check if a file is provided
if [ "$#" -ne 1 ]; then
    echo "Usage: $0 <image-file>"
    exit 1
fi

# Assign the image file to a variable
IMAGE_FILE="$1"

# Check if the file is an image
# if ! [[ "$IMAGE_FILE" =~ \.(jpg|jpeg|png|gif|bmp|tiff)$ ]]; then
if ! file "$IMAGE_FILE" | grep -iqE 'image|jpeg|png|gif|bitmap|tiff'; then
    echo "Error: File is not a supported image format."
    exit 2
fi

# Use exiv2 to extr
# Use exiv2 to extract make, model, and date
# skydio camera don't fill in "Image timestamp"
# exiv2 -K Exif.Image.DateTime -Pv
MAKE=$(exiv2 "$IMAGE_FILE" | grep 'Camera make' | awk -F': ' '{print $2}' | sed 's/ /_/g')
MODEL=$(exiv2 "$IMAGE_FILE" | grep 'Camera model' | awk -F': ' '{print $2}' | sed 's/ /_/g')
# DATE=$(exiv2 "$IMAGE_FILE" | grep 'Image timestamp' | awk -F': ' '{print $2}' | sed 's/ /_/g' | sed 's/:/-/g')
DATE=$(exiv2 -K Exif.Image.DateTime -Pv "$IMAGE_FILE" | sed 's/ /_/g' | sed 's/:/-/g')

# Calculate the hash of the image
HASH=$(md5sum "$IMAGE_FILE" | awk '{print $1}')

# Construct new file name
NEW_NAME="${MAKE}-${MODEL}-${DATE}-${HASH}.jpg"

# Rename the file
mv "$IMAGE_FILE" "$NEW_NAME"

echo "File renamed to $NEW_NAME"
