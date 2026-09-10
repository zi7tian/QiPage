package local.readapp.test;

import android.content.*;
import android.database.*;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract.Document;
import android.provider.OpenableColumns;
import java.io.*;

/** Deliberately non-AOSP authority and generic MIME reproduces OEM picker contracts. */
public final class FixtureProvider extends ContentProvider {
    public boolean onCreate() { return true; }
    public String getType(Uri uri) { return "application/octet-stream"; }
    private String name(Uri uri) {
        String n=uri.getLastPathSegment();
        if(n==null || !n.matches("[a-zA-Z0-9.-]+\\.(txt|epub|ttf|png)"))throw new IllegalArgumentException("Generated book fixtures only");
        return n;
    }
    public Cursor query(Uri uri,String[] projection,String selection,String[] selectionArgs,String sortOrder) {
        String n=name(uri);
        int flags=n.equals("partial.txt")?Document.FLAG_PARTIAL:n.equals("virtual.txt")?Document.FLAG_VIRTUAL_DOCUMENT:0;
        MatrixCursor cursor=new MatrixCursor(flags==0?new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE}:new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE,Document.COLUMN_FLAGS});
        cursor.addRow(flags==0?new Object[]{n,null}:new Object[]{n,null,flags}); return cursor;
    }
    public ParcelFileDescriptor openFile(Uri uri,String mode)throws FileNotFoundException {
        String n=name(uri); File file=new File(getContext().getCacheDir(),n);
        try {
            if(!file.exists())try(OutputStream out=new FileOutputStream(file)) {
                if(n.equals("binary.txt"))out.write(new byte[]{80,75,3,4,0});
                else if(n.equals("empty.txt"))out.write(new byte[0]);
                else if(n.equals("utf16.txt"))out.write("第三章 UTF16 中文与😀\nUTF16_END".getBytes("UTF-16"));
                else try(InputStream in=getContext().getAssets().open(n)) { byte[] b=new byte[65536]; int count; while((count=in.read(b))!=-1)out.write(b,0,count); }
            }
            return ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY);
        }catch(IOException e){throw new FileNotFoundException(e.toString());}
    }
    public Uri insert(Uri uri,ContentValues values){throw new UnsupportedOperationException();}
    public int delete(Uri uri,String selection,String[] args){throw new UnsupportedOperationException();}
    public int update(Uri uri,ContentValues values,String selection,String[] args){throw new UnsupportedOperationException();}
}

