# Shell utility functions for mediaprovider developers.
# sudo apt-get install rlwrap to have a more fully featured sqlite CLI
set -x # enable debugging

function add-media-grant () {
# add a media grant to -p package for -id file_id

  function usage() {

    cat <<EOF

    Usage: $(basename "$BASH_SOURCE[0]") [-i] id value [-p] package value

    Adds a media grant for specified package and file._id

    Available Options:

    -i, --id         The files._id in mediaprovider database
    -p, --package    Package name i.e. com.android.package

EOF


  }

  # If we don't have any params, just print the documentation.
  if [ -z "$1" ]
  then

    usage

  else

      # parse incoming arguments
      while [[ "$#" -gt 0 ]]
      do case $1 in
        -i|--id) id="$2"
        shift;;
      -p|--package) packagename="$2"
        shift;;
      *) usage; return
      esac
      shift
    done

  echo "Adding media_grant for id=$id to package $packagename"

  uri='content\\://media/picker/0/com.android.providers.media.photopicker/media/'
  uriWithId="${uri}$id"

  if [ -z "$id" ] || [ -z "$packagename" ]
  then
    usage; return
  fi


  adb wait-for-device
  adb shell content call --method 'grant_media_read_for_package' \
    --uri 'content://media' \
    --extra 'uri':s:"$uriWithId" \
    --extra 'android.intent.extra.PACKAGE_NAME':s:"$packagename"

  fi
}

function sqlite3-pull () {
    adb root
    if [ -z "$1" ]
    then
        dir=$(pwd)
    else
        dir=$1
    fi
    package=$(get-package)

    if [ -f "$dir/external.db" ]; then
      rm "$dir/external.db"
    fi
    if [ -f "$dir/external.db-wal" ]; then
      rm "$dir/external.db-wal"
    fi

    adb pull /data/user/0/$package/databases/external.db $dir/external.db
    adb pull /data/user/0/$package/databases/external.db-wal "$dir/external.db-wal"

    sqlite3 $dir/external.db "drop trigger files_insert"
    sqlite3 $dir/external.db "drop trigger files_update"
    sqlite3 $dir/external.db "drop trigger files_delete"

    rlwrap sqlite3 $dir/external.db
}

function sqlite3-push () {
    adb root
    if [ -z "$1" ]
    then
        dir=$(pwd)
    else
        dir=$1
    fi
    package=$(get-package)

    adb push $dir/external.db /data/user/0/$package/databases/external.db
    adb push $dir/external.db-wal /data/user/0/$package/databases/external.db-wal

    sqlite3-trigger-upgrade
}

function sqlite3-trigger-upgrade () {
    package=$(get-package)

    # Doesn't actually upgrade the db because db version is hardcoded in code
    # It however triggers upgrade path
    check_string="/data/user/0/$package/databases/external.db \"pragma user_version\""
    version=$(adb shell sqlite3 $check_string)
    echo "Old version: $version"

    version=$((version+1))
    upgrade_string="/data/user/0/$package/databases/external.db \"pragma user_version=$version\""
    adb shell sqlite3 $upgrade_string

    version=$(adb shell sqlite3 $check_string)
    echo "New version: $version"

    adb shell am force-stop $package
}

function get-id-from-data () {
    adb root
    path="$1"
    package=$(get-package)
    dir="/data/user/0/$package/databases/external.db"
    clause="\"select _id from files where _data='$path';\""
    echo $clause
    adb shell sqlite3 $dir $clause
}

function get-data-from-id () {
    adb root
    _id="$1"
    package=$(get-package)
    dir="/data/user/0/$package/databases/external.db"
    clause="\"select _data from files where _id='$_id';\""
    echo $clause
    adb shell sqlite3 $dir $clause
}

function get-package() {
    if [ -z "$(adb shell pm list package com.android.providers.media.module)" ]
    then
        echo "com.google.android.providers.media.module"
    else
        echo "com.android.providers.media.module"
    fi
}

set +x  # disable debugging
