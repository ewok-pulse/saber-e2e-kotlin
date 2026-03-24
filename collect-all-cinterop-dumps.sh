#!/bin/zsh

#
# Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
# Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
#

libs_location="$HOME/.konan/kotlin-native-prebuilt-macos-aarch64-2.4.0-dev-539/klib/platform/"
dumps_location="/tmp/cinterop-dumps"

rm -rf "$dumps_location"
mkdir "$dumps_location"

platformsCount=$(ls "$libs_location" | wc -l)
((platformsCount=platformsCount))

platformIndex=0

for platform in $(ls "$libs_location"); do
  ((platformIndex++))
  echo "Dumping platform [$platformIndex/$platformsCount] $platform"

  mkdir "$dumps_location/$platform"
  libraryIndex=0

  for lib in $(ls "$libs_location/$platform"); do
    librariesCount=$(ls "$libs_location/$platform" | wc -l)
    ((librariesCount=librariesCount))

    ((libraryIndex++))
    echo "Dumping library [$platformIndex/$platformsCount][$libraryIndex/$librariesCount] $lib"
#    kotlin-native/dist/bin/klib dump-metadata "$libs_location/$platform/$lib" > "$dumps_location/$platform/$lib"
    kotlin-native/dist/bin/klib dump-integer-stats "$libs_location/$platform/$lib" > "$dumps_location/$platform/$lib"
  done
done

