package org.jetbrains.java.decompiler.code;

public final class BytecodeVersion implements Comparable<BytecodeVersion> {
  public final int major;
  public final int minor;

  public BytecodeVersion(int major, int minor) {
    this.major = major & 0xffff;
    this.minor = minor & 0xffff;
  }

  public int toInt() {
    return major << 16 | minor;
  }

  public boolean hasEnums() {
    return major >= MAJOR_5;
  }

  public boolean hasInvokeDynamic() {
    return major >= MAJOR_7;
  }

  public boolean hasLambdas() {
    return major >= MAJOR_8;
  }

  public boolean hasIndyStringConcat() {
    return major >= MAJOR_9;
  }

  public boolean hasSealedClasses() {
    return major >= MAJOR_17 || (major >= MAJOR_15 && minor == PREVIEW);
  }

  @Override
  public int compareTo(BytecodeVersion o) {
    int cmp = Integer.compare(major, o.major);
    if (cmp != 0) return cmp;
    return Integer.compare(minor, o.minor);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    BytecodeVersion that = (BytecodeVersion) o;
    return major == that.major && minor == that.minor;
  }

  @Override
  public int hashCode() {
    return toInt();
  }

  public static BytecodeVersion fromInt(int encoded) {
    return new BytecodeVersion(encoded >> 16, encoded);
  }

  public static final int PREVIEW = 65535;
  public static final int MAJOR_1_0_2 = 45;
  public static final int VERSION_1_0_2 = MAJOR_1_0_2 << 16;
  public static final int MAJOR_1_2 = 46;
  public static final int VERSION_1_2 = MAJOR_1_2 << 16;
  public static final int MAJOR_1_3 = 47;
  public static final int VERSION_1_3 = MAJOR_1_3 << 16;
  public static final int MAJOR_1_4 = 48;
  public static final int VERSION_1_4 = MAJOR_1_4 << 16;
  public static final int MAJOR_5 = 49;
  public static final int VERSION_5 = MAJOR_5 << 16;
  public static final int MAJOR_6 = 50;
  public static final int VERSION_6 = MAJOR_6 << 16;
  public static final int MAJOR_7 = 51;
  public static final int VERSION_7 = MAJOR_7 << 16;
  public static final int MAJOR_8 = 52;
  public static final int VERSION_8 = MAJOR_8 << 16;
  public static final int MAJOR_9 = 53;
  public static final int VERSION_9 = MAJOR_9 << 16;
  public static final int MAJOR_10 = 54;
  public static final int VERSION_10 = MAJOR_10 << 16;
  public static final int MAJOR_11 = 55;
  public static final int VERSION_11 = MAJOR_11 << 16;
  public static final int MAJOR_12 = 56;
  public static final int VERSION_12 = MAJOR_12 << 16;
  public static final int VERSION_12_PREVIEW = VERSION_12 | PREVIEW;
  public static final int MAJOR_13 = 57;
  public static final int VERSION_13 = MAJOR_13 << 16;
  public static final int VERSION_13_PREVIEW = VERSION_13 | PREVIEW;
  public static final int MAJOR_14 = 58;
  public static final int VERSION_14 = MAJOR_14 << 16;
  public static final int VERSION_14_PREVIEW = VERSION_14 | PREVIEW;
  public static final int MAJOR_15 = 59;
  public static final int VERSION_15 = MAJOR_15 << 16;
  public static final int VERSION_15_PREVIEW = VERSION_15 | PREVIEW;
  public static final int MAJOR_16 = 60;
  public static final int VERSION_16 = MAJOR_16 << 16;
  public static final int VERSION_16_PREVIEW = VERSION_16 | PREVIEW;
  public static final int MAJOR_17 = 61;
  public static final int VERSION_17 = MAJOR_17 << 16;
  public static final int VERSION_17_PREVIEW = VERSION_17 | PREVIEW;
}
