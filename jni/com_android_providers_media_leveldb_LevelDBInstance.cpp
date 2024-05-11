#include <jni.h>
#include <nativehelper/scoped_utf_chars.h>

#include "leveldb/db.h"

#ifndef _Included_com_android_providers_media_leveldb_LevelDBInstance
#define _Included_com_android_providers_media_leveldb_LevelDBInstance
#ifdef __cplusplus
extern "C" {
#endif
#undef com_android_providers_media_leveldb_LevelDBInstance_MAX_BULK_INSERT_ENTRIES
#define com_android_providers_media_leveldb_LevelDBInstance_MAX_BULK_INSERT_ENTRIES 100L

static std::string getStatusCode(leveldb::Status status) {
    if (status.ok()) {
        return "0";
    } else if (status.IsNotFound()) {
        return "1";
    } else if (status.IsInvalidArgument()) {
        return "2";
    } else {
        return "3";
    }
}

static jobject createLevelDBResult(JNIEnv* env, leveldb::Status status, std::string value) {
    // Create the object of the class LevelDBResult
    jclass levelDbResultClass = env->FindClass("com/android/providers/media/leveldb/LevelDBResult");
    jobject levelDbResultData = env->AllocObject(levelDbResultClass);

    // Get the UserData fields to be set
    jfieldID codeField = env->GetFieldID(levelDbResultClass, "mCode", "Ljava/lang/String;");
    jfieldID messageField =
            env->GetFieldID(levelDbResultClass, "mErrorMessage", "Ljava/lang/String;");
    jfieldID valueField = env->GetFieldID(levelDbResultClass, "mValue", "Ljava/lang/String;");

    std::string statusCode = getStatusCode(status);
    env->SetObjectField(levelDbResultData, codeField, env->NewStringUTF(statusCode.c_str()));
    env->SetObjectField(levelDbResultData, messageField,
                        env->NewStringUTF(status.ToString().c_str()));
    env->SetObjectField(levelDbResultData, valueField, env->NewStringUTF(value.c_str()));
    return levelDbResultData;
}

static leveldb::Status insertInLevelDB(JNIEnv* env, jobject obj, jlong leveldbptr,
                                       jobject leveldbentry) {
    jclass levelDbEntryClass = env->GetObjectClass(leveldbentry);
    jmethodID getKeyMethodId =
            env->GetMethodID(levelDbEntryClass, "getKey", "()Ljava/lang/String;");
    jmethodID getValueMethodId =
            env->GetMethodID(levelDbEntryClass, "getValue", "()Ljava/lang/String;");

    jstring key = (jstring)env->CallObjectMethod(leveldbentry, getKeyMethodId);
    jstring value = (jstring)env->CallObjectMethod(leveldbentry, getValueMethodId);
    ScopedUtfChars utf_chars_key(env, key);
    ScopedUtfChars utf_chars_value(env, value);
    leveldb::DB* leveldb = reinterpret_cast<leveldb::DB*>(leveldbptr);
    leveldb::Status status;
    status = leveldb->Put(leveldb::WriteOptions(), utf_chars_key.c_str(), utf_chars_value.c_str());
    return status;
}

static jobject insert(JNIEnv* env, jobject obj, jlong leveldbptr, jobject leveldbentry) {
    return createLevelDBResult(env, insertInLevelDB(env, obj, leveldbptr, leveldbentry), "");
}

/*
 * Class:     com_android_providers_media_leveldb_LevelDBInstance
 * Method:    nativeCreateInstance
 * Signature: (Ljava/lang/String;)J
 */
JNIEXPORT jlong JNICALL
            Java_com_android_providers_media_leveldb_LevelDBInstance_nativeCreateInstance(
                JNIEnv* env, jclass leveldbInstanceClass, jstring path) {
    ScopedUtfChars utf_chars_path(env, path);
    leveldb::Options options;
    options.create_if_missing = true;
    leveldb::DB* leveldb;
    leveldb::Status status = leveldb::DB::Open(options, utf_chars_path.c_str(), &leveldb);
    if (status.ok()) {
        return reinterpret_cast<jlong>(leveldb);
    } else {
        long val = 0;
        return (jlong)val;
    }
}

/*
 * Class:     com_android_providers_media_leveldb_LevelDBInstance
 * Method:    nativeQuery
 * Signature: (JLjava/lang/String;)Lcom/android/providers/media/leveldb/LevelDBResult;
 */
JNIEXPORT jobject JNICALL Java_com_android_providers_media_leveldb_LevelDBInstance_nativeQuery(
        JNIEnv* env, jobject obj, jlong leveldbptr, jstring path) {
    ScopedUtfChars utf_chars_path(env, path);
    leveldb::DB* leveldb = reinterpret_cast<leveldb::DB*>(leveldbptr);
    leveldb::Status status;
    std::string value = "";
    status = leveldb->Get(leveldb::ReadOptions(), utf_chars_path.c_str(), &value);
    return createLevelDBResult(env, status, value);
}

/*
 * Class:     com_android_providers_media_leveldb_LevelDBInstance
 * Method:    nativeInsert
 * Signature: (JLcom/android/providers/media/leveldb/LevelDBEntry;)
 *                   Lcom/android/providers/media/leveldb/LevelDBResult;
 */
JNIEXPORT jobject JNICALL Java_com_android_providers_media_leveldb_LevelDBInstance_nativeInsert(
        JNIEnv* env, jobject obj, jlong leveldbptr, jobject leveldbentry) {
    return insert(env, obj, leveldbptr, leveldbentry);
}

/*
 * Class:     com_android_providers_media_leveldb_LevelDBInstance
 * Method:    nativeBulkInsert
 * Signature: (JLjava/util/List;)Lcom/android/providers/media/leveldb/LevelDBResult;
 */
JNIEXPORT jobject JNICALL Java_com_android_providers_media_leveldb_LevelDBInstance_nativeBulkInsert(
        JNIEnv* env, jobject obj, jlong leveldbptr, jobject entries) {
    // Get the class of the list
    jclass listClass = env->GetObjectClass(entries);

    // Get the iterator method ID
    jmethodID iteratorMethod = env->GetMethodID(listClass, "iterator", "()Ljava/util/Iterator;");

    // Get the iterator object
    jobject iterator = env->CallObjectMethod(entries, iteratorMethod);

    // Get the iterator class
    jclass iteratorClass = env->GetObjectClass(iterator);

    // Get the hasNext and next method IDs
    jmethodID hasNextMethod = env->GetMethodID(iteratorClass, "hasNext", "()Z");
    jmethodID nextMethod = env->GetMethodID(iteratorClass, "next", "()Ljava/lang/Object;");

    leveldb::Status status;

    // Iterate through the list
    while (env->CallBooleanMethod(iterator, hasNextMethod)) {
        jobject jLevelDBEntryObject = env->CallObjectMethod(iterator, nextMethod);
        leveldb::Status status = insertInLevelDB(env, obj, leveldbptr, jLevelDBEntryObject);
        if (!status.ok()) {
            break;
        }
    }

    return createLevelDBResult(env, status, "");
}

/*
 * Class:     com_android_providers_media_leveldb_LevelDBInstance
 * Method:    nativeDelete
 * Signature: (JLjava/lang/String;)Lcom/android/providers/media/leveldb/LevelDBResult;
 */
JNIEXPORT jobject JNICALL Java_com_android_providers_media_leveldb_LevelDBInstance_nativeDelete(
        JNIEnv* env, jobject obj, jlong leveldbptr, jstring key) {
    ScopedUtfChars utf_chars_key(env, key);
    leveldb::DB* leveldb = reinterpret_cast<leveldb::DB*>(leveldbptr);
    leveldb::Status status;
    status = leveldb->Delete(leveldb::WriteOptions(), utf_chars_key.c_str());
    return createLevelDBResult(env, status, "");
}

#ifdef __cplusplus
}
#endif
#endif
