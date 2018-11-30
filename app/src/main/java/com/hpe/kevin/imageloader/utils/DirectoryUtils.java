package com.hpe.kevin.imageloader.utils;

import android.content.Context;
import android.os.Environment;
import android.util.Log;

public class DirectoryUtils {
    private static final String TAG = "DirectoryUtils";

    public static void getEnvironmentDirectories() {
        //:/system
        String rootDir = Environment.getRootDirectory().toString();
        Log.e(TAG, "Environment.getRootDirectory()=:" + rootDir);

        //:/data 用户数据目录
        String dataDir = Environment.getDataDirectory().toString();
        Log.e(TAG, "Environment.getDataDirectory()=:" + dataDir);

        //:/cache 下载缓存内容目录
        String cacheDir = Environment.getDownloadCacheDirectory().toString();
        Log.e(TAG, "Environment.getDownloadCacheDirectory()=:" + cacheDir);

        //:/mnt/sdcard或者/storage/emulated/0或者/storage/sdcard0 主要的外部存储目录
        String storageDir = Environment.getExternalStorageDirectory().toString();
        Log.e(TAG, "Environment.getExternalStorageDirectory()=:" + storageDir);

        //:/mnt/sdcard/Pictures或者/storage/emulated/0/Pictures或者/storage/sdcard0/Pictures
        String publicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES).toString();
        Log.e(TAG, "Environment.getExternalStoragePublicDirectory()=:" + publicDir);

        //获取SD卡是否存在:mounted
        String storageState = Environment.getExternalStorageState().toLowerCase();
        Log.e(TAG, "Environment.getExternalStorageState()=:" + storageState);

        //设备的外存是否是用内存模拟的，是则返回true。(API Level 11)
        boolean isEmulated = Environment.isExternalStorageEmulated();
        Log.e(TAG, "Environment.isExternalStorageEmulated()=:" + isEmulated);

        //设备的外存是否是可以拆卸的，比如SD卡，是则返回true。(API Level 9)
        boolean isRemovable = Environment.isExternalStorageRemovable();
        Log.e(TAG, "Environment.isExternalStorageRemovable()=:" + isRemovable);
    }

    public static void getApplicationDirectories(Context context) {

        //获取当前程序路径 应用在内存上的目录 :/data/data/com.mufeng.toolproject/files
        String filesDir = context.getFilesDir().toString();
        Log.e(TAG, "context.getFilesDir()=:" + filesDir);

        //应用的在内存上的缓存目录 :/data/data/com.mufeng.toolproject/cache
        String cacheDir = context.getCacheDir().toString();
        Log.e(TAG, "context.getCacheDir()=:" + cacheDir);

        //应用在外部存储上的目录 :/storage/emulated/0/Android/data/com.mufeng.toolproject/files/Movies
        String externalFilesDir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES).toString();
        Log.e(TAG, "context.getExternalFilesDir()=:" + externalFilesDir);

        //应用的在外部存储上的缓存目录 :/storage/emulated/0/Android/data/com.mufeng.toolproject/cache
        String externalCacheDir = context.getExternalCacheDir().toString();
        Log.e(TAG, "context.getExternalCacheDir()=:" + externalCacheDir);

        //获取该程序的安装包路径 :/data/app/com.mufeng.toolproject-3.apk
        String packageResourcePath = context.getPackageResourcePath();
        Log.e(TAG, "context.getPackageResourcePath()=:" + packageResourcePath);

        //获取程序默认数据库路径 :/data/data/com.mufeng.toolproject/databases/mufeng
        String databasePat = context.getDatabasePath("mufeng").toString();
        Log.e(TAG, "context.getDatabasePath(\"mufeng\")=:" + databasePat);


    }
}
