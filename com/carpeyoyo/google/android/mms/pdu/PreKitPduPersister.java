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

import com.carpeyoyo.google.android.mms.ContentType;
import com.carpeyoyo.google.android.mms.InvalidHeaderValueException;
import com.carpeyoyo.google.android.mms.MmsException;
import com.carpeyoyo.google.android.mms.util.DownloadDrmHelper;
import com.carpeyoyo.google.android.mms.util.DrmConvertSession;
import com.carpeyoyo.google.android.mms.util.PduCache;
import com.carpeyoyo.google.android.mms.util.PduCacheEntry;
import com.carpeyoyo.google.android.mms.util.SqliteWrapper;

import android.annotation.SuppressLint;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.drm.DrmManagerClient;
import android.net.Uri;
import android.provider.BaseColumns;
import android.provider.MediaStore;
//import android.provider.Telephony;
//import android.provider.Telephony.Mms;
//import android.provider.Telephony.MmsSms;
//import android.provider.Telephony.Threads;
//import android.provider.Telephony.Mms.Addr;
//import android.provider.Telephony.Mms.Part;
//import android.provider.Telephony.MmsSms.PendingMessages;
import android.telephony.PhoneNumberUtils;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;
import android.util.Patterns;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.Map.Entry;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.carpeyoyo.google.android.mms.pdu.EncodedStringValue;

import static android.provider.BaseColumns._ID;

/**
 * This class is the high-level manager of PDU storage.
 */
public class PreKitPduPersister extends PduPersister{
    private static final String TAG = "PduPersister";
    private static final boolean DEBUG = false;
    private static final boolean LOCAL_LOGV = false;

    private static final long DUMMY_THREAD_ID = Long.MAX_VALUE;

    /**
     * The uri of temporary drm objects.
     */
    public static final String TEMPORARY_DRM_OBJECT_URI =
        "content://mms/" + Long.MAX_VALUE + "/part";
    /**
     * Indicate that we transiently failed to process a MM.
     */
    public static final int PROC_STATUS_TRANSIENT_FAILURE   = 1;
    /**
     * Indicate that we permanently failed to process a MM.
     */
    public static final int PROC_STATUS_PERMANENTLY_FAILURE = 2;
    /**
     * Indicate that we have successfully processed a MM.
     */
    public static final int PROC_STATUS_COMPLETED           = 3;

    private static PduPersister sPersister;
    private static final PduCache PDU_CACHE_INSTANCE;

    private static final int[] ADDRESS_FIELDS = new int[] {
            PduHeaders.BCC,
            PduHeaders.CC,
            PduHeaders.FROM,
            PduHeaders.TO
    };

    

    private static final String[] PDU_PROJECTION = new String[] {
        Mms._ID,
        Mms.MESSAGE_BOX,
        Mms.THREAD_ID,
        Mms.RETRIEVE_TEXT,
        Mms.SUBJECT,
        Mms.CONTENT_LOCATION,
        Mms.CONTENT_TYPE,
        Mms.MESSAGE_CLASS,
        Mms.MESSAGE_ID,
        Mms.RESPONSE_TEXT,
        Mms.TRANSACTION_ID,
        Mms.CONTENT_CLASS,
        Mms.DELIVERY_REPORT,
        Mms.MESSAGE_TYPE,
        Mms.MMS_VERSION,
        Mms.PRIORITY,
        Mms.READ_REPORT,
        Mms.READ_STATUS,
        Mms.REPORT_ALLOWED,
        Mms.RETRIEVE_STATUS,
        Mms.STATUS,
        Mms.DATE,
        Mms.DELIVERY_TIME,
        Mms.EXPIRY,
        Mms.MESSAGE_SIZE,
        Mms.SUBJECT_CHARSET,
        Mms.RETRIEVE_TEXT_CHARSET,
    };

    private static final int PDU_COLUMN_ID                    = 0;
    private static final int PDU_COLUMN_MESSAGE_BOX           = 1;
    private static final int PDU_COLUMN_THREAD_ID             = 2;
    private static final int PDU_COLUMN_RETRIEVE_TEXT         = 3;
    private static final int PDU_COLUMN_SUBJECT               = 4;
    private static final int PDU_COLUMN_CONTENT_LOCATION      = 5;
    private static final int PDU_COLUMN_CONTENT_TYPE          = 6;
    private static final int PDU_COLUMN_MESSAGE_CLASS         = 7;
    private static final int PDU_COLUMN_MESSAGE_ID            = 8;
    private static final int PDU_COLUMN_RESPONSE_TEXT         = 9;
    private static final int PDU_COLUMN_TRANSACTION_ID        = 10;
    private static final int PDU_COLUMN_CONTENT_CLASS         = 11;
    private static final int PDU_COLUMN_DELIVERY_REPORT       = 12;
    private static final int PDU_COLUMN_MESSAGE_TYPE          = 13;
    private static final int PDU_COLUMN_MMS_VERSION           = 14;
    private static final int PDU_COLUMN_PRIORITY              = 15;
    private static final int PDU_COLUMN_READ_REPORT           = 16;
    private static final int PDU_COLUMN_READ_STATUS           = 17;
    private static final int PDU_COLUMN_REPORT_ALLOWED        = 18;
    private static final int PDU_COLUMN_RETRIEVE_STATUS       = 19;
    private static final int PDU_COLUMN_STATUS                = 20;
    private static final int PDU_COLUMN_DATE                  = 21;
    private static final int PDU_COLUMN_DELIVERY_TIME         = 22;
    private static final int PDU_COLUMN_EXPIRY                = 23;
    private static final int PDU_COLUMN_MESSAGE_SIZE          = 24;
    private static final int PDU_COLUMN_SUBJECT_CHARSET       = 25;
    private static final int PDU_COLUMN_RETRIEVE_TEXT_CHARSET = 26;

    private static final String[] PART_PROJECTION = new String[] {
        Mms.Part._ID,
        Mms.Part.CHARSET,
        Mms.Part.CONTENT_DISPOSITION,
        Mms.Part.CONTENT_ID,
        Mms.Part.CONTENT_LOCATION,
        Mms.Part.CONTENT_TYPE,
        Mms.Part.FILENAME,
        Mms.Part.NAME,
        Mms.Part.TEXT
    };

    private static final int PART_COLUMN_ID                  = 0;
    private static final int PART_COLUMN_CHARSET             = 1;
    private static final int PART_COLUMN_CONTENT_DISPOSITION = 2;
    private static final int PART_COLUMN_CONTENT_ID          = 3;
    private static final int PART_COLUMN_CONTENT_LOCATION    = 4;
    private static final int PART_COLUMN_CONTENT_TYPE        = 5;
    private static final int PART_COLUMN_FILENAME            = 6;
    private static final int PART_COLUMN_NAME                = 7;
    private static final int PART_COLUMN_TEXT                = 8;

    private static final HashMap<Uri, Integer> MESSAGE_BOX_MAP;
    // These map are used for convenience in persist() and load().
    private static final HashMap<Integer, Integer> CHARSET_COLUMN_INDEX_MAP;
    private static final HashMap<Integer, Integer> ENCODED_STRING_COLUMN_INDEX_MAP;
    private static final HashMap<Integer, Integer> TEXT_STRING_COLUMN_INDEX_MAP;
    private static final HashMap<Integer, Integer> OCTET_COLUMN_INDEX_MAP;
    private static final HashMap<Integer, Integer> LONG_COLUMN_INDEX_MAP;
    private static final HashMap<Integer, String> CHARSET_COLUMN_NAME_MAP;
    private static final HashMap<Integer, String> ENCODED_STRING_COLUMN_NAME_MAP;
    private static final HashMap<Integer, String> TEXT_STRING_COLUMN_NAME_MAP;
    private static final HashMap<Integer, String> OCTET_COLUMN_NAME_MAP;
    private static final HashMap<Integer, String> LONG_COLUMN_NAME_MAP;

    static {
        MESSAGE_BOX_MAP = new HashMap<Uri, Integer>();
        MESSAGE_BOX_MAP.put(Mms.Inbox.CONTENT_URI,  Mms.MESSAGE_BOX_INBOX);
        MESSAGE_BOX_MAP.put(Mms.Sent.CONTENT_URI,   Mms.MESSAGE_BOX_SENT);
        MESSAGE_BOX_MAP.put(Mms.Draft.CONTENT_URI,  Mms.MESSAGE_BOX_DRAFTS);
        MESSAGE_BOX_MAP.put(Mms.Outbox.CONTENT_URI, Mms.MESSAGE_BOX_OUTBOX);

        CHARSET_COLUMN_INDEX_MAP = new HashMap<Integer, Integer>();
        CHARSET_COLUMN_INDEX_MAP.put(PduHeaders.SUBJECT, PDU_COLUMN_SUBJECT_CHARSET);
        CHARSET_COLUMN_INDEX_MAP.put(PduHeaders.RETRIEVE_TEXT, PDU_COLUMN_RETRIEVE_TEXT_CHARSET);

        CHARSET_COLUMN_NAME_MAP = new HashMap<Integer, String>();
        CHARSET_COLUMN_NAME_MAP.put(PduHeaders.SUBJECT, Mms.SUBJECT_CHARSET);
        CHARSET_COLUMN_NAME_MAP.put(PduHeaders.RETRIEVE_TEXT, Mms.RETRIEVE_TEXT_CHARSET);

        // Encoded string field code -> column index/name map.
        ENCODED_STRING_COLUMN_INDEX_MAP = new HashMap<Integer, Integer>();
        ENCODED_STRING_COLUMN_INDEX_MAP.put(PduHeaders.RETRIEVE_TEXT, PDU_COLUMN_RETRIEVE_TEXT);
        ENCODED_STRING_COLUMN_INDEX_MAP.put(PduHeaders.SUBJECT, PDU_COLUMN_SUBJECT);

        ENCODED_STRING_COLUMN_NAME_MAP = new HashMap<Integer, String>();
        ENCODED_STRING_COLUMN_NAME_MAP.put(PduHeaders.RETRIEVE_TEXT, Mms.RETRIEVE_TEXT);
        ENCODED_STRING_COLUMN_NAME_MAP.put(PduHeaders.SUBJECT, Mms.SUBJECT);

        // Text string field code -> column index/name map.
        TEXT_STRING_COLUMN_INDEX_MAP = new HashMap<Integer, Integer>();
        TEXT_STRING_COLUMN_INDEX_MAP.put(PduHeaders.CONTENT_LOCATION, PDU_COLUMN_CONTENT_LOCATION);
        TEXT_STRING_COLUMN_INDEX_MAP.put(PduHeaders.CONTENT_TYPE, PDU_COLUMN_CONTENT_TYPE);
        TEXT_STRING_COLUMN_INDEX_MAP.put(PduHeaders.MESSAGE_CLASS, PDU_COLUMN_MESSAGE_CLASS);
        TEXT_STRING_COLUMN_INDEX_MAP.put(PduHeaders.MESSAGE_ID, PDU_COLUMN_MESSAGE_ID);
        TEXT_STRING_COLUMN_INDEX_MAP.put(PduHeaders.RESPONSE_TEXT, PDU_COLUMN_RESPONSE_TEXT);
        TEXT_STRING_COLUMN_INDEX_MAP.put(PduHeaders.TRANSACTION_ID, PDU_COLUMN_TRANSACTION_ID);

        TEXT_STRING_COLUMN_NAME_MAP = new HashMap<Integer, String>();
        TEXT_STRING_COLUMN_NAME_MAP.put(PduHeaders.CONTENT_LOCATION, Mms.CONTENT_LOCATION);
        TEXT_STRING_COLUMN_NAME_MAP.put(PduHeaders.CONTENT_TYPE, Mms.CONTENT_TYPE);
        TEXT_STRING_COLUMN_NAME_MAP.put(PduHeaders.MESSAGE_CLASS, Mms.MESSAGE_CLASS);
        TEXT_STRING_COLUMN_NAME_MAP.put(PduHeaders.MESSAGE_ID, Mms.MESSAGE_ID);
        TEXT_STRING_COLUMN_NAME_MAP.put(PduHeaders.RESPONSE_TEXT, Mms.RESPONSE_TEXT);
        TEXT_STRING_COLUMN_NAME_MAP.put(PduHeaders.TRANSACTION_ID, Mms.TRANSACTION_ID);

        // Octet field code -> column index/name map.
        OCTET_COLUMN_INDEX_MAP = new HashMap<Integer, Integer>();
        OCTET_COLUMN_INDEX_MAP.put(PduHeaders.CONTENT_CLASS, PDU_COLUMN_CONTENT_CLASS);
        OCTET_COLUMN_INDEX_MAP.put(PduHeaders.DELIVERY_REPORT, PDU_COLUMN_DELIVERY_REPORT);
        OCTET_COLUMN_INDEX_MAP.put(PduHeaders.MESSAGE_TYPE, PDU_COLUMN_MESSAGE_TYPE);
        OCTET_COLUMN_INDEX_MAP.put(PduHeaders.MMS_VERSION, PDU_COLUMN_MMS_VERSION);
        OCTET_COLUMN_INDEX_MAP.put(PduHeaders.PRIORITY, PDU_COLUMN_PRIORITY);
        OCTET_COLUMN_INDEX_MAP.put(PduHeaders.READ_REPORT, PDU_COLUMN_READ_REPORT);
        OCTET_COLUMN_INDEX_MAP.put(PduHeaders.READ_STATUS, PDU_COLUMN_READ_STATUS);
        OCTET_COLUMN_INDEX_MAP.put(PduHeaders.REPORT_ALLOWED, PDU_COLUMN_REPORT_ALLOWED);
        OCTET_COLUMN_INDEX_MAP.put(PduHeaders.RETRIEVE_STATUS, PDU_COLUMN_RETRIEVE_STATUS);
        OCTET_COLUMN_INDEX_MAP.put(PduHeaders.STATUS, PDU_COLUMN_STATUS);

        OCTET_COLUMN_NAME_MAP = new HashMap<Integer, String>();
        OCTET_COLUMN_NAME_MAP.put(PduHeaders.CONTENT_CLASS, Mms.CONTENT_CLASS);
        OCTET_COLUMN_NAME_MAP.put(PduHeaders.DELIVERY_REPORT, Mms.DELIVERY_REPORT);
        OCTET_COLUMN_NAME_MAP.put(PduHeaders.MESSAGE_TYPE, Mms.MESSAGE_TYPE);
        OCTET_COLUMN_NAME_MAP.put(PduHeaders.MMS_VERSION, Mms.MMS_VERSION);
        OCTET_COLUMN_NAME_MAP.put(PduHeaders.PRIORITY, Mms.PRIORITY);
        OCTET_COLUMN_NAME_MAP.put(PduHeaders.READ_REPORT, Mms.READ_REPORT);
        OCTET_COLUMN_NAME_MAP.put(PduHeaders.READ_STATUS, Mms.READ_STATUS);
        OCTET_COLUMN_NAME_MAP.put(PduHeaders.REPORT_ALLOWED, Mms.REPORT_ALLOWED);
        OCTET_COLUMN_NAME_MAP.put(PduHeaders.RETRIEVE_STATUS, Mms.RETRIEVE_STATUS);
        OCTET_COLUMN_NAME_MAP.put(PduHeaders.STATUS, Mms.STATUS);

        // Long field code -> column index/name map.
        LONG_COLUMN_INDEX_MAP = new HashMap<Integer, Integer>();
        LONG_COLUMN_INDEX_MAP.put(PduHeaders.DATE, PDU_COLUMN_DATE);
        LONG_COLUMN_INDEX_MAP.put(PduHeaders.DELIVERY_TIME, PDU_COLUMN_DELIVERY_TIME);
        LONG_COLUMN_INDEX_MAP.put(PduHeaders.EXPIRY, PDU_COLUMN_EXPIRY);
        LONG_COLUMN_INDEX_MAP.put(PduHeaders.MESSAGE_SIZE, PDU_COLUMN_MESSAGE_SIZE);

        LONG_COLUMN_NAME_MAP = new HashMap<Integer, String>();
        LONG_COLUMN_NAME_MAP.put(PduHeaders.DATE, Mms.DATE);
        LONG_COLUMN_NAME_MAP.put(PduHeaders.DELIVERY_TIME, Mms.DELIVERY_TIME);
        LONG_COLUMN_NAME_MAP.put(PduHeaders.EXPIRY, Mms.EXPIRY);
        LONG_COLUMN_NAME_MAP.put(PduHeaders.MESSAGE_SIZE, Mms.MESSAGE_SIZE);

        PDU_CACHE_INSTANCE = PduCache.getInstance();
     }

    private final Context mContext;
    private final ContentResolver mContentResolver;
    private final DrmManagerClient mDrmManagerClient;
    private final TelephonyManager mTelephonyManager;

    protected PreKitPduPersister(Context context) {
        mContext = context;
        mContentResolver = context.getContentResolver();
        mDrmManagerClient = new DrmManagerClient(context);
        mTelephonyManager = (TelephonyManager)context
                .getSystemService(Context.TELEPHONY_SERVICE);
     }

    private void setEncodedStringValueToHeaders(
            Cursor c, int columnIndex,
            PduHeaders headers, int mapColumn) {
        String s = c.getString(columnIndex);
        if ((s != null) && (s.length() > 0)) {
            int charsetColumnIndex = CHARSET_COLUMN_INDEX_MAP.get(mapColumn);
            int charset = c.getInt(charsetColumnIndex);
            EncodedStringValue value = new EncodedStringValue(
                    charset, getBytes(s));
            headers.setEncodedStringValue(value, mapColumn);
        }
    }

    private void setTextStringToHeaders(
            Cursor c, int columnIndex,
            PduHeaders headers, int mapColumn) {
        String s = c.getString(columnIndex);
        if (s != null) {
            headers.setTextString(getBytes(s), mapColumn);
        }
    }

    private void setOctetToHeaders(
            Cursor c, int columnIndex,
            PduHeaders headers, int mapColumn) throws InvalidHeaderValueException {
        if (!c.isNull(columnIndex)) {
            int b = c.getInt(columnIndex);
            headers.setOctet(b, mapColumn);
        }
    }

    private void setLongToHeaders(
            Cursor c, int columnIndex,
            PduHeaders headers, int mapColumn) {
        if (!c.isNull(columnIndex)) {
            long l = c.getLong(columnIndex);
            headers.setLongInteger(l, mapColumn);
        }
    }

    private Integer getIntegerFromPartColumn(Cursor c, int columnIndex) {
        if (!c.isNull(columnIndex)) {
            return c.getInt(columnIndex);
        }
        return null;
    }

    private byte[] getByteArrayFromPartColumn(Cursor c, int columnIndex) {
        if (!c.isNull(columnIndex)) {
            return getBytes(c.getString(columnIndex));
        }
        return null;
    }

    private PduPart[] loadParts(long msgId) throws MmsException {
        Cursor c = SqliteWrapper.query(mContext, mContentResolver,
                Uri.parse("content://mms/" + msgId + "/part"),
                PART_PROJECTION, null, null, null);

        PduPart[] parts = null;

        try {
            if ((c == null) || (c.getCount() == 0)) {
                if (LOCAL_LOGV) {
                    Log.v(TAG, "loadParts(" + msgId + "): no part to load.");
                }
                return null;
            }

            int partCount = c.getCount();
            int partIdx = 0;
            parts = new PduPart[partCount];
            while (c.moveToNext()) {
                PduPart part = new PduPart();
                Integer charset = getIntegerFromPartColumn(
                        c, PART_COLUMN_CHARSET);
                if (charset != null) {
                    part.setCharset(charset);
                }

                byte[] contentDisposition = getByteArrayFromPartColumn(
                        c, PART_COLUMN_CONTENT_DISPOSITION);
                if (contentDisposition != null) {
                    part.setContentDisposition(contentDisposition);
                }

                byte[] contentId = getByteArrayFromPartColumn(
                        c, PART_COLUMN_CONTENT_ID);
                if (contentId != null) {
                    part.setContentId(contentId);
                }

                byte[] contentLocation = getByteArrayFromPartColumn(
                        c, PART_COLUMN_CONTENT_LOCATION);
                if (contentLocation != null) {
                    part.setContentLocation(contentLocation);
                }

                byte[] contentType = getByteArrayFromPartColumn(
                        c, PART_COLUMN_CONTENT_TYPE);
                if (contentType != null) {
                    part.setContentType(contentType);
                } else {
                    throw new MmsException("Content-Type must be set.");
                }

                byte[] fileName = getByteArrayFromPartColumn(
                        c, PART_COLUMN_FILENAME);
                if (fileName != null) {
                    part.setFilename(fileName);
                }

                byte[] name = getByteArrayFromPartColumn(
                        c, PART_COLUMN_NAME);
                if (name != null) {
                    part.setName(name);
                }

                // Construct a Uri for this part.
                long partId = c.getLong(PART_COLUMN_ID);
                Uri partURI = Uri.parse("content://mms/part/" + partId);
                part.setDataUri(partURI);

                // For images/audio/video, we won't keep their data in Part
                // because their renderer accept Uri as source.
                String type = toIsoString(contentType);
                if (!ContentType.isImageType(type)
                        && !ContentType.isAudioType(type)
                        && !ContentType.isVideoType(type)) {
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    InputStream is = null;

                    // Store simple string values directly in the database instead of an
                    // external file.  This makes the text searchable and retrieval slightly
                    // faster.
                    if (ContentType.TEXT_PLAIN.equals(type) || ContentType.APP_SMIL.equals(type)
                            || ContentType.TEXT_HTML.equals(type)) {
                        String text = c.getString(PART_COLUMN_TEXT);
                        byte [] blob = new EncodedStringValue(text != null ? text : "")
                            .getTextString();
                        baos.write(blob, 0, blob.length);
                    } else {

                        try {
                            is = mContentResolver.openInputStream(partURI);

                            byte[] buffer = new byte[256];
                            int len = is.read(buffer);
                            while (len >= 0) {
                                baos.write(buffer, 0, len);
                                len = is.read(buffer);
                            }
                        } catch (IOException e) {
                            Log.e(TAG, "Failed to load part data", e);
                            c.close();
                            throw new MmsException(e);
                        } finally {
                            if (is != null) {
                                try {
                                    is.close();
                                } catch (IOException e) {
                                    Log.e(TAG, "Failed to close stream", e);
                                } // Ignore
                            }
                        }
                    }
                    part.setData(baos.toByteArray());
                }
                parts[partIdx++] = part;
            }
        } finally {
            if (c != null) {
                c.close();
            }
        }

        return parts;
    }

    private void loadAddress(long msgId, PduHeaders headers) {
        Cursor c = SqliteWrapper.query(mContext, mContentResolver,
                Uri.parse("content://mms/" + msgId + "/addr"),
                new String[] { Mms.Addr.ADDRESS, Mms.Addr.CHARSET, Mms.Addr.TYPE },
                null, null, null);

        if (c != null) {
            try {
                while (c.moveToNext()) {
                    String addr = c.getString(0);
                    if (!TextUtils.isEmpty(addr)) {
                        int addrType = c.getInt(2);
                        switch (addrType) {
                            case PduHeaders.FROM:
                                headers.setEncodedStringValue(
                                        new EncodedStringValue(c.getInt(1), getBytes(addr)),
                                        addrType);
                                break;
                            case PduHeaders.TO:
                            case PduHeaders.CC:
                            case PduHeaders.BCC:
                                headers.appendEncodedStringValue(
                                        new EncodedStringValue(c.getInt(1), getBytes(addr)),
                                        addrType);
                                break;
                            default:
                                Log.e(TAG, "Unknown address type: " + addrType);
                                break;
                        }
                    }
                }
            } finally {
                c.close();
            }
        }
    }

    @Override
    public Context getContext() {
        return mContext;
    }

    /**
     * Load a PDU from storage by given Uri.
     *
     * @param uri The Uri of the PDU to be loaded.
     * @return A generic PDU object, it may be cast to dedicated PDU.
     * @throws MmsException Failed to load some fields of a PDU.
     */
    public GenericPdu load(Uri uri) throws MmsException {
        GenericPdu pdu = null;
        PduCacheEntry cacheEntry = null;
        int msgBox = 0;
        long threadId = -1;
        try {
            synchronized(PDU_CACHE_INSTANCE) {
                if (PDU_CACHE_INSTANCE.isUpdating(uri)) {
                    if (LOCAL_LOGV) {
                        Log.v(TAG, "load: " + uri + " blocked by isUpdating()");
                    }
                    try {
                        PDU_CACHE_INSTANCE.wait();
                    } catch (InterruptedException e) {
                        Log.e(TAG, "load: ", e);
                    }
                    cacheEntry = PDU_CACHE_INSTANCE.get(uri);
                    if (cacheEntry != null) {
                        return cacheEntry.getPdu();
                    }
                }
                // Tell the cache to indicate to other callers that this item
                // is currently being updated.
                PDU_CACHE_INSTANCE.setUpdating(uri, true);
            }

            Cursor c = SqliteWrapper.query(mContext, mContentResolver, uri,
                    PDU_PROJECTION, null, null, null);
            PduHeaders headers = new PduHeaders();
            Set<Entry<Integer, Integer>> set;
            long msgId = ContentUris.parseId(uri);

            try {
                if ((c == null) || (c.getCount() != 1) || !c.moveToFirst()) {
                    throw new MmsException("Bad uri: " + uri);
                }

                msgBox = c.getInt(PDU_COLUMN_MESSAGE_BOX);
                threadId = c.getLong(PDU_COLUMN_THREAD_ID);

                set = ENCODED_STRING_COLUMN_INDEX_MAP.entrySet();
                for (Entry<Integer, Integer> e : set) {
                    setEncodedStringValueToHeaders(
                            c, e.getValue(), headers, e.getKey());
                }

                set = TEXT_STRING_COLUMN_INDEX_MAP.entrySet();
                for (Entry<Integer, Integer> e : set) {
                    setTextStringToHeaders(
                            c, e.getValue(), headers, e.getKey());
                }

                set = OCTET_COLUMN_INDEX_MAP.entrySet();
                for (Entry<Integer, Integer> e : set) {
                    setOctetToHeaders(
                            c, e.getValue(), headers, e.getKey());
                }

                set = LONG_COLUMN_INDEX_MAP.entrySet();
                for (Entry<Integer, Integer> e : set) {
                    setLongToHeaders(
                            c, e.getValue(), headers, e.getKey());
                }
            } finally {
                if (c != null) {
                    c.close();
                }
            }

            // Check whether 'msgId' has been assigned a valid value.
            if (msgId == -1L) {
                throw new MmsException("Error! ID of the message: -1.");
            }

            // Load address information of the MM.
            loadAddress(msgId, headers);

            int msgType = headers.getOctet(PduHeaders.MESSAGE_TYPE);
            PduBody body = new PduBody();

            // For PDU which type is M_retrieve.conf or Send.req, we should
            // load multiparts and put them into the body of the PDU.
            if ((msgType == PduHeaders.MESSAGE_TYPE_RETRIEVE_CONF)
                    || (msgType == PduHeaders.MESSAGE_TYPE_SEND_REQ)) {
                PduPart[] parts = loadParts(msgId);
                if (parts != null) {
                    int partsNum = parts.length;
                    for (int i = 0; i < partsNum; i++) {
                        body.addPart(parts[i]);
                    }
                }
            }

            switch (msgType) {
            case PduHeaders.MESSAGE_TYPE_NOTIFICATION_IND:
                pdu = new NotificationInd(headers);
                break;
            case PduHeaders.MESSAGE_TYPE_DELIVERY_IND:
                pdu = new DeliveryInd(headers);
                break;
            case PduHeaders.MESSAGE_TYPE_READ_ORIG_IND:
                pdu = new ReadOrigInd(headers);
                break;
            case PduHeaders.MESSAGE_TYPE_RETRIEVE_CONF:
                pdu = new RetrieveConf(headers, body);
                break;
            case PduHeaders.MESSAGE_TYPE_SEND_REQ:
                pdu = new SendReq(headers, body);
                break;
            case PduHeaders.MESSAGE_TYPE_ACKNOWLEDGE_IND:
                pdu = new AcknowledgeInd(headers);
                break;
            case PduHeaders.MESSAGE_TYPE_NOTIFYRESP_IND:
                pdu = new NotifyRespInd(headers);
                break;
            case PduHeaders.MESSAGE_TYPE_READ_REC_IND:
                pdu = new ReadRecInd(headers);
                break;
            case PduHeaders.MESSAGE_TYPE_SEND_CONF:
            case PduHeaders.MESSAGE_TYPE_FORWARD_REQ:
            case PduHeaders.MESSAGE_TYPE_FORWARD_CONF:
            case PduHeaders.MESSAGE_TYPE_MBOX_STORE_REQ:
            case PduHeaders.MESSAGE_TYPE_MBOX_STORE_CONF:
            case PduHeaders.MESSAGE_TYPE_MBOX_VIEW_REQ:
            case PduHeaders.MESSAGE_TYPE_MBOX_VIEW_CONF:
            case PduHeaders.MESSAGE_TYPE_MBOX_UPLOAD_REQ:
            case PduHeaders.MESSAGE_TYPE_MBOX_UPLOAD_CONF:
            case PduHeaders.MESSAGE_TYPE_MBOX_DELETE_REQ:
            case PduHeaders.MESSAGE_TYPE_MBOX_DELETE_CONF:
            case PduHeaders.MESSAGE_TYPE_MBOX_DESCR:
            case PduHeaders.MESSAGE_TYPE_DELETE_REQ:
            case PduHeaders.MESSAGE_TYPE_DELETE_CONF:
            case PduHeaders.MESSAGE_TYPE_CANCEL_REQ:
            case PduHeaders.MESSAGE_TYPE_CANCEL_CONF:
                throw new MmsException(
                        "Unsupported PDU type: " + Integer.toHexString(msgType));

            default:
                throw new MmsException(
                        "Unrecognized PDU type: " + Integer.toHexString(msgType));
            }
        } finally {
            synchronized(PDU_CACHE_INSTANCE) {
                if (pdu != null) {
                    assert(PDU_CACHE_INSTANCE.get(uri) == null);
                    // Update the cache entry with the real info
                    cacheEntry = new PduCacheEntry(pdu, msgBox, threadId);
                    PDU_CACHE_INSTANCE.put(uri, cacheEntry);
                }
                PDU_CACHE_INSTANCE.setUpdating(uri, false);
                PDU_CACHE_INSTANCE.notifyAll(); // tell anybody waiting on this entry to go ahead
            }
        }
        return pdu;
    }

    private void persistAddress(
            long msgId, int type, EncodedStringValue[] array) {
        ContentValues values = new ContentValues(3);

        for (EncodedStringValue addr : array) {
            values.clear(); // Clear all values first.
            values.put(Mms.Addr.ADDRESS, toIsoString(addr.getTextString()));
            values.put(Mms.Addr.CHARSET, addr.getCharacterSet());
            values.put(Mms.Addr.TYPE, type);

            Uri uri = Uri.parse("content://mms/" + msgId + "/addr");
            SqliteWrapper.insert(mContext, mContentResolver, uri, values);
        }
    }

    private static String getPartContentType(PduPart part) {
        return part.getContentType() == null ? null : toIsoString(part.getContentType());
    }

    public Uri persistPart(PduPart part, long msgId, HashMap<Uri, InputStream> preOpenedFiles)
            throws MmsException {
        Uri uri = Uri.parse("content://mms/" + msgId + "/part");
        ContentValues values = new ContentValues(8);

        int charset = part.getCharset();
        if (charset != 0 ) {
            values.put(Mms.Part.CHARSET, charset);
        }

        String contentType = getPartContentType(part);
        if (contentType != null) {
            // There is no "image/jpg" in Android (and it's an invalid mimetype).
            // Change it to "image/jpeg"
            if (ContentType.IMAGE_JPG.equals(contentType)) {
                contentType = ContentType.IMAGE_JPEG;
            }

            values.put(Mms.Part.CONTENT_TYPE, contentType);
            // To ensure the SMIL part is always the first part.
            if (ContentType.APP_SMIL.equals(contentType)) {
                values.put(Mms.Part.SEQ, -1);
            }
        } else {
            throw new MmsException("MIME type of the part must be set.");
        }

        if (part.getFilename() != null) {
            String fileName = new String(part.getFilename());
            values.put(Mms.Part.FILENAME, fileName);
        }

        if (part.getName() != null) {
            String name = new String(part.getName());
            values.put(Mms.Part.NAME, name);
        }

        Object value = null;
        if (part.getContentDisposition() != null) {
            value = toIsoString(part.getContentDisposition());
            values.put(Mms.Part.CONTENT_DISPOSITION, (String) value);
        }

        if (part.getContentId() != null) {
            value = toIsoString(part.getContentId());
            values.put(Mms.Part.CONTENT_ID, (String) value);
        }

        if (part.getContentLocation() != null) {
            value = toIsoString(part.getContentLocation());
            values.put(Mms.Part.CONTENT_LOCATION, (String) value);
        }

        Uri res = SqliteWrapper.insert(mContext, mContentResolver, uri, values);
        if (res == null) {
            throw new MmsException("Failed to persist part, return null.");
        }

        String uriPath = part.getUriPath();
        if (uriPath != null) {
            values = new ContentValues(1);
            values.put("\n" + Mms.Part._DATA, uriPath);
            int rowsUpated = mContentResolver.update(res,values,null,null);
            Log.d("PersistPart","uriPath: " + uriPath + "\nrowsUpdated: " + rowsUpated);
            if (rowsUpated != 1){
                throw new MmsException("Failed to change _data, return null.");
            }
        }
        else {
            persistData(part, res, contentType, preOpenedFiles);
        }

        // After successfully store the data, we should update
        // the dataUri of the part.
        part.setDataUri(res);

        return res;
    }

    /**
     * Save data of the part into storage. The source data may be given
     * by a byte[] or a Uri. If it's a byte[], directly save it
     * into storage, otherwise load source data from the dataUri and then
     * save it. If the data is an image, we may scale down it according
     * to user preference.
     *
     * @param part The PDU part which contains data to be saved.
     * @param uri The URI of the part.
     * @param contentType The MIME type of the part.
     * @param preOpenedFiles if not null, a map of preopened InputStreams for the parts.
     * @throws MmsException Cannot find source data or error occurred
     *         while saving the data.
     */
    private void persistData(PduPart part, Uri uri,
            String contentType, HashMap<Uri, InputStream> preOpenedFiles)
            throws MmsException {
        OutputStream os = null;
        InputStream is = null;
        DrmConvertSession drmConvertSession = null;
        Uri dataUri = null;
        String path = null;

        try {
            byte[] data = part.getData();
            if (ContentType.TEXT_PLAIN.equals(contentType)
                    || ContentType.APP_SMIL.equals(contentType)
                    || ContentType.TEXT_HTML.equals(contentType)) {
                ContentValues cv = new ContentValues();
                cv.put(Mms.Part.TEXT, new EncodedStringValue(data).getString());
                if (mContentResolver.update(uri, cv, null, null) != 1) {
                    throw new MmsException("unable to update " + uri.toString());
                }
            } else {
                boolean isDrm = DownloadDrmHelper.isDrmConvertNeeded(contentType);
                if (isDrm) {
                    if (uri != null) {
                        try {
                            path = convertUriToPath(mContext, uri);
                            if (LOCAL_LOGV) {
                                Log.v(TAG, "drm uri: " + uri + " path: " + path);
                            }
                            File f = new File(path);
                            long len = f.length();
                            if (LOCAL_LOGV) {
                                Log.v(TAG, "drm path: " + path + " len: " + len);
                            }
                            if (len > 0) {
                                // we're not going to re-persist and re-encrypt an already
                                // converted drm file
                                return;
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "Can't get file info for: " + part.getDataUri(), e);
                        }
                    }
                    // We haven't converted the file yet, start the conversion
                    drmConvertSession = DrmConvertSession.open(mContext, contentType);
                    if (drmConvertSession == null) {
                        throw new MmsException("Mimetype " + contentType +
                                " can not be converted.");
                    }
                }
                // uri can look like:
                // content://mms/part/98
                os = mContentResolver.openOutputStream(uri);
                if (data == null) {
                    dataUri = part.getDataUri();
                    if ((dataUri == null) || (dataUri == uri)) {
                        Log.w(TAG, "Can't find data for this part.");
                        return;
                    }
                    // dataUri can look like:
                    // content://com.google.android.gallery3d.provider/picasa/item/5720646660183715586
                    if (preOpenedFiles != null && preOpenedFiles.containsKey(dataUri)) {
                        is = preOpenedFiles.get(dataUri);
                    }
                    if (is == null) {
                        is = mContentResolver.openInputStream(dataUri);
                    }

                    if (LOCAL_LOGV) {
                        Log.v(TAG, "Saving data to: " + uri);
                    }

                    byte[] buffer = new byte[8192];
                    for (int len = 0; (len = is.read(buffer)) != -1; ) {
                        if (!isDrm) {
                            os.write(buffer, 0, len);
                        } else {
                            byte[] convertedData = drmConvertSession.convert(buffer, len);
                            if (convertedData != null) {
                                os.write(convertedData, 0, convertedData.length);
                            } else {
                                throw new MmsException("Error converting drm data.");
                            }
                        }
                    }
                } else {
                    if (LOCAL_LOGV) {
                        Log.v(TAG, "Saving data to: " + uri);
                    }
                    if (!isDrm) {
                        os.write(data);
                    } else {
                        dataUri = uri;
                        byte[] convertedData = drmConvertSession.convert(data, data.length);
                        if (convertedData != null) {
                            os.write(convertedData, 0, convertedData.length);
                        } else {
                            throw new MmsException("Error converting drm data.");
                        }
                    }
                }
            }
        } catch (FileNotFoundException e) {
            Log.e(TAG, "Failed to open Input/Output stream.", e);
            throw new MmsException(e);
        } catch (IOException e) {
            Log.e(TAG, "Failed to read/write data.", e);
            throw new MmsException(e);
        } finally {
            if (os != null) {
                try {
                    os.close();
                } catch (IOException e) {
                    Log.e(TAG, "IOException while closing: " + os, e);
                } // Ignore
            }
            if (is != null) {
                try {
                    is.close();
                } catch (IOException e) {
                    Log.e(TAG, "IOException while closing: " + is, e);
                } // Ignore
            }
            if (drmConvertSession != null) {
                drmConvertSession.close(path);

                // Reset the permissions on the encrypted part file so everyone has only read
                // permission.
                File f = new File(path);
                ContentValues values = new ContentValues(0);
                SqliteWrapper.update(mContext, mContentResolver,
                                     Uri.parse("content://mms/resetFilePerm/" + f.getName()),
                                     values, null, null);
            }
        }
    }

    private void updateAddress(
            long msgId, int type, EncodedStringValue[] array) {
        // Delete old address information and then insert new ones.
        SqliteWrapper.delete(mContext, mContentResolver,
                Uri.parse("content://mms/" + msgId + "/addr"),
                Mms.Addr.TYPE + "=" + type, null);

        persistAddress(msgId, type, array);
    }

    /**
     * Update headers of a SendReq.
     *
     * @param uri The PDU which need to be updated.
     * @throws MmsException Bad URI or updating failed.
     */
    public void updateHeaders(Uri uri, SendReq sendReq) {
        synchronized(PDU_CACHE_INSTANCE) {
            // If the cache item is getting updated, wait until it's done updating before
            // purging it.
            if (PDU_CACHE_INSTANCE.isUpdating(uri)) {
                if (LOCAL_LOGV) {
                    Log.v(TAG, "updateHeaders: " + uri + " blocked by isUpdating()");
                }
                try {
                    PDU_CACHE_INSTANCE.wait();
                } catch (InterruptedException e) {
                    Log.e(TAG, "updateHeaders: ", e);
                }
            }
        }
        PDU_CACHE_INSTANCE.purge(uri);

        ContentValues values = new ContentValues(10);
        byte[] contentType = sendReq.getContentType();
        if (contentType != null) {
            values.put(Mms.CONTENT_TYPE, toIsoString(contentType));
        }

        long date = sendReq.getDate();
        if (date != -1) {
            values.put(Mms.DATE, date);
        }

        int deliveryReport = sendReq.getDeliveryReport();
        if (deliveryReport != 0) {
            values.put(Mms.DELIVERY_REPORT, deliveryReport);
        }

        long expiry = sendReq.getExpiry();
        if (expiry != -1) {
            values.put(Mms.EXPIRY, expiry);
        }

        byte[] msgClass = sendReq.getMessageClass();
        if (msgClass != null) {
            values.put(Mms.MESSAGE_CLASS, toIsoString(msgClass));
        }

        int priority = sendReq.getPriority();
        if (priority != 0) {
            values.put(Mms.PRIORITY, priority);
        }

        int readReport = sendReq.getReadReport();
        if (readReport != 0) {
            values.put(Mms.READ_REPORT, readReport);
        }

        byte[] transId = sendReq.getTransactionId();
        if (transId != null) {
            values.put(Mms.TRANSACTION_ID, toIsoString(transId));
        }

        EncodedStringValue subject = sendReq.getSubject();
        if (subject != null) {
            values.put(Mms.SUBJECT, toIsoString(subject.getTextString()));
            values.put(Mms.SUBJECT_CHARSET, subject.getCharacterSet());
        } else {
            values.put(Mms.SUBJECT, "");
        }

        long messageSize = sendReq.getMessageSize();
        if (messageSize > 0) {
            values.put(Mms.MESSAGE_SIZE, messageSize);
        }

        PduHeaders headers = sendReq.getPduHeaders();
        HashSet<String> recipients = new HashSet<String>();
        for (int addrType : ADDRESS_FIELDS) {
            EncodedStringValue[] array = null;
            if (addrType == PduHeaders.FROM) {
                EncodedStringValue v = headers.getEncodedStringValue(addrType);
                if (v != null) {
                    array = new EncodedStringValue[1];
                    array[0] = v;
                }
            } else {
                array = headers.getEncodedStringValues(addrType);
            }

            if (array != null) {
                long msgId = ContentUris.parseId(uri);
                updateAddress(msgId, addrType, array);
                if (addrType == PduHeaders.TO) {
                    for (EncodedStringValue v : array) {
                        if (v != null) {
                            recipients.add(v.getString());
                        }
                    }
                }
            }
        }
        if (!recipients.isEmpty()) {
            long threadId = getOrCreateThreadId(mContext, recipients);
            values.put(Mms.THREAD_ID, threadId);
        }

        SqliteWrapper.update(mContext, mContentResolver, uri, values, null, null);
    }

    private void updatePart(Uri uri, PduPart part, HashMap<Uri, InputStream> preOpenedFiles)
            throws MmsException {
        ContentValues values = new ContentValues(7);

        int charset = part.getCharset();
        if (charset != 0 ) {
            values.put(Mms.Part.CHARSET, charset);
        }

        String contentType = null;
        if (part.getContentType() != null) {
            contentType = toIsoString(part.getContentType());
            values.put(Mms.Part.CONTENT_TYPE, contentType);
        } else {
            throw new MmsException("MIME type of the part must be set.");
        }

        if (part.getFilename() != null) {
            String fileName = new String(part.getFilename());
            values.put(Mms.Part.FILENAME, fileName);
        }

        if (part.getName() != null) {
            String name = new String(part.getName());
            values.put(Mms.Part.NAME, name);
        }

        Object value = null;
        if (part.getContentDisposition() != null) {
            value = toIsoString(part.getContentDisposition());
            values.put(Mms.Part.CONTENT_DISPOSITION, (String) value);
        }

        if (part.getContentId() != null) {
            value = toIsoString(part.getContentId());
            values.put(Mms.Part.CONTENT_ID, (String) value);
        }

        if (part.getContentLocation() != null) {
            value = toIsoString(part.getContentLocation());
            values.put(Mms.Part.CONTENT_LOCATION, (String) value);
        }

        SqliteWrapper.update(mContext, mContentResolver, uri, values, null, null);

        // Only update the data when:
        // 1. New binary data supplied or
        // 2. The Uri of the part is different from the current one.
        if ((part.getData() != null)
                || (uri != part.getDataUri())) {
            persistData(part, uri, contentType, preOpenedFiles);
        }
    }

    /**
     * Update all parts of a PDU.
     *
     * @param uri The PDU which need to be updated.
     * @param body New message body of the PDU.
     * @param preOpenedFiles if not null, a map of preopened InputStreams for the parts.
     * @throws MmsException Bad URI or updating failed.
     */
    public void updateParts(Uri uri, PduBody body, HashMap<Uri, InputStream> preOpenedFiles)
            throws MmsException {
        try {
            PduCacheEntry cacheEntry;
            synchronized(PDU_CACHE_INSTANCE) {
                if (PDU_CACHE_INSTANCE.isUpdating(uri)) {
                    if (LOCAL_LOGV) {
                        Log.v(TAG, "updateParts: " + uri + " blocked by isUpdating()");
                    }
                    try {
                        PDU_CACHE_INSTANCE.wait();
                    } catch (InterruptedException e) {
                        Log.e(TAG, "updateParts: ", e);
                    }
                    cacheEntry = PDU_CACHE_INSTANCE.get(uri);
                    if (cacheEntry != null) {
                        ((MultimediaMessagePdu) cacheEntry.getPdu()).setBody(body);
                    }
                }
                // Tell the cache to indicate to other callers that this item
                // is currently being updated.
                PDU_CACHE_INSTANCE.setUpdating(uri, true);
            }

            ArrayList<PduPart> toBeCreated = new ArrayList<PduPart>();
            HashMap<Uri, PduPart> toBeUpdated = new HashMap<Uri, PduPart>();

            int partsNum = body.getPartsNum();
            StringBuilder filter = new StringBuilder().append('(');
            for (int i = 0; i < partsNum; i++) {
                PduPart part = body.getPart(i);
                Uri partUri = part.getDataUri();
                if ((partUri == null) || !partUri.getAuthority().startsWith("mms")) {
                    toBeCreated.add(part);
                } else {
                    toBeUpdated.put(partUri, part);

                    // Don't use 'i > 0' to determine whether we should append
                    // 'AND' since 'i = 0' may be skipped in another branch.
                    if (filter.length() > 1) {
                        filter.append(" AND ");
                    }

                    filter.append(Mms.Part._ID);
                    filter.append("!=");
                    DatabaseUtils.appendEscapedSQLString(filter, partUri.getLastPathSegment());
                }
            }
            filter.append(')');

            long msgId = ContentUris.parseId(uri);

            // Remove the parts which doesn't exist anymore.
            SqliteWrapper.delete(mContext, mContentResolver,
                    Uri.parse(Mms.CONTENT_URI + "/" + msgId + "/part"),
                    filter.length() > 2 ? filter.toString() : null, null);

            // Create new parts which didn't exist before.
            for (PduPart part : toBeCreated) {
                persistPart(part, msgId, preOpenedFiles);
            }

            // Update the modified parts.
            for (Map.Entry<Uri, PduPart> e : toBeUpdated.entrySet()) {
                updatePart(e.getKey(), e.getValue(), preOpenedFiles);
            }
        } finally {
            synchronized(PDU_CACHE_INSTANCE) {
                PDU_CACHE_INSTANCE.setUpdating(uri, false);
                PDU_CACHE_INSTANCE.notifyAll();
            }
        }
    }

    /**
     * Persist a PDU object to specific location in the storage.
     *
     * @param pdu The PDU object to be stored.
     * @param uri Where to store the given PDU object.
     * @param createThreadId if true, this function may create a thread id for the recipients
     * @param groupMmsEnabled if true, all of the recipients addressed in the PDU will be used
     *  to create the associated thread. When false, only the sender will be used in finding or
     *  creating the appropriate thread or conversation.
     * @param preOpenedFiles if not null, a map of preopened InputStreams for the parts.
     * @return A Uri which can be used to access the stored PDU.
     */

    public Uri persist(GenericPdu pdu, Uri uri, boolean createThreadId, boolean groupMmsEnabled,
            HashMap<Uri, InputStream> preOpenedFiles)
            throws MmsException {
        if (uri == null) {
            throw new MmsException("Uri may not be null.");
        }
        long msgId = -1;
        try {
            msgId = ContentUris.parseId(uri);
        } catch (NumberFormatException e) {
            // the uri ends with "inbox" or something else like that
        }
        boolean existingUri = msgId != -1;

        if (!existingUri && MESSAGE_BOX_MAP.get(uri) == null) {
            throw new MmsException(
                    "Bad destination, must be one of "
                    + "content://mms/inbox, content://mms/sent, "
                    + "content://mms/drafts, content://mms/outbox, "
                    + "content://mms/temp.");
        }
        synchronized(PDU_CACHE_INSTANCE) {
            // If the cache item is getting updated, wait until it's done updating before
            // purging it.
            if (PDU_CACHE_INSTANCE.isUpdating(uri)) {
                if (LOCAL_LOGV) {
                    Log.v(TAG, "persist: " + uri + " blocked by isUpdating()");
                }
                try {
                    PDU_CACHE_INSTANCE.wait();
                } catch (InterruptedException e) {
                    Log.e(TAG, "persist1: ", e);
                }
            }
        }
        PDU_CACHE_INSTANCE.purge(uri);

        PduHeaders header = pdu.getPduHeaders();
        PduBody body = null;
        ContentValues values = new ContentValues();
        Set<Entry<Integer, String>> set;

        set = ENCODED_STRING_COLUMN_NAME_MAP.entrySet();
        for (Entry<Integer, String> e : set) {
            int field = e.getKey();
            EncodedStringValue encodedString = header.getEncodedStringValue(field);
            if (encodedString != null) {
                String charsetColumn = CHARSET_COLUMN_NAME_MAP.get(field);
                values.put(e.getValue(), toIsoString(encodedString.getTextString()));
                values.put(charsetColumn, encodedString.getCharacterSet());
            }
        }

        set = TEXT_STRING_COLUMN_NAME_MAP.entrySet();
        for (Entry<Integer, String> e : set){
            byte[] text = header.getTextString(e.getKey());
            if (text != null) {
                values.put(e.getValue(), toIsoString(text));
            }
        }

        set = OCTET_COLUMN_NAME_MAP.entrySet();
        for (Entry<Integer, String> e : set){
            int b = header.getOctet(e.getKey());
            if (b != 0) {
                values.put(e.getValue(), b);
            }
        }

        set = LONG_COLUMN_NAME_MAP.entrySet();
        for (Entry<Integer, String> e : set){
            long l = header.getLongInteger(e.getKey());
            if (l != -1L) {
                values.put(e.getValue(), l);
            }
        }

        HashMap<Integer, EncodedStringValue[]> addressMap =
                new HashMap<Integer, EncodedStringValue[]>(ADDRESS_FIELDS.length);
        // Save address information.
        for (int addrType : ADDRESS_FIELDS) {
            EncodedStringValue[] array = null;
            if (addrType == PduHeaders.FROM) {
                EncodedStringValue v = header.getEncodedStringValue(addrType);
                if (v != null) {
                    array = new EncodedStringValue[1];
                    array[0] = v;
                }
            } else {
                array = header.getEncodedStringValues(addrType);
            }
            addressMap.put(addrType, array);
        }

        HashSet<String> recipients = new HashSet<String>();
        int msgType = pdu.getMessageType();
        // Here we only allocate thread ID for M-Notification.ind,
        // M-Retrieve.conf and M-Send.req.
        // Some of other PDU types may be allocated a thread ID outside
        // this scope.
        if ((msgType == PduHeaders.MESSAGE_TYPE_NOTIFICATION_IND)
                || (msgType == PduHeaders.MESSAGE_TYPE_RETRIEVE_CONF)
                || (msgType == PduHeaders.MESSAGE_TYPE_SEND_REQ)) {
            switch (msgType) {
                case PduHeaders.MESSAGE_TYPE_NOTIFICATION_IND:
                case PduHeaders.MESSAGE_TYPE_RETRIEVE_CONF:
                    loadRecipients(PduHeaders.FROM, recipients, addressMap, false);

                    // For received messages when group MMS is enabled, we want to associate this
                    // message with the thread composed of all the recipients -- all but our own
                    // number, that is. This includes the person who sent the
                    // message or the FROM field (above) in addition to the other people the message
                    // was addressed to or the TO field. Our own number is in that TO field and
                    // we have to ignore it in loadRecipients.
                    if (groupMmsEnabled) {
                        loadRecipients(PduHeaders.TO, recipients, addressMap, true);
                        
                        // Also load any numbers in the CC field to address group messaging
                        // compatibility issues with devices that place numbers in this field
                        // for group messages.
                        loadRecipients(PduHeaders.CC, recipients, addressMap, true);
                    }
                    break;
                case PduHeaders.MESSAGE_TYPE_SEND_REQ:
                    loadRecipients(PduHeaders.TO, recipients, addressMap, false);
                    if (groupMmsEnabled) {
                        loadRecipients(PduHeaders.CC, recipients, addressMap, false);
                    }
                    break;
            }
            long threadId = 0;
            if (createThreadId && !recipients.isEmpty()) {
                // Given all the recipients associated with this message, find (or create) the
                // correct thread.
                threadId = getOrCreateThreadId(mContext, recipients);
            }
            values.put(Mms.THREAD_ID, threadId);
        }

        // Save parts first to avoid inconsistent message is loaded
        // while saving the parts.
        long dummyId = System.currentTimeMillis(); // Dummy ID of the msg.

        // Figure out if this PDU is a text-only message
        boolean textOnly = true;

        // Get body if the PDU is a RetrieveConf or SendReq.
        if (pdu instanceof MultimediaMessagePdu) {
            body = ((MultimediaMessagePdu) pdu).getBody();
            // Start saving parts if necessary.
            if (body != null) {
                int partsNum = body.getPartsNum();
                if (partsNum > 2) {
                    // For a text-only message there will be two parts: 1-the SMIL, 2-the text.
                    // Down a few lines below we're checking to make sure we've only got SMIL or
                    // text. We also have to check then we don't have more than two parts.
                    // Otherwise, a slideshow with two text slides would be marked as textOnly.
                    textOnly = false;
                }
                for (int i = 0; i < partsNum; i++) {
                    PduPart part = body.getPart(i);
                    persistPart(part, dummyId, preOpenedFiles);

                    // If we've got anything besides text/plain or SMIL part, then we've got
                    // an mms message with some other type of attachment.
                    String contentType = getPartContentType(part);
                    if (contentType != null && !ContentType.APP_SMIL.equals(contentType)
                            && !ContentType.TEXT_PLAIN.equals(contentType)) {
                        textOnly = false;
                    }
                }
            }
        }
        // Record whether this mms message is a simple plain text or not. This is a hint for the
        // UI.
        values.put(Mms.TEXT_ONLY, textOnly ? 1 : 0);

        Uri res = null;
        if (existingUri) {
            res = uri;
            SqliteWrapper.update(mContext, mContentResolver, res, values, null, null);
        } else {
            res = SqliteWrapper.insert(mContext, mContentResolver, uri, values);
            if (res == null) {
                throw new MmsException("persist() failed: return null.");
            }
            // Get the real ID of the PDU and update all parts which were
            // saved with the dummy ID.
            msgId = ContentUris.parseId(res);
        }

        values = new ContentValues(1);
        values.put(Mms.Part.MSG_ID, msgId);
        SqliteWrapper.update(mContext, mContentResolver,
                             Uri.parse("content://mms/" + dummyId + "/part"),
                             values, null, null);
        // We should return the longest URI of the persisted PDU, for
        // example, if input URI is "content://mms/inbox" and the _ID of
        // persisted PDU is '8', we should return "content://mms/inbox/8"
        // instead of "content://mms/8".
        // FIXME: Should the MmsProvider be responsible for this???
        if (!existingUri) {
            res = Uri.parse(uri + "/" + msgId);
        }

        // Save address information.
        for (int addrType : ADDRESS_FIELDS) {
            EncodedStringValue[] array = addressMap.get(addrType);
            if (array != null) {
                persistAddress(msgId, addrType, array);
            }
        }

        return res;
    }

    /**
     * For a given address type, extract the recipients from the headers.
     *
     * @param addressType can be PduHeaders.FROM, PduHeaders.TO or PduHeaders.CC
     * @param recipients a HashSet that is loaded with the recipients from the FROM, TO or CC headers
     * @param addressMap a HashMap of the addresses from the ADDRESS_FIELDS header
     * @param excludeMyNumber if true, the number of this phone will be excluded from recipients
     */
    private void loadRecipients(int addressType, HashSet<String> recipients,
            HashMap<Integer, EncodedStringValue[]> addressMap, boolean excludeMyNumber) {
        EncodedStringValue[] array = addressMap.get(addressType);
        if (array == null) {
            return;
        }
        // If the TO recipients is only a single address, then we can skip loadRecipients when
        // we're excluding our own number because we know that address is our own.
        if (excludeMyNumber && array.length == 1) {
            return;
        }
        @SuppressLint("MissingPermission") String myNumber = excludeMyNumber ? mTelephonyManager.getLine1Number() : null;
        for (EncodedStringValue v : array) {
            if (v != null) {
                String number = v.getString();
                if ((myNumber == null || !PhoneNumberUtils.compare(number, myNumber)) &&
                        !recipients.contains(number)) {
                    // Only add numbers which aren't my own number.
                    recipients.add(number);
                }
            }
        }
    }

    /**
     * Move a PDU object from one location to another.
     *
     * @param from Specify the PDU object to be moved.
     * @param to The destination location, should be one of the following:
     *        "content://mms/inbox", "content://mms/sent",
     *        "content://mms/drafts", "content://mms/outbox",
     *        "content://mms/trash".
     * @return New Uri of the moved PDU.
     * @throws MmsException Error occurred while moving the message.
     */
    public Uri move(Uri from, Uri to) throws MmsException {
        // Check whether the 'msgId' has been assigned a valid value.
        long msgId = ContentUris.parseId(from);
        if (msgId == -1L) {
            throw new MmsException("Error! ID of the message: -1.");
        }

        // Get corresponding int value of destination box.
        Integer msgBox = MESSAGE_BOX_MAP.get(to);
        if (msgBox == null) {
            throw new MmsException(
                    "Bad destination, must be one of "
                    + "content://mms/inbox, content://mms/sent, "
                    + "content://mms/drafts, content://mms/outbox, "
                    + "content://mms/temp.");
        }

        ContentValues values = new ContentValues(1);
        values.put(Mms.MESSAGE_BOX, msgBox);
        SqliteWrapper.update(mContext, mContentResolver, from, values, null, null);
        return ContentUris.withAppendedId(to, msgId);
    }

    /**
     * Remove all objects in the temporary path.
     */
    public void release() {
        Uri uri = Uri.parse(TEMPORARY_DRM_OBJECT_URI);
        SqliteWrapper.delete(mContext, mContentResolver, uri, null, null);
    }

    /**
     * Find all messages to be sent or downloaded before certain time.
     */
    public Cursor getPendingMessages(long dueTime) {
        Uri.Builder uriBuilder = MmsSms.PendingMessages.CONTENT_URI.buildUpon();
        uriBuilder.appendQueryParameter("protocol", "mms");

        String selection = MmsSms.PendingMessages.ERROR_TYPE + " < ?"
                + " AND " + MmsSms.PendingMessages.DUE_TIME + " <= ?";

        String[] selectionArgs = new String[] {
                String.valueOf(MmsSms.ERR_TYPE_GENERIC_PERMANENT),
                String.valueOf(dueTime)
        };

        return SqliteWrapper.query(mContext, mContentResolver,
                uriBuilder.build(), null, selection, selectionArgs,
                MmsSms.PendingMessages.DUE_TIME);
    }

    /**
     * Used to find the thread ID. Replaces a static function for Threads class for lower than API 19.
     *
     * @param context
     * @param address_set
     * @return
     */
    private static long getOrCreateThreadId(Context context, HashSet<String> address_set){
        Uri.Builder thread_builder = Uri.parse("content://mms-sms/threadID").buildUpon();
        // Add Addresses
        for (String address : address_set) {
            thread_builder.appendQueryParameter("recipient", address);
        }
        // Get ThreadID
        long thread_id = -1;
        Cursor cursor = context.getContentResolver().query(thread_builder.build(), null, null, null, null);
        if (cursor != null) {
            if (cursor.moveToFirst()) {
                thread_id = cursor.getLong(cursor.getColumnIndex("_id"));
            }
            cursor.close();
        }
        return thread_id;
    }

    /**
     * Base columns for tables that contain MMSs.
     */
    public interface BaseMmsColumns extends BaseColumns {

        /** Message box: all messages. */
        public static final int MESSAGE_BOX_ALL    = 0;
        /** Message box: inbox. */
        public static final int MESSAGE_BOX_INBOX  = 1;
        /** Message box: sent messages. */
        public static final int MESSAGE_BOX_SENT   = 2;
        /** Message box: drafts. */
        public static final int MESSAGE_BOX_DRAFTS = 3;
        /** Message box: outbox. */
        public static final int MESSAGE_BOX_OUTBOX = 4;

        /**
         * The thread ID of the message.
         * <P>Type: INTEGER (long)</P>
         */
        public static final String THREAD_ID = "thread_id";

        /**
         * The date the message was received.
         * <P>Type: INTEGER (long)</P>
         */
        public static final String DATE = "date";

        /**
         * The date the message was sent.
         * <P>Type: INTEGER (long)</P>
         */
        public static final String DATE_SENT = "date_sent";

        /**
         * The box which the message belongs to, e.g. {@link #MESSAGE_BOX_INBOX}.
         * <P>Type: INTEGER</P>
         */
        public static final String MESSAGE_BOX = "msg_box";

        /**
         * Has the message been read?
         * <P>Type: INTEGER (boolean)</P>
         */
        public static final String READ = "read";

        /**
         * Has the message been seen by the user? The "seen" flag determines
         * whether we need to show a new message notification.
         * <P>Type: INTEGER (boolean)</P>
         */
        public static final String SEEN = "seen";

        /**
         * Does the message have only a text part (can also have a subject) with
         * no picture, slideshow, sound, etc. parts?
         * <P>Type: INTEGER (boolean)</P>
         */
        public static final String TEXT_ONLY = "text_only";

        /**
         * The {@code Message-ID} of the message.
         * <P>Type: TEXT</P>
         */
        public static final String MESSAGE_ID = "m_id";

        /**
         * The subject of the message, if present.
         * <P>Type: TEXT</P>
         */
        public static final String SUBJECT = "sub";

        /**
         * The character set of the subject, if present.
         * <P>Type: INTEGER</P>
         */
        public static final String SUBJECT_CHARSET = "sub_cs";

        /**
         * The {@code Content-Type} of the message.
         * <P>Type: TEXT</P>
         */
        public static final String CONTENT_TYPE = "ct_t";

        /**
         * The {@code Content-Location} of the message.
         * <P>Type: TEXT</P>
         */
        public static final String CONTENT_LOCATION = "ct_l";

        /**
         * The expiry time of the message.
         * <P>Type: INTEGER (long)</P>
         */
        public static final String EXPIRY = "exp";

        /**
         * The class of the message.
         * <P>Type: TEXT</P>
         */
        public static final String MESSAGE_CLASS = "m_cls";

        /**
         * The type of the message defined by MMS spec.
         * <P>Type: INTEGER</P>
         */
        public static final String MESSAGE_TYPE = "m_type";

        /**
         * The version of the specification that this message conforms to.
         * <P>Type: INTEGER</P>
         */
        public static final String MMS_VERSION = "v";

        /**
         * The size of the message.
         * <P>Type: INTEGER</P>
         */
        public static final String MESSAGE_SIZE = "m_size";

        /**
         * The priority of the message.
         * <P>Type: INTEGER</P>
         */
        public static final String PRIORITY = "pri";

        /**
         * The {@code read-report} of the message.
         * <P>Type: INTEGER (boolean)</P>
         */
        public static final String READ_REPORT = "rr";

        /**
         * Is read report allowed?
         * <P>Type: INTEGER (boolean)</P>
         */
        public static final String REPORT_ALLOWED = "rpt_a";

        /**
         * The {@code response-status} of the message.
         * <P>Type: INTEGER</P>
         */
        public static final String RESPONSE_STATUS = "resp_st";

        /**
         * The {@code status} of the message.
         * <P>Type: INTEGER</P>
         */
        public static final String STATUS = "st";

        /**
         * The {@code transaction-id} of the message.
         * <P>Type: TEXT</P>
         */
        public static final String TRANSACTION_ID = "tr_id";

        /**
         * The {@code retrieve-status} of the message.
         * <P>Type: INTEGER</P>
         */
        public static final String RETRIEVE_STATUS = "retr_st";

        /**
         * The {@code retrieve-text} of the message.
         * <P>Type: TEXT</P>
         */
        public static final String RETRIEVE_TEXT = "retr_txt";

        /**
         * The character set of the retrieve-text.
         * <P>Type: INTEGER</P>
         */
        public static final String RETRIEVE_TEXT_CHARSET = "retr_txt_cs";

        /**
         * The {@code read-status} of the message.
         * <P>Type: INTEGER</P>
         */
        public static final String READ_STATUS = "read_status";

        /**
         * The {@code content-class} of the message.
         * <P>Type: INTEGER</P>
         */
        public static final String CONTENT_CLASS = "ct_cls";

        /**
         * The {@code delivery-report} of the message.
         * <P>Type: INTEGER</P>
         */
        public static final String DELIVERY_REPORT = "d_rpt";

        /**
         * The {@code delivery-time-token} of the message.
         * <P>Type: INTEGER</P>
         * @deprecated this column is no longer supported.
         * @hide
         */
        @Deprecated
        public static final String DELIVERY_TIME_TOKEN = "d_tm_tok";

        /**
         * The {@code delivery-time} of the message.
         * <P>Type: INTEGER</P>
         */
        public static final String DELIVERY_TIME = "d_tm";

        /**
         * The {@code response-text} of the message.
         * <P>Type: TEXT</P>
         */
        public static final String RESPONSE_TEXT = "resp_txt";

        /**
         * The {@code sender-visibility} of the message.
         * <P>Type: TEXT</P>
         * @deprecated this column is no longer supported.
         * @hide
         */
        @Deprecated
        public static final String SENDER_VISIBILITY = "s_vis";

        /**
         * The {@code reply-charging} of the message.
         * <P>Type: INTEGER</P>
         * @deprecated this column is no longer supported.
         * @hide
         */
        @Deprecated
        public static final String REPLY_CHARGING = "r_chg";

        /**
         * The {@code reply-charging-deadline-token} of the message.
         * <P>Type: INTEGER</P>
         * @deprecated this column is no longer supported.
         * @hide
         */
        @Deprecated
        public static final String REPLY_CHARGING_DEADLINE_TOKEN = "r_chg_dl_tok";

        /**
         * The {@code reply-charging-deadline} of the message.
         * <P>Type: INTEGER</P>
         * @deprecated this column is no longer supported.
         * @hide
         */
        @Deprecated
        public static final String REPLY_CHARGING_DEADLINE = "r_chg_dl";

        /**
         * The {@code reply-charging-id} of the message.
         * <P>Type: TEXT</P>
         * @deprecated this column is no longer supported.
         * @hide
         */
        @Deprecated
        public static final String REPLY_CHARGING_ID = "r_chg_id";

        /**
         * The {@code reply-charging-size} of the message.
         * <P>Type: INTEGER</P>
         * @deprecated this column is no longer supported.
         * @hide
         */
        @Deprecated
        public static final String REPLY_CHARGING_SIZE = "r_chg_sz";

        /**
         * The {@code previously-sent-by} of the message.
         * <P>Type: TEXT</P>
         * @deprecated this column is no longer supported.
         * @hide
         */
        @Deprecated
        public static final String PREVIOUSLY_SENT_BY = "p_s_by";

        /**
         * The {@code previously-sent-date} of the message.
         * <P>Type: INTEGER</P>
         * @deprecated this column is no longer supported.
         * @hide
         */
        @Deprecated
        public static final String PREVIOUSLY_SENT_DATE = "p_s_d";

        /**
         * The {@code store} of the message.
         * <P>Type: TEXT</P>
         * @deprecated this column is no longer supported.
         * @hide
         */
        @Deprecated
        public static final String STORE = "store";

        /**
         * The {@code mm-state} of the message.
         * <P>Type: INTEGER</P>
         * @deprecated this column is no longer supported.
         * @hide
         */
        @Deprecated
        public static final String MM_STATE = "mm_st";

        /**
         * The {@code mm-flags-token} of the message.
         * <P>Type: INTEGER</P>
         * @deprecated this column is no longer supported.
         * @hide
         */
        @Deprecated
        public static final String MM_FLAGS_TOKEN = "mm_flg_tok";

        /**
         * The {@code mm-flags} of the message.
         * <P>Type: TEXT</P>
         * @deprecated this column is no longer supported.
         * @hide
         */
        @Deprecated
        public static final String MM_FLAGS = "mm_flg";

        /**
         * The {@code store-status} of the message.
         * <P>Type: TEXT</P>
         * @deprecated this column is no longer supported.
         * @hide
         */
        @Deprecated
        public static final String STORE_STATUS = "store_st";

        /**
         * The {@code store-status-text} of the message.
         * <P>Type: TEXT</P>
         * @deprecated this column is no longer supported.
         * @hide
         */
        @Deprecated
        public static final String STORE_STATUS_TEXT = "store_st_txt";

        /**
         * The {@code stored} of the message.
         * <P>Type: TEXT</P>
         * @deprecated this column is no longer supported.
         * @hide
         */
        @Deprecated
        public static final String STORED = "stored";

        /**
         * The {@code totals} of the message.
         * <P>Type: TEXT</P>
         * @deprecated this column is no longer supported.
         * @hide
         */
        @Deprecated
        public static final String TOTALS = "totals";

        /**
         * The {@code mbox-totals} of the message.
         * <P>Type: TEXT</P>
         * @deprecated this column is no longer supported.
         * @hide
         */
        @Deprecated
        public static final String MBOX_TOTALS = "mb_t";

        /**
         * The {@code mbox-totals-token} of the message.
         * <P>Type: INTEGER</P>
         * @deprecated this column is no longer supported.
         * @hide
         */
        @Deprecated
        public static final String MBOX_TOTALS_TOKEN = "mb_t_tok";

        /**
         * The {@code quotas} of the message.
         * <P>Type: TEXT</P>
         * @deprecated this column is no longer supported.
         * @hide
         */
        @Deprecated
        public static final String QUOTAS = "qt";

        /**
         * The {@code mbox-quotas} of the message.
         * <P>Type: TEXT</P>
         * @deprecated this column is no longer supported.
         * @hide
         */
        @Deprecated
        public static final String MBOX_QUOTAS = "mb_qt";

        /**
         * The {@code mbox-quotas-token} of the message.
         * <P>Type: INTEGER</P>
         * @deprecated this column is no longer supported.
         * @hide
         */
        @Deprecated
        public static final String MBOX_QUOTAS_TOKEN = "mb_qt_tok";

        /**
         * The {@code message-count} of the message.
         * <P>Type: INTEGER</P>
         * @deprecated this column is no longer supported.
         * @hide
         */
        @Deprecated
        public static final String MESSAGE_COUNT = "m_cnt";

        /**
         * The {@code start} of the message.
         * <P>Type: INTEGER</P>
         * @deprecated this column is no longer supported.
         * @hide
         */
        @Deprecated
        public static final String START = "start";

        /**
         * The {@code distribution-indicator} of the message.
         * <P>Type: TEXT</P>
         * @deprecated this column is no longer supported.
         * @hide
         */
        @Deprecated
        public static final String DISTRIBUTION_INDICATOR = "d_ind";

        /**
         * The {@code element-descriptor} of the message.
         * <P>Type: TEXT</P>
         * @deprecated this column is no longer supported.
         * @hide
         */
        @Deprecated
        public static final String ELEMENT_DESCRIPTOR = "e_des";

        /**
         * The {@code limit} of the message.
         * <P>Type: INTEGER</P>
         * @deprecated this column is no longer supported.
         * @hide
         */
        @Deprecated
        public static final String LIMIT = "limit";

        /**
         * The {@code recommended-retrieval-mode} of the message.
         * <P>Type: INTEGER</P>
         * @deprecated this column is no longer supported.
         * @hide
         */
        @Deprecated
        public static final String RECOMMENDED_RETRIEVAL_MODE = "r_r_mod";

        /**
         * The {@code recommended-retrieval-mode-text} of the message.
         * <P>Type: TEXT</P>
         * @deprecated this column is no longer supported.
         * @hide
         */
        @Deprecated
        public static final String RECOMMENDED_RETRIEVAL_MODE_TEXT = "r_r_mod_txt";

        /**
         * The {@code status-text} of the message.
         * <P>Type: TEXT</P>
         * @deprecated this column is no longer supported.
         * @hide
         */
        @Deprecated
        public static final String STATUS_TEXT = "st_txt";

        /**
         * The {@code applic-id} of the message.
         * <P>Type: TEXT</P>
         * @deprecated this column is no longer supported.
         * @hide
         */
        @Deprecated
        public static final String APPLIC_ID = "apl_id";

        /**
         * The {@code reply-applic-id} of the message.
         * <P>Type: TEXT</P>
         * @deprecated this column is no longer supported.
         * @hide
         */
        @Deprecated
        public static final String REPLY_APPLIC_ID = "r_apl_id";

        /**
         * The {@code aux-applic-id} of the message.
         * <P>Type: TEXT</P>
         * @deprecated this column is no longer supported.
         * @hide
         */
        @Deprecated
        public static final String AUX_APPLIC_ID = "aux_apl_id";

        /**
         * The {@code drm-content} of the message.
         * <P>Type: TEXT</P>
         * @deprecated this column is no longer supported.
         * @hide
         */
        @Deprecated
        public static final String DRM_CONTENT = "drm_c";

        /**
         * The {@code adaptation-allowed} of the message.
         * <P>Type: TEXT</P>
         * @deprecated this column is no longer supported.
         * @hide
         */
        @Deprecated
        public static final String ADAPTATION_ALLOWED = "adp_a";

        /**
         * The {@code replace-id} of the message.
         * <P>Type: TEXT</P>
         * @deprecated this column is no longer supported.
         * @hide
         */
        @Deprecated
        public static final String REPLACE_ID = "repl_id";

        /**
         * The {@code cancel-id} of the message.
         * <P>Type: TEXT</P>
         * @deprecated this column is no longer supported.
         * @hide
         */
        @Deprecated
        public static final String CANCEL_ID = "cl_id";

        /**
         * The {@code cancel-status} of the message.
         * <P>Type: INTEGER</P>
         * @deprecated this column is no longer supported.
         * @hide
         */
        @Deprecated
        public static final String CANCEL_STATUS = "cl_st";

        /**
         * Is the message locked?
         * <P>Type: INTEGER (boolean)</P>
         */
        public static final String LOCKED = "locked";
    }

    /**
     * Columns for the "canonical_addresses" table used by MMS and SMS.
     */
    public interface CanonicalAddressesColumns extends BaseColumns {
        /**
         * An address used in MMS or SMS.  Email addresses are
         * converted to lower case and are compared by string
         * equality.  Other addresses are compared using
         * PHONE_NUMBERS_EQUAL.
         * <P>Type: TEXT</P>
         */
        public static final String ADDRESS = "address";
    }

    /**
     * Columns for the "threads" table used by MMS and SMS.
     */
    public interface ThreadsColumns extends BaseColumns {

        /**
         * The date at which the thread was created.
         * <P>Type: INTEGER (long)</P>
         */
        public static final String DATE = "date";

        /**
         * A string encoding of the recipient IDs of the recipients of
         * the message, in numerical order and separated by spaces.
         * <P>Type: TEXT</P>
         */
        public static final String RECIPIENT_IDS = "recipient_ids";

        /**
         * The message count of the thread.
         * <P>Type: INTEGER</P>
         */
        public static final String MESSAGE_COUNT = "message_count";

        /**
         * Indicates whether all messages of the thread have been read.
         * <P>Type: INTEGER</P>
         */
        public static final String READ = "read";

        /**
         * The snippet of the latest message in the thread.
         * <P>Type: TEXT</P>
         */
        public static final String SNIPPET = "snippet";

        /**
         * The charset of the snippet.
         * <P>Type: INTEGER</P>
         */
        public static final String SNIPPET_CHARSET = "snippet_cs";

        /**
         * Type of the thread, either {@link Threads#COMMON_THREAD} or
         * {@link Threads#BROADCAST_THREAD}.
         * <P>Type: INTEGER</P>
         */
        public static final String TYPE = "type";

        /**
         * Indicates whether there is a transmission error in the thread.
         * <P>Type: INTEGER</P>
         */
        public static final String ERROR = "error";

        /**
         * Indicates whether this thread contains any attachments.
         * <P>Type: INTEGER</P>
         */
        public static final String HAS_ATTACHMENT = "has_attachment";
    }

    /**
     * Helper functions for the "threads" table used by MMS and SMS.
     */
    public static final class Threads implements ThreadsColumns {

        private static final String[] ID_PROJECTION = { BaseColumns._ID };

        /**
         * Private {@code content://} style URL for this table. Used by
         * {@link #getOrCreateThreadId(android.content.Context, java.util.Set)}.
         */
        private static final Uri THREAD_ID_CONTENT_URI = Uri.parse(
                "content://mms-sms/threadID");

        /**
         * The {@code content://} style URL for this table, by conversation.
         */
        public static final Uri CONTENT_URI = Uri.withAppendedPath(
                MmsSms.CONTENT_URI, "conversations");

        /**
         * The {@code content://} style URL for this table, for obsolete threads.
         */
        public static final Uri OBSOLETE_THREADS_URI = Uri.withAppendedPath(
                CONTENT_URI, "obsolete");

        /** Thread type: common thread. */
        public static final int COMMON_THREAD    = 0;

        /** Thread type: broadcast thread. */
        public static final int BROADCAST_THREAD = 1;

        /**
         * Not instantiable.
         * @hide
         */
        private Threads() {
        }

        /**
         * This is a single-recipient version of {@code getOrCreateThreadId}.
         * It's convenient for use with SMS messages.
         * @param context the context object to use.
         * @param recipient the recipient to send to.
         * @hide
         */
        public static long getOrCreateThreadId(Context context, String recipient) {
            Set<String> recipients = new HashSet<String>();

            recipients.add(recipient);
            return getOrCreateThreadId(context, recipients);
        }

        /**
         * Given the recipients list and subject of an unsaved message,
         * return its thread ID.  If the message starts a new thread,
         * allocate a new thread ID.  Otherwise, use the appropriate
         * existing thread ID.
         *
         * <p>Find the thread ID of the same set of recipients (in any order,
         * without any additions). If one is found, return it. Otherwise,
         * return a unique thread ID.</p>
         * @hide
         */
        public static long getOrCreateThreadId(
                Context context, Set<String> recipients) {
            Uri.Builder uriBuilder = THREAD_ID_CONTENT_URI.buildUpon();

            for (String recipient : recipients) {
                if (Mms.isEmailAddress(recipient)) {
                    recipient = Mms.extractAddrSpec(recipient);
                }

                uriBuilder.appendQueryParameter("recipient", recipient);
            }

            Uri uri = uriBuilder.build();
            //if (DEBUG) Log.v(TAG, "getOrCreateThreadId uri: " + uri);

            Cursor cursor = SqliteWrapper.query(context, context.getContentResolver(),
                    uri, ID_PROJECTION, null, null, null);
            if (cursor != null) {
                try {
                    if (cursor.moveToFirst()) {
                        return cursor.getLong(0);
                    } else {
                        Log.e(TAG, "getOrCreateThreadId returned no rows!");
                    }
                } finally {
                    cursor.close();
                }
            }

            Log.e(TAG, "getOrCreateThreadId failed with uri " + uri.toString());
            throw new IllegalArgumentException("Unable to find or allocate a thread ID.");
        }
    }

    /**
     * Contains all MMS messages.
     */
    public static final class Mms implements BaseMmsColumns {

        /**
         * Not instantiable.
         * @hide
         */
        private Mms() {
        }

        /**
         * The {@code content://} URI for this table.
         */
        public static final Uri CONTENT_URI = Uri.parse("content://mms");

        /**
         * Content URI for getting MMS report requests.
         */
        public static final Uri REPORT_REQUEST_URI = Uri.withAppendedPath(
                CONTENT_URI, "report-request");

        /**
         * Content URI for getting MMS report status.
         */
        public static final Uri REPORT_STATUS_URI = Uri.withAppendedPath(
                CONTENT_URI, "report-status");

        /**
         * The default sort order for this table.
         */
        public static final String DEFAULT_SORT_ORDER = "date DESC";

        /**
         * Regex pattern for names and email addresses.
         * <ul>
         *     <li><em>mailbox</em> = {@code name-addr}</li>
         *     <li><em>name-addr</em> = {@code [display-name] angle-addr}</li>
         *     <li><em>angle-addr</em> = {@code [CFWS] "<" addr-spec ">" [CFWS]}</li>
         * </ul>
         * @hide
         */
        public static final Pattern NAME_ADDR_EMAIL_PATTERN =
                Pattern.compile("\\s*(\"[^\"]*\"|[^<>\"]+)\\s*<([^<>]+)>\\s*");

        /**
         * Helper method to query this table.
         * @hide
         */
        public static Cursor query(
                ContentResolver cr, String[] projection) {
            return cr.query(CONTENT_URI, projection, null, null, DEFAULT_SORT_ORDER);
        }

        /**
         * Helper method to query this table.
         * @hide
         */
        public static Cursor query(
                ContentResolver cr, String[] projection,
                String where, String orderBy) {
            return cr.query(CONTENT_URI, projection,
                    where, null, orderBy == null ? DEFAULT_SORT_ORDER : orderBy);
        }

        /**
         * Helper method to extract email address from address string.
         * @hide
         */
        public static String extractAddrSpec(String address) {
            Matcher match = NAME_ADDR_EMAIL_PATTERN.matcher(address);

            if (match.matches()) {
                return match.group(2);
            }
            return address;
        }

        /**
         * Is the specified address an email address?
         *
         * @param address the input address to test
         * @return true if address is an email address; false otherwise.
         * @hide
         */
        public static boolean isEmailAddress(String address) {
            if (TextUtils.isEmpty(address)) {
                return false;
            }

            String s = extractAddrSpec(address);
            Matcher match = Patterns.EMAIL_ADDRESS.matcher(s);
            return match.matches();
        }

        /**
         * Is the specified number a phone number?
         *
         * @param number the input number to test
         * @return true if number is a phone number; false otherwise.
         * @hide
         */
        public static boolean isPhoneNumber(String number) {
            if (TextUtils.isEmpty(number)) {
                return false;
            }

            Matcher match = Patterns.PHONE.matcher(number);
            return match.matches();
        }

        /**
         * Contains all MMS messages in the MMS app inbox.
         */
        public static final class Inbox implements BaseMmsColumns {

            /**
             * Not instantiable.
             * @hide
             */
            private Inbox() {
            }

            /**
             * The {@code content://} style URL for this table.
             */
            public static final Uri
                    CONTENT_URI = Uri.parse("content://mms/inbox");

            /**
             * The default sort order for this table.
             */
            public static final String DEFAULT_SORT_ORDER = "date DESC";
        }

        /**
         * Contains all MMS messages in the MMS app sent folder.
         */
        public static final class Sent implements BaseMmsColumns {

            /**
             * Not instantiable.
             * @hide
             */
            private Sent() {
            }

            /**
             * The {@code content://} style URL for this table.
             */
            public static final Uri
                    CONTENT_URI = Uri.parse("content://mms/sent");

            /**
             * The default sort order for this table.
             */
            public static final String DEFAULT_SORT_ORDER = "date DESC";
        }

        /**
         * Contains all MMS messages in the MMS app drafts folder.
         */
        public static final class Draft implements BaseMmsColumns {

            /**
             * Not instantiable.
             * @hide
             */
            private Draft() {
            }

            /**
             * The {@code content://} style URL for this table.
             */
            public static final Uri
                    CONTENT_URI = Uri.parse("content://mms/drafts");

            /**
             * The default sort order for this table.
             */
            public static final String DEFAULT_SORT_ORDER = "date DESC";
        }

        /**
         * Contains all MMS messages in the MMS app outbox.
         */
        public static final class Outbox implements BaseMmsColumns {

            /**
             * Not instantiable.
             * @hide
             */
            private Outbox() {
            }

            /**
             * The {@code content://} style URL for this table.
             */
            public static final Uri
                    CONTENT_URI = Uri.parse("content://mms/outbox");

            /**
             * The default sort order for this table.
             */
            public static final String DEFAULT_SORT_ORDER = "date DESC";
        }

        /**
         * Contains address information for an MMS message.
         */
        public static final class Addr implements BaseColumns {

            /**
             * Not instantiable.
             * @hide
             */
            private Addr() {
            }

            /**
             * The ID of MM which this address entry belongs to.
             * <P>Type: INTEGER (long)</P>
             */
            public static final String MSG_ID = "msg_id";

            /**
             * The ID of contact entry in Phone Book.
             * <P>Type: INTEGER (long)</P>
             */
            public static final String CONTACT_ID = "contact_id";

            /**
             * The address text.
             * <P>Type: TEXT</P>
             */
            public static final String ADDRESS = "address";

            /**
             * Type of address: must be one of {@code PduHeaders.BCC},
             * {@code PduHeaders.CC}, {@code PduHeaders.FROM}, {@code PduHeaders.TO}.
             * <P>Type: INTEGER</P>
             */
            public static final String TYPE = "type";

            /**
             * Character set of this entry (MMS charset value).
             * <P>Type: INTEGER</P>
             */
            public static final String CHARSET = "charset";
        }

        /**
         * Contains message parts.
         */
        public static final class Part implements BaseColumns {

            /**
             * Not instantiable.
             * @hide
             */
            private Part() {
            }

            /**
             * The identifier of the message which this part belongs to.
             * <P>Type: INTEGER</P>
             */
            public static final String MSG_ID = "mid";

            /**
             * The order of the part.
             * <P>Type: INTEGER</P>
             */
            public static final String SEQ = "seq";

            /**
             * The content type of the part.
             * <P>Type: TEXT</P>
             */
            public static final String CONTENT_TYPE = "ct";

            /**
             * The name of the part.
             * <P>Type: TEXT</P>
             */
            public static final String NAME = "name";

            /**
             * The charset of the part.
             * <P>Type: TEXT</P>
             */
            public static final String CHARSET = "chset";

            /**
             * The file name of the part.
             * <P>Type: TEXT</P>
             */
            public static final String FILENAME = "fn";

            /**
             * The content disposition of the part.
             * <P>Type: TEXT</P>
             */
            public static final String CONTENT_DISPOSITION = "cd";

            /**
             * The content ID of the part.
             * <P>Type: INTEGER</P>
             */
            public static final String CONTENT_ID = "cid";

            /**
             * The content location of the part.
             * <P>Type: INTEGER</P>
             */
            public static final String CONTENT_LOCATION = "cl";

            /**
             * The start of content-type of the message.
             * <P>Type: INTEGER</P>
             */
            public static final String CT_START = "ctt_s";

            /**
             * The type of content-type of the message.
             * <P>Type: TEXT</P>
             */
            public static final String CT_TYPE = "ctt_t";

            /**
             * The location (on filesystem) of the binary data of the part.
             * <P>Type: INTEGER</P>
             */
            public static final String _DATA = "_data";

            /**
             * The message text.
             * <P>Type: TEXT</P>
             */
            public static final String TEXT = "text";
        }

        /**
         * Message send rate table.
         */
        public static final class Rate {

            /**
             * Not instantiable.
             * @hide
             */
            private Rate() {
            }

            /**
             * The {@code content://} style URL for this table.
             */
            public static final Uri CONTENT_URI = Uri.withAppendedPath(
                    Mms.CONTENT_URI, "rate");

            /**
             * When a message was successfully sent.
             * <P>Type: INTEGER (long)</P>
             */
            public static final String SENT_TIME = "sent_time";
        }

        /**
         * Intents class.
         */

        public static final class Intents {

            /**
             * Not instantiable.
             * @hide
             */
            private Intents() {
            }

            /**
             * Indicates that the contents of specified URIs were changed.
             * The application which is showing or caching these contents
             * should be updated.
             */
            //@SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
            public static final String CONTENT_CHANGED_ACTION
                    = "android.intent.action.CONTENT_CHANGED";

            /**
             * An extra field which stores the URI of deleted contents.
             */
            public static final String DELETED_CONTENTS = "deleted_contents";
        }
    }

    /**
     * Contains all MMS and SMS messages.
     */
    public static final class MmsSms implements BaseColumns {

        /**
         * Not instantiable.
         * @hide
         */
        private MmsSms() {
        }

        /**
         * The column to distinguish SMS and MMS messages in query results.
         */
        public static final String TYPE_DISCRIMINATOR_COLUMN =
                "transport_type";

        /**
         * The {@code content://} style URL for this table.
         */
        public static final Uri CONTENT_URI = Uri.parse("content://mms-sms/");

        /**
         * The {@code content://} style URL for this table, by conversation.
         */
        public static final Uri CONTENT_CONVERSATIONS_URI = Uri.parse(
                "content://mms-sms/conversations");

        /**
         * The {@code content://} style URL for this table, by phone number.
         */
        public static final Uri CONTENT_FILTER_BYPHONE_URI = Uri.parse(
                "content://mms-sms/messages/byphone");

        /**
         * The {@code content://} style URL for undelivered messages in this table.
         */
        public static final Uri CONTENT_UNDELIVERED_URI = Uri.parse(
                "content://mms-sms/undelivered");

        /**
         * The {@code content://} style URL for draft messages in this table.
         */
        public static final Uri CONTENT_DRAFT_URI = Uri.parse(
                "content://mms-sms/draft");

        /**
         * The {@code content://} style URL for locked messages in this table.
         */
        public static final Uri CONTENT_LOCKED_URI = Uri.parse(
                "content://mms-sms/locked");

        /**
         * Pass in a query parameter called "pattern" which is the text to search for.
         * The sort order is fixed to be: {@code thread_id ASC, date DESC}.
         */
        public static final Uri SEARCH_URI = Uri.parse(
                "content://mms-sms/search");

        // Constants for message protocol types.

        /** SMS protocol type. */
        public static final int SMS_PROTO = 0;

        /** MMS protocol type. */
        public static final int MMS_PROTO = 1;

        // Constants for error types of pending messages.

        /** Error type: no error. */
        public static final int NO_ERROR                      = 0;

        /** Error type: generic transient error. */
        public static final int ERR_TYPE_GENERIC              = 1;

        /** Error type: SMS protocol transient error. */
        public static final int ERR_TYPE_SMS_PROTO_TRANSIENT  = 2;

        /** Error type: MMS protocol transient error. */
        public static final int ERR_TYPE_MMS_PROTO_TRANSIENT  = 3;

        /** Error type: transport failure. */
        public static final int ERR_TYPE_TRANSPORT_FAILURE    = 4;

        /** Error type: permanent error (along with all higher error values). */
        public static final int ERR_TYPE_GENERIC_PERMANENT    = 10;

        /** Error type: SMS protocol permanent error. */
        public static final int ERR_TYPE_SMS_PROTO_PERMANENT  = 11;

        /** Error type: MMS protocol permanent error. */
        public static final int ERR_TYPE_MMS_PROTO_PERMANENT  = 12;

        /**
         * Contains pending messages info.
         */
        public static final class PendingMessages implements BaseColumns {

            /**
             * Not instantiable.
             * @hide
             */
            private PendingMessages() {
            }

            public static final Uri CONTENT_URI = Uri.withAppendedPath(
                    MmsSms.CONTENT_URI, "pending");

            /**
             * The type of transport protocol (MMS or SMS).
             * <P>Type: INTEGER</P>
             */
            public static final String PROTO_TYPE = "proto_type";

            /**
             * The ID of the message to be sent or downloaded.
             * <P>Type: INTEGER (long)</P>
             */
            public static final String MSG_ID = "msg_id";

            /**
             * The type of the message to be sent or downloaded.
             * This field is only valid for MM. For SM, its value is always set to 0.
             * <P>Type: INTEGER</P>
             */
            public static final String MSG_TYPE = "msg_type";

            /**
             * The type of the error code.
             * <P>Type: INTEGER</P>
             */
            public static final String ERROR_TYPE = "err_type";

            /**
             * The error code of sending/retrieving process.
             * <P>Type: INTEGER</P>
             */
            public static final String ERROR_CODE = "err_code";

            /**
             * How many times we tried to send or download the message.
             * <P>Type: INTEGER</P>
             */
            public static final String RETRY_INDEX = "retry_index";

            /**
             * The time to do next retry.
             * <P>Type: INTEGER (long)</P>
             */
            public static final String DUE_TIME = "due_time";

            /**
             * The time we last tried to send or download the message.
             * <P>Type: INTEGER (long)</P>
             */
            public static final String LAST_TRY = "last_try";
        }

        /**
         * Words table used by provider for full-text searches.
         * @hide
         */
        public static final class WordsTable {

            /**
             * Not instantiable.
             * @hide
             */
            private WordsTable() {}

            /**
             * Primary key.
             * <P>Type: INTEGER (long)</P>
             */
            public static final String ID = "_id";

            /**
             * Source row ID.
             * <P>Type: INTEGER (long)</P>
             */
            public static final String SOURCE_ROW_ID = "source_id";

            /**
             * Table ID (either 1 or 2).
             * <P>Type: INTEGER</P>
             */
            public static final String TABLE_ID = "table_to_use";

            /**
             * The words to index.
             * <P>Type: TEXT</P>
             */
            public static final String INDEXED_TEXT = "index_text";
        }
    }

    /**
     * Carriers class contains information about APNs, including MMSC information.
     */
    public static final class Carriers implements BaseColumns {

        /**
         * Not instantiable.
         * @hide
         */
        private Carriers() {}

        /**
         * The {@code content://} style URL for this table.
         */
        public static final Uri CONTENT_URI = Uri.parse("content://telephony/carriers");

        /**
         * The default sort order for this table.
         */
        public static final String DEFAULT_SORT_ORDER = "name ASC";

        /**
         * Entry name.
         * <P>Type: TEXT</P>
         */
        public static final String NAME = "name";

        /**
         * APN name.
         * <P>Type: TEXT</P>
         */
        public static final String APN = "apn";

        /**
         * Proxy address.
         * <P>Type: TEXT</P>
         */
        public static final String PROXY = "proxy";

        /**
         * Proxy port.
         * <P>Type: TEXT</P>
         */
        public static final String PORT = "port";

        /**
         * MMS proxy address.
         * <P>Type: TEXT</P>
         */
        public static final String MMSPROXY = "mmsproxy";

        /**
         * MMS proxy port.
         * <P>Type: TEXT</P>
         */
        public static final String MMSPORT = "mmsport";

        /**
         * Server address.
         * <P>Type: TEXT</P>
         */
        public static final String SERVER = "server";

        /**
         * APN username.
         * <P>Type: TEXT</P>
         */
        public static final String USER = "user";

        /**
         * APN password.
         * <P>Type: TEXT</P>
         */
        public static final String PASSWORD = "password";

        /**
         * MMSC URL.
         * <P>Type: TEXT</P>
         */
        public static final String MMSC = "mmsc";

        /**
         * Mobile Country Code (MCC).
         * <P>Type: TEXT</P>
         */
        public static final String MCC = "mcc";

        /**
         * Mobile Network Code (MNC).
         * <P>Type: TEXT</P>
         */
        public static final String MNC = "mnc";

        /**
         * Numeric operator ID (as String). Usually {@code MCC + MNC}.
         * <P>Type: TEXT</P>
         */
        public static final String NUMERIC = "numeric";

        /**
         * Authentication type.
         * <P>Type:  INTEGER</P>
         */
        public static final String AUTH_TYPE = "authtype";

        /**
         * Comma-delimited list of APN types.
         * <P>Type: TEXT</P>
         */
        public static final String TYPE = "type";

        /**
         * The protocol to use to connect to this APN.
         *
         * One of the {@code PDP_type} values in TS 27.007 section 10.1.1.
         * For example: {@code IP}, {@code IPV6}, {@code IPV4V6}, or {@code PPP}.
         * <P>Type: TEXT</P>
         */
        public static final String PROTOCOL = "protocol";

        /**
         * The protocol to use to connect to this APN when roaming.
         * The syntax is the same as protocol.
         * <P>Type: TEXT</P>
         */
        public static final String ROAMING_PROTOCOL = "roaming_protocol";

        /**
         * Is this the current APN?
         * <P>Type: INTEGER (boolean)</P>
         */
        public static final String CURRENT = "current";

        /**
         * Is this APN enabled?
         * <P>Type: INTEGER (boolean)</P>
         */
        public static final String CARRIER_ENABLED = "carrier_enabled";

        /**
         * Radio Access Technology info.
         * To check what values are allowed, refer to {@link android.telephony.ServiceState}.
         * This should be spread to other technologies,
         * but is currently only used for LTE (14) and eHRPD (13).
         * <P>Type: INTEGER</P>
         */
        public static final String BEARER = "bearer";

        /**
         * MVNO type:
         * {@code SPN (Service Provider Name), IMSI, GID (Group Identifier Level 1)}.
         * <P>Type: TEXT</P>
         */
        public static final String MVNO_TYPE = "mvno_type";

        /**
         * MVNO data.
         * Use the following examples.
         * <ul>
         *     <li>SPN: A MOBILE, BEN NL, ...</li>
         *     <li>IMSI: 302720x94, 2060188, ...</li>
         *     <li>GID: 4E, 33, ...</li>
         * </ul>
         * <P>Type: TEXT</P>
         */
        public static final String MVNO_MATCH_DATA = "mvno_match_data";
    }

    /**
     * Contains received SMS cell broadcast messages.
     * @hide
     */
    public static final class CellBroadcasts implements BaseColumns {

        /**
         * Not instantiable.
         * @hide
         */
        private CellBroadcasts() {}

        /**
         * The {@code content://} URI for this table.
         */
        public static final Uri CONTENT_URI = Uri.parse("content://cellbroadcasts");

        /**
         * Message geographical scope.
         * <P>Type: INTEGER</P>
         */
        public static final String GEOGRAPHICAL_SCOPE = "geo_scope";

        /**
         * Message serial number.
         * <P>Type: INTEGER</P>
         */
        public static final String SERIAL_NUMBER = "serial_number";

        /**
         * PLMN of broadcast sender. {@code SERIAL_NUMBER + PLMN + LAC + CID} uniquely identifies
         * a broadcast for duplicate detection purposes.
         * <P>Type: TEXT</P>
         */
        public static final String PLMN = "plmn";

        /**
         * Location Area (GSM) or Service Area (UMTS) of broadcast sender. Unused for CDMA.
         * Only included if Geographical Scope of message is not PLMN wide (01).
         * <P>Type: INTEGER</P>
         */
        public static final String LAC = "lac";

        /**
         * Cell ID of message sender (GSM/UMTS). Unused for CDMA. Only included when the
         * Geographical Scope of message is cell wide (00 or 11).
         * <P>Type: INTEGER</P>
         */
        public static final String CID = "cid";

        /**
         * Message code. <em>OBSOLETE: merged into SERIAL_NUMBER.</em>
         * <P>Type: INTEGER</P>
         */
        public static final String V1_MESSAGE_CODE = "message_code";

        /**
         * Message identifier. <em>OBSOLETE: renamed to SERVICE_CATEGORY.</em>
         * <P>Type: INTEGER</P>
         */
        public static final String V1_MESSAGE_IDENTIFIER = "message_id";

        /**
         * Service category (GSM/UMTS: message identifier; CDMA: service category).
         * <P>Type: INTEGER</P>
         */
        public static final String SERVICE_CATEGORY = "service_category";

        /**
         * Message language code.
         * <P>Type: TEXT</P>
         */
        public static final String LANGUAGE_CODE = "language";

        /**
         * Message body.
         * <P>Type: TEXT</P>
         */
        public static final String MESSAGE_BODY = "body";

        /**
         * Message delivery time.
         * <P>Type: INTEGER (long)</P>
         */
        public static final String DELIVERY_TIME = "date";

        /**
         * Has the message been viewed?
         * <P>Type: INTEGER (boolean)</P>
         */
        public static final String MESSAGE_READ = "read";

        /**
         * Message format (3GPP or 3GPP2).
         * <P>Type: INTEGER</P>
         */
        public static final String MESSAGE_FORMAT = "format";

        /**
         * Message priority (including emergency).
         * <P>Type: INTEGER</P>
         */
        public static final String MESSAGE_PRIORITY = "priority";

        /**
         * ETWS warning type (ETWS alerts only).
         * <P>Type: INTEGER</P>
         */
        public static final String ETWS_WARNING_TYPE = "etws_warning_type";

        /**
         * CMAS message class (CMAS alerts only).
         * <P>Type: INTEGER</P>
         */
        public static final String CMAS_MESSAGE_CLASS = "cmas_message_class";

        /**
         * CMAS category (CMAS alerts only).
         * <P>Type: INTEGER</P>
         */
        public static final String CMAS_CATEGORY = "cmas_category";

        /**
         * CMAS response type (CMAS alerts only).
         * <P>Type: INTEGER</P>
         */
        public static final String CMAS_RESPONSE_TYPE = "cmas_response_type";

        /**
         * CMAS severity (CMAS alerts only).
         * <P>Type: INTEGER</P>
         */
        public static final String CMAS_SEVERITY = "cmas_severity";

        /**
         * CMAS urgency (CMAS alerts only).
         * <P>Type: INTEGER</P>
         */
        public static final String CMAS_URGENCY = "cmas_urgency";

        /**
         * CMAS certainty (CMAS alerts only).
         * <P>Type: INTEGER</P>
         */
        public static final String CMAS_CERTAINTY = "cmas_certainty";

        /** The default sort order for this table. */
        public static final String DEFAULT_SORT_ORDER = DELIVERY_TIME + " DESC";

        public static final String[] QUERY_COLUMNS = {
                _ID,
                GEOGRAPHICAL_SCOPE,
                PLMN,
                LAC,
                CID,
                SERIAL_NUMBER,
                SERVICE_CATEGORY,
                LANGUAGE_CODE,
                MESSAGE_BODY,
                DELIVERY_TIME,
                MESSAGE_READ,
                MESSAGE_FORMAT,
                MESSAGE_PRIORITY,
                ETWS_WARNING_TYPE,
                CMAS_MESSAGE_CLASS,
                CMAS_CATEGORY,
                CMAS_RESPONSE_TYPE,
                CMAS_SEVERITY,
                CMAS_URGENCY,
                CMAS_CERTAINTY
        };
    }
}
