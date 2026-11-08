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

package vip.mystery0.pixel.text.mms.vendor.pdu;


import java.io.ByteArrayOutputStream;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import androidx.annotation.NonNull;

/**
 * Encoded-string-value = Text-string | Value-length Char-set Text-string
 */
public class EncodedStringValue implements Cloneable {

    /**
     * The Char-set value.
     */
    private int mCharacterSet;

    /**
     * The Text-string value.
     */
    private byte[] mData;

    /**
     * Constructor.
     *
     * @param charset the Char-set value
     * @param data the Text-string value
     * @throws NullPointerException if Text-string value is null.
     */
    public EncodedStringValue(int charset, byte[] data) {
        // TODO: CharSet needs to be validated against MIBEnum.
        if(null == data) {
            throw new NullPointerException("EncodedStringValue: Text-string is null.");
        }

        mCharacterSet = charset;
        mData = new byte[data.length];
        System.arraycopy(data, 0, mData, 0, data.length);
    }

    /**
     * Constructor.
     *
     * @param data the Text-string value
     * @throws NullPointerException if Text-string value is null.
     */
    public EncodedStringValue(byte[] data) {
        this(CharacterSets.DEFAULT_CHARSET, data);
    }
    public EncodedStringValue(String data) {
        mData = data.getBytes(StandardCharsets.UTF_8);
        mCharacterSet = CharacterSets.DEFAULT_CHARSET;
    }

    /**
     * Get Char-set value.
     *
     * @return the value
     */
    public int getCharacterSet() {
        return mCharacterSet;
    }

    /**
     * Set Char-set value.
     *
     * @param charset the Char-set value
     */
    public void setCharacterSet(int charset) {
        // TODO: CharSet needs to be validated against MIBEnum.
        mCharacterSet = charset;
    }

    /**
     * Get Text-string value.
     *
     * @return the value
     */
    public byte[] getTextString() {
        byte[] byteArray = new byte[mData.length];

        System.arraycopy(mData, 0, byteArray, 0, mData.length);
        return byteArray;
    }

    /**
     * Set Text-string value.
     *
     * @param textString the Text-string value
     * @throws NullPointerException if Text-string value is null.
     */
    public void setTextString(byte[] textString) {
        if(null == textString) {
            throw new NullPointerException("EncodedStringValue: Text-string is null.");
        }

        mData = new byte[textString.length];
        System.arraycopy(textString, 0, mData, 0, textString.length);
    }

    /**
     * Convert this object to a {@link java.lang.String}. If the encoding of
     * the EncodedStringValue is null or unsupported, it will be
     * treated as iso-8859-1 encoding.
     *
     * @return The decoded String.
     */
    public String getString()  {
        if (CharacterSets.ANY_CHARSET == mCharacterSet) {
            return new String(mData); // system default encoding.
        } else {
            try {
                String name = CharacterSets.getMimeName(mCharacterSet);
                return new String(mData, name);
            } catch (UnsupportedEncodingException ignored) {
                return new String(mData, StandardCharsets.ISO_8859_1);
            }
        }
    }

    /**
     * Append to Text-string.
     *
     * @param textString the textString to append
     * @throws NullPointerException 文本为空时抛出
     */
    public void appendTextString(byte[] textString) {
        if(null == textString) {
            throw new NullPointerException("Text-string is null.");
        }

        if(null == mData) {
            mData = new byte[textString.length];
            System.arraycopy(textString, 0, mData, 0, textString.length);
        } else {
            ByteArrayOutputStream newTextString = new ByteArrayOutputStream();
            newTextString.write(mData, 0, mData.length);
            newTextString.write(textString, 0, textString.length);

            mData = newTextString.toByteArray();
        }
    }

    /*
     * (non-Javadoc)
     * @see java.lang.Object#clone()
     */
    @Override
    @NonNull
    public EncodedStringValue clone() throws CloneNotSupportedException {
        EncodedStringValue copy = (EncodedStringValue) super.clone();
        copy.mData = mData.clone();
        return copy;
    }

    /**
     * Split this encoded string around matches of the given pattern.
     *
     * @param pattern the delimiting pattern
     * @return the array of encoded strings computed by splitting this encoded
     *         string around matches of the given pattern
     */
    public EncodedStringValue[] split(String pattern) {
        String[] temp = getString().split(pattern);
        EncodedStringValue[] ret = new EncodedStringValue[temp.length];
        for (int i = 0; i < ret.length; ++i) {
            try {
                ret[i] = new EncodedStringValue(mCharacterSet,
                        temp[i].getBytes());
            } catch (NullPointerException e) {
                // Can't arrive here
                return null;
            }
        }
        return ret;
    }

    /**
     * Extract an EncodedStringValue[] from a given String.
     */
    public static EncodedStringValue[] extract(String src) {
        String[] values = src.split(";");

        ArrayList<EncodedStringValue> list = new ArrayList<>();
        for (String value : values) {
            if (!value.isEmpty()) {
                list.add(new EncodedStringValue(value));
            }
        }

        int len = list.size();
        if (len > 0) {
            return list.toArray(new EncodedStringValue[len]);
        } else {
            return null;
        }
    }

    /**
     * Concatenate an EncodedStringValue[] into a single String.
     */
    public static String concat(EncodedStringValue[] addr) {
        StringBuilder sb = new StringBuilder();
        int maxIndex = addr.length - 1;
        for (int i = 0; i <= maxIndex; i++) {
            sb.append(addr[i].getString());
            if (i < maxIndex) {
                sb.append(";");
            }
        }

        return sb.toString();
    }
    public static EncodedStringValue copy(EncodedStringValue value) {
        if (value == null) {
            return null;
        }

        return new EncodedStringValue(value.mCharacterSet, value.mData);
    }
    public static EncodedStringValue[] encodeStrings(String[] array) {
        int count = array.length;
        if (count > 0) {
            EncodedStringValue[] encodedArray = new EncodedStringValue[count];
            for (int i = 0; i < count; i++) {
                encodedArray[i] = new EncodedStringValue(array[i]);
            }
            return encodedArray;
        }
        return null;
    }
}
