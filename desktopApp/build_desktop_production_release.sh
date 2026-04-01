#!/bin/sh

# Function to generate a shortened unique name
generate_shortened_name() {
  local base="$1"
  local extension="$2"
  local counter=1
  local new_name="${base:0:30}.$extension"  # Shorten the base name

  # Check if the new name already exists
  while [ -e "$new_name" ]; do
    new_name="${base:0:30}_$counter.$extension"  # Append a counter if the name already exists
    ((counter++))
  done

  echo "$new_name"
}

./gradlew clean createReleaseDistributable
mkdir proguard-jars
rm -r proguard-jars/*
cp build/compose/tmp/main-release/proguard/* proguard-jars
rm proguard-jars/skiko-awt-runtime-*.jar

cd proguard-jars
for file in *; do
  # Check if the file is a regular file
  if [ -f "$file" ]; then
    # Get the file extension
    extension="${file##*.}"

    # Get the base name without extension
    base_name="${file%.*}"

    # Generate a shortened unique name
    new_name=$(generate_shortened_name "$base_name" "$extension")

    # Rename the file
    mv "$file" "$new_name"

    echo "Renamed $file to $new_name"
  fi
done
cd ..

rm -r output
conveyor --file=conveyor_production.conf --cache-limit=20.0 make site
#conveyor --file=conveyor_production.conf --cache-limit=20.0 -Kapp.machines=linux.amd64 make debian-package
#conveyor --file=conveyor_production.conf --cache-limit=20.0 make windows-msix


