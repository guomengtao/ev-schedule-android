package com.application.watch.classschedule;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * 升级包只读 Provider（无 androidx，FileProvider 的极简替代，~40 行）。
 *
 * 用途：下载到 getExternalFilesDir 的 APK 通过 content:// URI 交给系统安装器
 * （ACTION_INSTALL_PACKAGE + application/vnd.android.package-archive）。
 * Android 7+ 禁 file:// URI，必须走 ContentProvider；
 * authorities = 包名 + ".updatefiles"（build.sh 按变体注入，双变体各自唯一）。
 * 仅映射 getExternalFilesDir 下的单层文件名，外部无法穿越目录。
 */
public final class MiniFileProvider extends ContentProvider {

    @Override public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        String name = uri.getLastPathSegment();
        // 只允许纯文件名，防路径穿越
        if (name == null || name.contains("/") || name.contains("\\") || name.contains("..")) {
            throw new FileNotFoundException("非法文件名");
        }
        File dir = getContext().getExternalFilesDir(null);
        File f = new File(dir, name);
        if (!f.exists()) {
            throw new FileNotFoundException(name);
        }
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public Cursor query(Uri uri, String[] p, String s, String[] a, String o) {
        return null;
    }

    @Override public String getType(Uri uri) {
        return "application/vnd.android.package-archive";
    }

    @Override public Uri insert(Uri uri, ContentValues v) {
        return null;
    }

    @Override public int delete(Uri uri, String s, String[] a) {
        return 0;
    }

    @Override public int update(Uri uri, ContentValues v, String s, String[] a) {
        return 0;
    }
}
