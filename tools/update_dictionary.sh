#!/bin/bash
# Rebuilds the dictionary bundled in app/src/main/assets/ from the EDRDG's latest JMdict_e and
# KANJIDIC2 files (CC BY-SA 4.0, https://www.edrdg.org/edrdg/licence.html). The licence requires
# apps to keep the data up to date, so run this before each release.
#
# Writes app/src/main/assets/dictionary-<JMdict's date>.db, removes the previous database from git,
# and points JMDICT_DATABASE_NAME in Constants.kt at the new one. A new name makes installed copies
# take the new file on update; setupGakuDatabasesAndFiles deletes the old one. Needs JAVA_HOME set
# to JDK 17 or 21, as for building.
set -euo pipefail

cd "$(dirname "$0")/.."
WORK=$PWD/app/build/dictionary
ASSETS=app/src/main/assets
CONSTANTS=app/src/main/java/ca/fuwafuwa/gaku/Constants.kt

mkdir -p "$WORK"
fetch() { # url file
    curl -fL --retry 3 -o "$WORK/$2.gz.part" "$1"
    mv "$WORK/$2.gz.part" "$WORK/$2.gz"
    # Without the DOCTYPE block: kxml2, which the generator parses with, reads past its end to the end
    # of the file, and Gaku keeps JMdict's entity names (n, v5r) rather than their expansions anyway
    gunzip -c "$WORK/$2.gz" | sed '/^<!DOCTYPE/,/^\]>/d' > "$WORK/$2"
}

fetch https://www.edrdg.org/pub/Nihongo/JMdict_e.gz JMdict_e.xml
fetch https://www.edrdg.org/kanjidic/kanjidic2.xml.gz kanjidic2.xml

DATE=$(grep -m1 -o 'JMdict created: [0-9-]*' "$WORK/JMdict_e.xml" | cut -d' ' -f3)
[ -n "$DATE" ] || { echo "No creation date in JMdict_e.xml" >&2; exit 1; }
NAME=dictionary-$DATE.db

rm -f "$WORK/$NAME"
bash ./gradlew testOfflineDebugUnitTest --tests 'ca.fuwafuwa.gaku.GenerateDictionary' --rerun \
    -Pgaku.jmdict="$WORK/JMdict_e.xml" -Pgaku.kanjidic2="$WORK/kanjidic2.xml" -Pgaku.dictionaryOut="$WORK/$NAME"
[ -s "$WORK/$NAME" ] || { echo "GenerateDictionary didn't write $NAME" >&2; exit 1; }

for old in "$ASSETS"/DB_KakuDict-*.db "$ASSETS"/dictionary-*.db; do
    [ -e "$old" ] && [ "$old" != "$ASSETS/$NAME" ] && git rm -q -f "$old"
done
cp "$WORK/$NAME" "$ASSETS/$NAME"
git add "$ASSETS/$NAME"
sed -i "s/^const val JMDICT_DATABASE_NAME = \".*\"/const val JMDICT_DATABASE_NAME = \"$NAME\"/" "$CONSTANTS"

echo "Wrote $ASSETS/$NAME ($(du -h "$ASSETS/$NAME" | cut -f1)) from JMdict_e and KANJIDIC2 of $DATE"
if command -v sqlite3 > /dev/null; then
    sqlite3 "$ASSETS/$NAME" "SELECT dictionary, count(*) FROM entryoptimized GROUP BY dictionary"
fi
