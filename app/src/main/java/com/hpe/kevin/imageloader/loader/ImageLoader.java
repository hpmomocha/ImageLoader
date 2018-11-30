package com.hpe.kevin.imageloader.loader;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.StatFs;
import android.support.annotation.NonNull;
import android.util.Log;
import android.util.LruCache;
import android.widget.ImageView;

import com.hpe.kevin.imageloader.R;
import com.hpe.kevin.imageloader.utils.MyUtils;
import com.jakewharton.disklrucache.DiskLruCache;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class ImageLoader {
    private static final String TAG = "ImageLoader";

    public static final int MESSAGE_POST_RESULT = 1;

    private static final int CPU_COUNT = Runtime.getRuntime().availableProcessors();

    private static final int CORE_POOL_SIZE = CPU_COUNT + 1;
    private static final int MAXIMUM_POOL_SIZE = CPU_COUNT * 2 + 1;
    private static final long KEEP_ALIVE = 10L;

    private static final int TAG_KEY_URI = R.id.imageloader_uri;
    private static final long DISK_CACHE_SIZE = 1024 * 1024 * 50; // 50MB
    private static final int IO_BUFFER_SIZE = 8 * 1024;
    private static final int DISK_CACHE_INDEX = 0;
    private boolean mIsDiskLruCacheCreated = false;

    // 线程工厂，用来创建线程池中的线程。
    private static final ThreadFactory sThreadFactory = new ThreadFactory() {
        private final AtomicInteger mCount = new AtomicInteger(1);

        @Override
        public Thread newThread(@NonNull Runnable r) {
            return new Thread(r, "ImageLoader#" + mCount.getAndIncrement());
        }
    };

    // 线程池
    public static final Executor THREAD_POOL_EXECUTOR = new ThreadPoolExecutor(
            CORE_POOL_SIZE, MAXIMUM_POOL_SIZE,
            KEEP_ALIVE, TimeUnit.SECONDS,
            new LinkedBlockingQueue<Runnable>(), sThreadFactory);

    private Handler mMainHandler = new Handler(Looper.getMainLooper()) {
        /**
         * Subclasses must implement this to receive messages.
         *
         * @param msg
         */
        @Override
        public void handleMessage(Message msg) {
            LoaderResult result = (LoaderResult) msg.obj;
            ImageView imageView = result.imageView;
            imageView.setImageBitmap(result.bitmap);
            String uri = (String) imageView.getTag(TAG_KEY_URI);
            if (uri.equals(result.uri)) {
                imageView.setImageBitmap(result.bitmap);
            } else {
                Log.w(TAG, "set image bitmap, but url has changed, ignored!");
            }
        }
    };

    private Context mContext;
    private ImageResizer mImageResizer = new ImageResizer();
    private LruCache<String, Bitmap> mMemoryCache;
    private DiskLruCache mDiskLruCache;

    private ImageLoader(Context context) {
        mContext = context.getApplicationContext();
        // 取得最大内存
        int maxMemory = (int) (Runtime.getRuntime().maxMemory() / 1024);
        // 缓存为最大内存的1/8
        int cacheSize = maxMemory / 8;
        mMemoryCache = new LruCache<String, Bitmap>(cacheSize) {
            /**
             * Returns the size of the entry for {@code key} and {@code value} in
             * user-defined units.  The default implementation returns 1 so that size
             * is the number of entries and max size is the maximum number of entries.
             *
             * <p>An entry's size must not change while it is in the cache.
             *
             * @param key
             * @param bitmap
             */
            @Override
            protected int sizeOf(String key, Bitmap bitmap) {
                // 返回bitmap的大小
                return bitmap.getRowBytes() * bitmap.getHeight() / 1024;
            }
        };

        // 磁盘缓存目录
        File diskCacheDir = getDiskCacheDir(mContext, "bitmap");
        if (!diskCacheDir.exists()) {
            // 创建磁盘缓存目录
            diskCacheDir.mkdirs();
        }

        // 缓存目录的可用空间大于50MB
        if (getUsableSpace(diskCacheDir) > DISK_CACHE_SIZE) {
            try {
                // 创建DiskLruCache
                // appVersion表示版本号。当版本号变化时DiskLruCache会清空之前所有的缓存文件，但是实际上不一定会。
                // valueCount表示单个节点所对应的个数。
                mDiskLruCache = DiskLruCache.open(diskCacheDir, 1, 1, DISK_CACHE_SIZE);
                mIsDiskLruCacheCreated = true;
            } catch (IOException e) {
                Log.e(TAG, "创建磁盘缓存失败！");
            }
        }
    }

    /**
     * build a new instance of ImageLoader
     *
     * @param context
     * @return
     */
    public static ImageLoader build(Context context) {
        return new ImageLoader(context);
    }

    /**
     * 将bitmap缓存到内存中
     * @param key
     * @param bitmap
     */
    private void addBitmapToMemoryCache(String key, Bitmap bitmap) {
        if (getBitmapFromMemCache(key) == null) {
            mMemoryCache.put(key, bitmap);
        }
    }

    /**
     * 根据key从内存缓存中取得bitmap
     * @param key
     * @return
     */
    private Bitmap getBitmapFromMemCache(String key) {
        return mMemoryCache.get(key);
    }

    /**
     * load bitmap from memory cache or disk cache or network async, then bind imageView and bitmap.
     * NOTE THAT: should run in UI Thread
     * @param uri
     * @param imageView
     */
    public void bindBitmap(final String uri, final ImageView imageView) {
        bindBitmap(uri, imageView, 0, 0);
    }

    public void bindBitmap(final String uri, final ImageView imageView, final int reqWidth, final int reqHeight) {
        // TAG_KEY_URI：必须是唯一的，否则会出现以下错误：
        // The key must be an application-specific resource id.
        // 那么如何保证这种唯一性呢？
        // 在res/values/下，新建ids.xml文件
        // <resources>
        //     <item type="id" name="tag_first" />
        //     <item type="id" name="tag_second" />
        //     <item type="id" name="tag_third" />
        // </resources>
        // 然后使用时如下设置：
        // if (convertView==null) {
        //	convertView=new ViewHolder();
        //	convertView.setTag(R.id.tag_first,viewholder1);
        //	convertView.setTag(R.id.tag_second,viewholder2);
        //	convertView.setTag(R.id.tag_third,viewholder3);
        //} else{
        //	convertView = (ViewHolder) convertView.getTag(R.id.tag_first);
        //	convertView = (ViewHolder) convertView.getTag(R.id.tag_second);
        //	convertView = (ViewHolder) convertView.getTag(R.id.tag_third);
        //}
        imageView.setTag(TAG_KEY_URI, uri);

        // 先尝试从内存的缓存中取得bitmap
        Bitmap bitmap = loadBitmapFromMemCache(uri);
        if (bitmap != null) {
            imageView.setImageBitmap(bitmap);
            return;
        }

        // 如果内存缓存中不存在，则新起线程加载bitmap
        Runnable loadBitmapTask = new Runnable() {
            @Override
            public void run() {
                Log.d(TAG, "I AM WORKING IN RUNNABLE!");
                Bitmap bitmap = loadBitmap(uri, reqWidth, reqHeight);
                if (bitmap != null) {
                    LoaderResult result = new LoaderResult(imageView, uri, bitmap);
                    mMainHandler.obtainMessage(MESSAGE_POST_RESULT, result).sendToTarget();
                }
            }
        };
        THREAD_POOL_EXECUTOR.execute(loadBitmapTask);
    }

    /**
     * load bitmap from memory cache or disk cache or network
     *
     * @param uri http url
     * @param reqWidth the width ImageView desired
     * @param reqHeight the height ImageView desired
     * @return bitmap, maybe null
     */
    public Bitmap loadBitmap(String uri, int reqWidth, int reqHeight) {
        // load bitmap from memory cache
        Bitmap bitmap = loadBitmapFromMemCache(uri);
        if (bitmap != null) {
            Log.d(TAG, "getBitmapFromMemCache, uri:" + uri);
            return bitmap;
        }
        // load bitmap from disk cache
        try {
            bitmap = loadBitmapFromDiskCache(uri, reqWidth, reqHeight);
            if (bitmap != null) {
                Log.d(TAG, "loadBitmapFromDisk,url:" + uri);
                return bitmap;
            }
        } catch (IOException e) {
            Log.e(TAG, "Error in loadBitmapFromDisk: " + e);
        }

        // if disk cache is enabled, download bitmap from network and output bitmap into disk cache
        try {
            bitmap = downloadBitmapFromHttp(uri, reqWidth, reqHeight);
        } catch (IOException e) {
            Log.e(TAG, "Error in downloadBitmapFromHttp: " + e);
        }

        // download bitmap from network directly
        if (bitmap == null && !mIsDiskLruCacheCreated) {
            Log.w(TAG, "encounter error, DiskLruCache is not created.");
            bitmap = downloadBitmapFromUrl(uri);
        }

        return bitmap;
    }

    /**
     * 从磁盘缓存中加载bitmap
     * @param url
     * @param reqWidth
     * @param reqHeight
     * @return
     * @throws IOException
     */
    private Bitmap loadBitmapFromDiskCache(
            String url,
            int reqWidth,
            int reqHeight) throws IOException {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Log.w(TAG, "load bitmap from UI Thread, it's not recommended!");
        }
        if (mDiskLruCache == null) {
            return null;
        }

        Bitmap bitmap = null;
        String key = hashKeyFormUrl(url);
        // 通过get方法得到snapShot对象
        DiskLruCache.Snapshot snapshot = mDiskLruCache.get(key);
        if (snapshot != null) {
            // Snapshot可以得到缓存的文件输入流
            FileInputStream fileInputStream = (FileInputStream) snapshot.getInputStream(DISK_CACHE_INDEX);
            FileDescriptor fileDescriptor = fileInputStream.getFD();
            bitmap = mImageResizer.decodeSampledBitmapFromDescriptor(fileDescriptor, reqWidth, reqHeight);
            if (bitmap != null) {
                // 从磁盘中加载进来后放到内存中
                addBitmapToMemoryCache(key, bitmap);
            }
        }

        return bitmap;
    }

    /**
     * 从网络下载图片，通过文件输出流写到文件系统
     * @param urlString
     * @param outputStream
     * @return
     */
    public boolean downloadUrlToStream(String urlString, OutputStream outputStream) {
        HttpURLConnection urlConnection = null;
        BufferedOutputStream out = null;
        BufferedInputStream in = null;
        try {
            urlConnection = (HttpURLConnection) new URL(urlString).openConnection();
            in = new BufferedInputStream(urlConnection.getInputStream(), IO_BUFFER_SIZE);
            out = new BufferedOutputStream(outputStream, IO_BUFFER_SIZE);
            int b = -1;
            while ( (b = in.read()) != -1) {
                out.write(b);
            }
            return true;
        } catch (IOException e) {
            Log.e(TAG, "downloadBitmap failed." + e);
        } finally {
            if (urlConnection != null) {
                urlConnection.disconnect();
            }
            MyUtils.close(out);
            MyUtils.close(in);
        }

        return false;
    }

    /**
     * download Bitmap from url, and then output the bitmap into disk cache
     * @param url
     * @param reqWidth
     * @param reqHeight
     * @return
     */
    private Bitmap downloadBitmapFromHttp(String url, int reqWidth, int reqHeight) throws IOException {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw new RuntimeException("can not visit network from UI Thread.");
        }
        if (mDiskLruCache == null) {
            return null;
        }

        String key = hashKeyFormUrl(url);
        // DiskLruCache的缓存添加通过Editor完成，Editor表示一个缓存对象的编辑对象。
        // 对于key而言，如果当前不存在其他Editor对象，那么edit()就会返回一个新的Editor对象，通过它可以得到一个输出流。
        DiskLruCache.Editor editor = mDiskLruCache.edit(key);
        if (editor != null) {
            // 由于前面在DiskLruCache的open方法中设置了一个节点只能有一个数据，因此DISK_CACHE_INDEX常量直接设为0。
            OutputStream outputStream = editor.newOutputStream(DISK_CACHE_INDEX);
            if (downloadUrlToStream(url, outputStream)) {
                // downloadUrlToStream方法并没有真正地将图片写入文件系统，还必须通过Editor的commit()来提交写入操作。
                editor.commit();
            } else {
                // 如果图片下载过程发生异常，可以通过Editor的abort()来回退整个操作。
                editor.abort();
            }
            mDiskLruCache.flush();
        }
        return loadBitmapFromDiskCache(url, reqWidth, reqHeight);
    }

    /**
     * download bitmap from network
     * @param urlString
     * @return
     */
    private Bitmap downloadBitmapFromUrl(String urlString) {
        HttpURLConnection urlConnection = null;
        BufferedInputStream in = null;
        Bitmap bitmap = null;
        try {
            urlConnection = (HttpURLConnection) new URL(urlString).openConnection();
            in = new BufferedInputStream(urlConnection.getInputStream(), IO_BUFFER_SIZE);
            bitmap = BitmapFactory.decodeStream(in);
        } catch (IOException e) {
            Log.e(TAG, "Error in downloadBitmap: " + e);
        } finally {
            if (urlConnection != null) {
                urlConnection.disconnect();
            }
            MyUtils.close(in);
        }
        return bitmap;
    }

    /**
     * load bitmap from memory cache
     *
     * @param url
     * @return
     */
    private Bitmap loadBitmapFromMemCache(String url) {
        final String key = hashKeyFormUrl(url);
        return getBitmapFromMemCache(key);
    }

    /**
     * 根据URL生成缓存key
     * @param url
     * @return
     */
    private String hashKeyFormUrl(String url) {
        String cacheKey;
        try {
            final MessageDigest mDigest = MessageDigest.getInstance("MD5");
            mDigest.update(url.getBytes());
            cacheKey = bytesToHexString(mDigest.digest());
        } catch (NoSuchAlgorithmException e) {
            cacheKey = String.valueOf(url.hashCode());
        }
        return cacheKey;
    }

    /**
     * 将byte数组转化成十六进制字符串
     * @param bytes
     * @return
     */
    private String bytesToHexString(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < bytes.length; i++) {
            // 0xFF是int的十六进制，int型有32位，0xFF的高24位为0。
            // 而byte要转化为int的时候，高24位会补1，通过和0xFF做位与操作，截取byte转化为int后的低8位。
            String hex = Integer.toHexString(0xFF & bytes[i]);
            if (hex.length() == 1) {
                sb.append("0");
            }
            sb.append(hex);
        }
        return sb.toString();
    }

    /**
     * 取得磁盘缓存目录
     * @param context
     * @param uniqueName
     * @return
     */
    public File getDiskCacheDir(Context context, String uniqueName) {
        // 获取SD卡是否存在:mounted
        boolean externalStorageAvailable = Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED);
        final String cachePath;
        if (externalStorageAvailable) {
            // 应用在外部存储上的缓存目录
            cachePath = context.getExternalCacheDir().getPath();
            Log.d(TAG, "外部存储上的缓存目录:" + cachePath);
        } else {
            // 应用在内部存储上的缓存目录
            cachePath = context.getCacheDir().getPath();
            Log.d(TAG, "内部存储上的缓存目录:" + cachePath);
        }

        return new File(cachePath + File.separator + uniqueName);
    }

    /**
     * 取得指定目录的可用空间
     * @param path
     * @return
     */
    private long getUsableSpace(File path) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.GINGERBREAD) {
            return path.getUsableSpace();
        }
        final StatFs stats = new StatFs(path.getPath());
        return stats.getBlockSizeLong() * stats.getAvailableBlocksLong();
    }

    private static class LoaderResult {
        public ImageView imageView;
        public String uri;
        public Bitmap bitmap;

        public LoaderResult(ImageView imageView, String uri, Bitmap bitmap) {
            this.imageView = imageView;
            this.uri = uri;
            this.bitmap = bitmap;
        }
    }
}
