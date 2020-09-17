# sudo apt-get install rlwrap to have a more fully featured sqlite CLI
set -x

function sqlite3-pull () {
    adb root
    if [ -z "$1" ]
    then
        dir=$(pwd)
    else
        dir=$1
    fi
    package=$(get-package)

    rm $dir/external.db
    rm $dir/external.db-wal

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

function get-package() {
    if [ -z "$(adb shell pm list package com.android.providers.media.module)" ]
    then
        echo "com.google.android.providers.media.module"
    else
        echo "com.android.providers.media.module"
    fi
}
