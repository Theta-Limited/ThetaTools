#!/bin/bash
# This script sorts JPG images from a given directory into subfolders based on their EXIF camera model.

# Check if the correct number of arguments is provided.
if [ "$#" -ne 2 ]; then
    echo "Usage: $0 <input_directory> <output_directory>"
    exit 1
fi

input_dir="$1"
output_dir="$2"

# Verify that the input directory exists.
if [ ! -d "$input_dir" ]; then
    echo "Error: Input directory '$input_dir' does not exist."
    exit 1
fi

# Create the output directory if it doesn't exist.
mkdir -p "$output_dir"

# Enable case-insensitive matching and allow for empty globs.
shopt -s nullglob nocaseglob

# Loop through all JPG/JPEG files in the input folder.
for file in "$input_dir"/*.jpg "$input_dir"/*.jpeg "$input_dir"/*.JPG "$input_dir"/*.JPEG; do
    # Skip if not a regular file.
    if [ ! -f "$file" ]; then
        continue
    fi

    # Extract the EXIF camera model using exiv2. The -g option filters for the model tag,
    # and -Pv outputs only the value.
    model=$(exiv2 -g Exif.Image.Model -Pv "$file" 2>/dev/null)
    
    # If no model is found, label it as Unknown.
    if [ -z "$model" ]; then
        model="Unknown"
    fi

    # Sanitize the model name to form a valid folder name.
    # Replace spaces with underscores; you can add more sanitization if needed.
    safe_model=$(echo "$model" | tr ' ' '_')

    # Create a subfolder in the output directory for the camera model.
    mkdir -p "$output_dir/$safe_model"

    # Move the file to the corresponding subfolder.
    mv "$file" "$output_dir/$safe_model/"
done

echo "Sorting complete."
