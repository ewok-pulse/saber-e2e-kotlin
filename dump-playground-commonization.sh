#!/bin/zsh

scriptsDirectory=$(pwd)
playground="$HOME/Documents/Projects/UserProjects/KmpSandbox"
cinterop_dumps="/tmp/cinterop-dumps-2"

./gradlew install

rm -rf "$cinterop_dumps"
rm -rf "$HOME/.konan/kotlin-native-prebuilt-macos-aarch64-2.4.255-SNAPSHOT/klib/commonized/2.4.255-SNAPSHOT"

cd "$playground"

./gradlew :composeApp:commonize --rerun-tasks --no-build-cache --no-configuration-cache

#grep -rE '\| unsafe \s*\| fun [a-zA-Z0-9_]+' "$cinterop_dumps" \
#  | sed -e 's/^[^:]*\://g' \
#  | sed -E 's/.*\| fun ([a-zA-Z0-9_]+).*/fun \1/g' \
#  | uniq \
#  | nl -w2 -s'. ' \
#  > "$cinterop_dumps"/unsafe-functions
#
#grep -rE '\| unsafe int \s*\| fun [a-zA-Z0-9_]+' "$cinterop_dumps" \
#  | sed -e 's/^[^:]*\://g' \
#  | sed -E 's/.*\| fun ([a-zA-Z0-9_]+).*/fun \1/g' \
#  | uniq \
#  | nl -w2 -s'. ' \
#  > "$cinterop_dumps"/unsafe-int-functions

python3 "$scriptsDirectory/"gather_allowed_platforms.py > "$cinterop_dumps/commonization-families"
python3 "$scriptsDirectory/"gather_type_configurations.py > "$cinterop_dumps/type-configurations"
