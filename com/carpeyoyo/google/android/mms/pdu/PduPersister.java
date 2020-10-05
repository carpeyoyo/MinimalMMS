/*
 * Copyright (C) 2007-2008 Esmertec AG.
 * Copyright (C) 2007-2008 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.carpeyoyo.google.android.mms.pdu;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteException;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.util.Log;

import com.carpeyoyo.google.android.mms.MmsException;

import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.util.HashMap;

public abstract class PduPersister {
    private static final String TAG = "PduPersister";

    static PduPersister sPersister = null;

    public static PduPersister getPduPersister(Context context) {
        if ((sPersister == null) || !context.equals(sPersister.getContext())) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT){
                sPersister = (PduPersister) new OriginalPduPersister(context);
            }
            else {
                sPersister = (PduPersister) new PreKitPduPersister(context);
            }
        }

        return sPersister;
    }

    public abstract Context getContext();
    public abstract GenericPdu load(Uri uri) throws MmsException;
    public abstract Uri persistPart(PduPart part, long msgId, HashMap<Uri, InputStream> preOpenedFiles) throws MmsException;
    public abstract void updateHeaders(Uri uri, SendReq sendReq);
    public abstract void updateParts(Uri uri, PduBody body, HashMap<Uri, InputStream> preOpenedFiles) throws MmsException;
    public abstract Uri move(Uri from, Uri to) throws MmsException;
    public abstract void release();
    public abstract Cursor getPendingMessages(long dueTime);
    public abstract Uri persist(GenericPdu pdu, Uri uri, boolean createThreadId, boolean groupMmsEnabled, HashMap<Uri, InputStream> preOpenedFiles) throws MmsException;

    /**
     * This method expects uri in the following format
     *     content://media/<table_name>/<row_index> (or)
     *     file://sdcard/test.mp4
     *     http://test.com/test.mp4
     *
     * Here <table_name> shall be "video" or "audio" or "images"
     * <row_index> the index of the content in given table
     */
    static public String convertUriToPath(Context context, Uri uri){
        String path = null;
        if (null != uri) {
            String scheme = uri.getScheme();
            if (null == scheme || scheme.equals("") ||
                    scheme.equals(ContentResolver.SCHEME_FILE)) {
                path = uri.getPath();

            } else if (scheme.equals("http")) {
                path = uri.toString();

            } else if (scheme.equals(ContentResolver.SCHEME_CONTENT)) {
                String[] projection = new String[] {MediaStore.MediaColumns.DATA};
                Cursor cursor = null;
                try {
                    cursor = context.getContentResolver().query(uri, projection, null,
                            null, null);
                    if (null == cursor || 0 == cursor.getCount() || !cursor.moveToFirst()) {
                        throw new IllegalArgumentException("Given Uri could not be found" +
                                " in media store");
                    }
                    int pathIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA);
                    path = cursor.getString(pathIndex);
                } catch (SQLiteException e) {
                    throw new IllegalArgumentException("Given Uri is not formatted in a way " +
                            "so that it can be found in media store.");
                } finally {
                    if (null != cursor) {
                        cursor.close();
                    }
                }
            } else {
                throw new IllegalArgumentException("Given Uri scheme is not supported");
            }
        }
        return path;
    }

    /**
     * Wrap a byte[] into a String.
     */
    public static String toIsoString(byte[] bytes) {
        try {
            return new String(bytes, CharacterSets.MIMENAME_ISO_8859_1);
        } catch (UnsupportedEncodingException e) {
            // Impossible to reach here!
            Log.e(TAG, "ISO_8859_1 must be supported!", e);
            return "";
        }
    }

    /**
     * Unpack a given String into a byte[].
     */
    public static byte[] getBytes(String data) {
        try {
            return data.getBytes(CharacterSets.MIMENAME_ISO_8859_1);
        } catch (UnsupportedEncodingException e) {
            // Impossible to reach here!
            Log.e(TAG, "ISO_8859_1 must be supported!", e);
            return new byte[0];
        }
    }
}
