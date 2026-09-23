/*
 *                       Copyright (C) of Avery
 *
 *                              _ooOoo_
 *                             o8888888o
 *                             88" . "88
 *                             (| -_- |)
 *                             O\  =  /O
 *                          ____/`- -'\____
 *                        .'  \\|     |//  `.
 *                       /  \\|||  :  |||//  \
 *                      /  _||||| -:- |||||-  \
 *                      |   | \\\  -  /// |   |
 *                      | \_|  ''\- -/''  |   |
 *                      \  .-\__  `-`  ___/-. /
 *                    ___`. .' /- -.- -\  `. . __
 *                 ."" '<  `.___\_<|>_/___.'  >'"".
 *                | | :  `- \`.;`\ _ /`;.`/ - ` : | |
 *                \  \ `-.   \_ __\ /__ _/   .-` /  /
 *           ======`-.____`-.___\_____/___.-`____.-'======
 *                              `=- -='
 *           ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
 *              Buddha bless, there will never be bug!!!
 */

package com.github.tvbox.osc.subtitle;

import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;

import com.github.tvbox.osc.subtitle.exception.FatalParsingException;
import com.github.tvbox.osc.subtitle.format.FormatASS;
import com.github.tvbox.osc.subtitle.format.FormatSRT;
import com.github.tvbox.osc.subtitle.format.FormatSTL;
import com.github.tvbox.osc.subtitle.format.TimedTextFileFormat;
import com.github.tvbox.osc.subtitle.model.TimedTextObject;
import com.github.tvbox.osc.subtitle.runtime.AppTaskExecutor;
import com.github.tvbox.osc.util.FileUtils;
import com.github.tvbox.osc.util.UnicodeReader;
import com.lzy.okgo.OkGo;

import org.apache.commons.io.input.ReaderInputStream;
import org.mozilla.universalchardet.UniversalDetector;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.net.URLDecoder;
import java.nio.charset.Charset;

import okhttp3.Response;

/**
 * @author AveryZhong.
 */

public class SubtitleLoader {
    private static final String TAG = SubtitleLoader.class.getSimpleName();
    /** 编码探测失败时的兜底编码 */
    private static final String DEFAULT_CHARSET = "UTF-8";

    private SubtitleLoader() {
        throw new AssertionError("No instance for you.");
    }

    public static void loadSubtitle(final String path, final Callback callback) {
        if (TextUtils.isEmpty(path)) {
            return;
        }
        if (path.startsWith("http://")
                || path.startsWith("https://")) {
            loadFromRemoteAsync(path, callback);
        } else {
            loadFromLocalAsync(path, callback);
        }
    }

    private static void loadFromRemoteAsync(final String remoteSubtitlePath,
                                            final Callback callback) {
        AppTaskExecutor.deskIO().execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final SubtitleLoadSuccessResult subtitleLoadSuccessResult = loadFromRemote(remoteSubtitlePath);
                    if (callback != null) {
                        AppTaskExecutor.mainThread().execute(new Runnable() {
                            @Override
                            public void run() {
                                callback.onSuccess(subtitleLoadSuccessResult);
                            }
                        });
                    }

                } catch (final Exception e) {
                    e.printStackTrace();
                    if (callback != null) {
                        AppTaskExecutor.mainThread().execute(new Runnable() {
                            @Override
                            public void run() {
                                callback.onError(e);
                            }
                        });
                    }

                }
            }
        });
    }

    private static void loadFromLocalAsync(final String localSubtitlePath,
                                           final Callback callback) {
        AppTaskExecutor.deskIO().execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final SubtitleLoadSuccessResult subtitleLoadSuccessResult = loadFromLocal(localSubtitlePath);
                    if (subtitleLoadSuccessResult == null) {
                        // 文件不存在或读取失败：走 onError，避免上层拿到 null 后静默失败
                        if (callback != null) {
                            AppTaskExecutor.mainThread().execute(new Runnable() {
                                @Override
                                public void run() {
                                    callback.onError(new IOException("无法读取字幕文件：" + localSubtitlePath));
                                }
                            });
                        }
                        return;
                    }
                    if (callback != null) {
                        AppTaskExecutor.mainThread().execute(new Runnable() {
                            @Override
                            public void run() {
                                callback.onSuccess(subtitleLoadSuccessResult);
                            }
                        });
                    }

                } catch (final Exception e) {
                    e.printStackTrace();
                    if (callback != null) {
                        AppTaskExecutor.mainThread().execute(new Runnable() {
                            @Override
                            public void run() {
                                callback.onError(e);
                            }
                        });
                    }

                }
            }
        });
    }

    public SubtitleLoadSuccessResult loadSubtitle(String path) {
        if (TextUtils.isEmpty(path)) {
            return null;
        }
        try {
            if (path.startsWith("http://")
                    || path.startsWith("https://")) {
                return loadFromRemote(path);
            } else {
                return loadFromLocal(path);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    private static SubtitleLoadSuccessResult loadFromRemote(final String remoteSubtitlePath)
            throws IOException, FatalParsingException, Exception {
        Log.d(TAG, "parseRemote: remoteSubtitlePath = " + remoteSubtitlePath);
        String referer = "";
        if (remoteSubtitlePath.contains("alicloud") || remoteSubtitlePath.contains("aliyundrive")) {
            referer = "https://www.aliyundrive.com/";
        } else if (remoteSubtitlePath.contains("assrt.net")) {
            referer = "https://secure.assrt.net/";
        }
        String ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/94.0.4606.54 Safari/537.36";
        Response response = OkGo.<String>get(remoteSubtitlePath)
                .headers("Referer", referer)
                .headers("User-Agent", ua)
                .execute();
        byte[] bytes = response.body().bytes();
        String encoding = detectCharset(bytes);
        String content = new String(bytes, encoding);
        InputStream is = new ByteArrayInputStream(content.getBytes());
        String filename = "";
        String contentDispostion = response.header("content-disposition", "");
        String[] cd = contentDispostion.split(";");
        if (cd.length > 1) {
            String filenameInfo = cd[1];
            filenameInfo = filenameInfo.trim();
            if (filenameInfo.startsWith("filename=")) {
                filename = filenameInfo.replace("filename=", "");
                filename = filename.replace("\"", "");
            } else if (filenameInfo.startsWith("filename*=")) {
                filename = filenameInfo.substring(filenameInfo.lastIndexOf("''")+2);
            }
            filename = filename.trim();
            filename = URLDecoder.decode(filename);
        }
        String filePath = filename;
        if (filename == null || filename.length() < 1) {
            Uri uri = Uri.parse(remoteSubtitlePath);
            filePath = uri.getPath();
        }
        SubtitleLoadSuccessResult subtitleLoadSuccessResult = new SubtitleLoadSuccessResult();
        subtitleLoadSuccessResult.timedTextObject = loadAndParse(is, filePath);
        subtitleLoadSuccessResult.fileName = filename;
        subtitleLoadSuccessResult.content = content;
        subtitleLoadSuccessResult.subtitlePath = remoteSubtitlePath;
        return subtitleLoadSuccessResult;
    }

    private static SubtitleLoadSuccessResult loadFromLocal(final String localSubtitlePath)
            throws IOException, FatalParsingException {
        Log.d(TAG, "parseLocal: localSubtitlePath = " + localSubtitlePath);
        File file = new File(localSubtitlePath);
        if (!file.exists() || !file.canRead()) {
            Log.d(TAG, "parseLocal: file not exists or unreadable = " + localSubtitlePath);
            return null;
        }
        byte[] bytes = readAllBytes(file);
        if (bytes == null || bytes.length == 0) {
            Log.d(TAG, "parseLocal: read empty = " + localSubtitlePath);
            return null;
        }
        String encoding = detectCharset(bytes);
        String content = new String(bytes, encoding);
        InputStream is = new ByteArrayInputStream(content.getBytes());
        String filePath = file.getPath();
        SubtitleLoadSuccessResult subtitleLoadSuccessResult = new SubtitleLoadSuccessResult();
        subtitleLoadSuccessResult.timedTextObject = loadAndParse(is, filePath);
        if (subtitleLoadSuccessResult.timedTextObject == null) {
            Log.d(TAG, "parseLocal: parse failed = " + localSubtitlePath);
            return null;
        }
        String fileName = filePath.substring(filePath.lastIndexOf("/") + 1);
        subtitleLoadSuccessResult.fileName = fileName;
        subtitleLoadSuccessResult.subtitlePath = localSubtitlePath;
        return subtitleLoadSuccessResult;
    }

    /**
     * 编码探测：探测失败时兜底 UTF-8，避免 new String(bytes, null) 直接 NPE
     */
    private static String detectCharset(byte[] bytes) {
        try {
            UniversalDetector detector = new UniversalDetector(null);
            detector.handleData(bytes, 0, bytes.length);
            detector.dataEnd();
            String encoding = detector.getDetectedCharset();
            return TextUtils.isEmpty(encoding) ? DEFAULT_CHARSET : encoding;
        } catch (Exception e) {
            return DEFAULT_CHARSET;
        }
    }

    /**
     * 完整读取文件：不使用 available()（大文件会截断），按流长度循环读满
     */
    private static byte[] readAllBytes(File file) {
        InputStream in = null;
        try {
            in = new FileInputStream(file);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } catch (IOException e) {
            Log.e(TAG, "readAllBytes: " + e.getMessage());
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignore) { }
            }
        }
    }

    private static TimedTextObject loadAndParse(final InputStream is, final String filePath)
            throws IOException, FatalParsingException {
        String fileName = filePath.substring(filePath.lastIndexOf("/") + 1);
        String ext = "";
        if (fileName.lastIndexOf(".") > 0) {
            ext = fileName.substring(fileName.lastIndexOf("."));
        }
        Log.d(TAG, "parse: name = " + fileName + ", ext = " + ext);
        // 先把流读成字节，兜底重试时每次都开全新流，避免上一次解析吃掉流导致后续必然失败
        byte[] raw = toBytes(is);
        TimedTextFileFormat[] arr = {new FormatSRT(), new FormatASS(), new FormatSTL(), new FormatSTL()};
        if (".srt".equalsIgnoreCase(ext)) {
            return parseOne(arr[0], fileName, raw);
        } else if (".ass".equalsIgnoreCase(ext)) {
            return parseOne(arr[1], fileName, raw);
        } else if (".stl".equalsIgnoreCase(ext) || ".ttml".equalsIgnoreCase(ext)) {
            return parseOne(arr[2], fileName, raw);
        }
        for (TimedTextFileFormat oneFormat : arr) {
            try {
                TimedTextObject obj = parseOne(oneFormat, fileName, raw);
                if (obj != null) return obj;
            } catch (Exception e) {
                Log.d(TAG, "parse retry: " + e.getMessage());
            }
        }
        return null;
    }

    private static TimedTextObject parseOne(TimedTextFileFormat format, String fileName, byte[] raw)
            throws IOException, FatalParsingException {
        Reader reader = new UnicodeReader(new ByteArrayInputStream(raw)); //处理有BOM头的utf8
        return format.parseFile(fileName, new ReaderInputStream(reader, Charset.forName(DEFAULT_CHARSET)));
    }

    private static byte[] toBytes(InputStream is) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    public interface Callback {
        void onSuccess(SubtitleLoadSuccessResult SubtitleLoadSuccessResult);

        void onError(Exception exception);
    }
}
