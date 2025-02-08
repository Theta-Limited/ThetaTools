#!/bin/bash
# written by chatgpt
# take one or more drone images and rename each to:
# make-model-date-hash.jpg

# Check if at least one file is provided
if [ "$#" -lt 1 ]; then
    echo "Usage: $0 <image-file> [<image-file>...]"
    exit 1
fi

# Loop over each file provided as an argument
for IMAGE_FILE in "$@"; do
    echo "Processing '$IMAGE_FILE'..."

    # Check if the file exists and is a regular file
    if [ ! -f "$IMAGE_FILE" ]; then
        echo "Error: '$IMAGE_FILE' does not exist or is not a regular file."
        continue
    fi

    # Check if the file is an image
    if ! file "$IMAGE_FILE" | grep -iqE 'image|jpeg|png|gif|bitmap|tiff'; then
        echo "Skipping '$IMAGE_FILE': File is not a supported image format."
        continue
    fi

    # Extract camera metadata using exiv2.
    # Replace spaces with underscores for the MAKE and MODEL.
    MAKE=$(exiv2 "$IMAGE_FILE" | grep 'Camera make' | awk -F': ' '{print $2}' | sed 's/ /_/g')
    MODEL=$(exiv2 "$IMAGE_FILE" | grep 'Camera model' | awk -F': ' '{print $2}' | sed 's/ /_/g')
    # Extract the date; note that some cameras may not fill in "Image timestamp"
    DATE=$(exiv2 -K Exif.Image.DateTime -Pv "$IMAGE_FILE" | sed 's/ /_/g' | sed 's/:/-/g')

    # some autels might return camera for make
    # if so, get it from tiff:Model
    if [ "$MAKE" = "Camera" ]; then
        echo "Generic MAKE; looking deeper"
        MAKE=$(exiv2 -P kt "$IMAGE_FILE" | grep -i 'tiff.make' | sed -E 's/^([^ ]+) +/\1 /' | awk -F' ' '{for(i=2; i<NF; i++) printf $i " "; print $(NF)}' | sed 's/ /_/g')
        echo "Make is now $MAKE"
    fi
    
    # Calculate the MD5 hash of the image
    HASH=$(md5sum "$IMAGE_FILE" | awk '{print $1}')

    # Construct new file name
    NEW_NAME="${MAKE}-${MODEL}-${DATE}-${HASH}.jpg"

    # Rename the file
    if mv "$IMAGE_FILE" "$NEW_NAME"; then
        echo "Renamed '$IMAGE_FILE' to '$NEW_NAME'"
    else
        echo "Error renaming '$IMAGE_FILE'"
    fi

done
