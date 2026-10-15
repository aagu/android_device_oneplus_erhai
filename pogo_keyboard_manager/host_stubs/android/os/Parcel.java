/*
 * Copyright (C) 2026 The LineageOS Project
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

package android.os;

public class Parcel {
    public void writeString(String val) {}
    public String readString() { return null; }
    public void writeInt(int val) {}
    public int readInt() { return 0; }
    public void writeLong(long val) {}
    public long readLong() { return 0L; }
    public void writeByte(byte val) {}
    public byte readByte() { return 0; }
    public void writeParcelable(Parcelable p, int flags) {}
    public <T extends Parcelable> T readParcelable(ClassLoader loader, Class<T> clazz) { return null; }
}
