package ly.fea.system;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.FileNotFoundException;

/** يشارك الملفات المحفوظة مؤقتاً مع تطبيقات العرض (PDF والصور وWord) دون مكتبات إضافية. */
public class SharedFiles extends ContentProvider {
    static final String AUTH = "ly.fea.system.files";

    static Uri uriFor(File f) { return Uri.parse("content://" + AUTH + "/" + Uri.encode(f.getName())); }

    private File fileFor(Uri uri) throws FileNotFoundException {
        String name = uri.getLastPathSegment();
        if (name == null || name.contains("/") || name.contains("..")) throw new FileNotFoundException();
        File f = new File(new File(getContext().getCacheDir(), "shared"), name);
        if (!f.exists()) throw new FileNotFoundException(name);
        return f;
    }

    @Override public boolean onCreate() { return true; }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        return ParcelFileDescriptor.open(fileFor(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public String getType(Uri uri) {
        String name = uri.getLastPathSegment();
        String ext = name != null && name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase() : "";
        String t = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        return t != null ? t : "application/octet-stream";
    }

    @Override public Cursor query(Uri uri, String[] projection, String sel, String[] args, String order) {
        try {
            File f = fileFor(uri);
            MatrixCursor c = new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
            c.addRow(new Object[]{f.getName(), f.length()});
            return c;
        } catch (FileNotFoundException e) { return null; }
    }

    @Override public Uri insert(Uri uri, ContentValues v) { return null; }
    @Override public int delete(Uri uri, String s, String[] a) { return 0; }
    @Override public int update(Uri uri, ContentValues v, String s, String[] a) { return 0; }
}
